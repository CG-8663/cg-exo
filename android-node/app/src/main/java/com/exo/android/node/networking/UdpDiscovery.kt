package com.exo.android.node.networking

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovery message format (JSON) to match Python nodes
 */
@Serializable
private data class DiscoveryMessage(
    val type: String = "discovery",
    val node_id: String,
    val grpc_port: Int
)

/**
 * UDP broadcast-based peer discovery
 * Mirrors Python UdpDiscovery from exo/networking/udp/udp_discovery.py
 */
class UdpDiscovery(
    private val context: Context,
    private val nodeId: String,
    private val grpcPort: Int,
    private val broadcastPort: Int = DEFAULT_BROADCAST_PORT
) : Discovery {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    override val discoveredPeers: StateFlow<Map<String, PeerInfo>> = _discoveredPeers

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private var isRunning = false

    private var broadcastJob: Job? = null
    private var listenerJob: Job? = null
    private var cleanupJob: Job? = null

    // JSON parser with lenient mode to handle various formats
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // Ensure default values are included in JSON
    }

    companion object {
        const val DEFAULT_BROADCAST_PORT = 5678
        private const val BROADCAST_INTERVAL_MS = 2500L
        private const val PEER_TIMEOUT_MS = 10000L // 10 seconds
        private const val CLEANUP_INTERVAL_MS = 5000L
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) {
            Timber.w("UDP discovery already running")
            return@withContext
        }

        Timber.i("Starting UDP discovery on port $broadcastPort")
        isRunning = true

        try {
            // Acquire multicast lock for WiFi
            acquireMulticastLock()

            // Create UDP socket
            socket = DatagramSocket(broadcastPort).apply {
                broadcast = true
                reuseAddress = true
            }

            // Start broadcaster
            broadcastJob = scope.launch {
                broadcastPresence()
            }

            // Start listener
            listenerJob = scope.launch {
                listenForPeers()
            }

            // Start cleanup job to remove stale peers
            cleanupJob = scope.launch {
                cleanupStalePeers()
            }

            Timber.i("UDP discovery started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start UDP discovery")
            stop()
            throw e
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRunning) {
            return@withContext
        }

        Timber.i("Stopping UDP discovery")
        isRunning = false

        broadcastJob?.cancel()
        listenerJob?.cancel()
        cleanupJob?.cancel()

        socket?.close()
        socket = null

        releaseMulticastLock()

        peers.clear()
        _discoveredPeers.value = emptyMap()

        Timber.i("UDP discovery stopped")
    }

    /**
     * Broadcast this node's presence
     */
    private suspend fun broadcastPresence() {
        while (isRunning) {
            try {
                // Send JSON format to be compatible with Python nodes
                val discoveryMsg = DiscoveryMessage(
                    node_id = nodeId,
                    grpc_port = grpcPort
                )
                val jsonMessage = json.encodeToString(DiscoveryMessage.serializer(), discoveryMsg)
                val data = jsonMessage.toByteArray()

                Timber.d("Broadcasting JSON: $jsonMessage")

                // Get broadcast addresses
                val broadcastAddresses = getBroadcastAddresses()

                for (address in broadcastAddresses) {
                    try {
                        val packet = DatagramPacket(
                            data,
                            data.size,
                            address,
                            broadcastPort
                        )
                        socket?.send(packet)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to send broadcast to $address")
                    }
                }

                Timber.v("Broadcast presence: $nodeId:$grpcPort to ${broadcastAddresses.size} addresses")

                delay(BROADCAST_INTERVAL_MS)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Timber.e(e, "Error in broadcast loop")
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Listen for peer broadcasts
     */
    private suspend fun listenForPeers() {
        val buffer = ByteArray(1024)

        while (isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val message = String(packet.data, 0, packet.length)
                val peerAddress = packet.address.hostAddress ?: continue

                processPeerMessage(message, peerAddress)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Timber.w(e, "Error receiving UDP packet")
                    delay(100)
                }
            }
        }
    }

    /**
     * Process received peer message
     * Supports both formats:
     * 1. Simple: "nodeId:port"
     * 2. JSON: {"type": "discovery", "node_id": "...", "grpc_port": 12345}
     */
    private fun processPeerMessage(message: String, address: String) {
        try {
            val (peerId, peerPort) = if (message.startsWith("{")) {
                // JSON format from Python nodes
                try {
                    val discoveryMsg = json.decodeFromString<DiscoveryMessage>(message)
                    Pair(discoveryMsg.node_id, discoveryMsg.grpc_port)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse JSON discovery message: $message")
                    return
                }
            } else {
                // Simple format: "nodeId:port"
                val parts = message.split(":")
                if (parts.size != 2) {
                    Timber.w("Invalid peer message format: $message")
                    return
                }
                val port = parts[1].toIntOrNull()
                if (port == null) {
                    Timber.w("Invalid port in peer message: $message")
                    return
                }
                Pair(parts[0], port)
            }

            // Ignore our own broadcasts
            if (peerId == nodeId) {
                return
            }

            val peerInfo = PeerInfo(
                id = peerId,
                address = address,
                port = peerPort,
                lastSeen = System.currentTimeMillis()
            )

            val isNew = !peers.containsKey(peerId)
            peers[peerId] = peerInfo
            _discoveredPeers.value = peers.toMap()

            if (isNew) {
                Timber.i("Discovered new peer: $peerId at $address:$peerPort")
            } else {
                Timber.v("Updated peer: $peerId")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error processing peer message: $message")
        }
    }

    /**
     * Remove peers that haven't been seen recently
     */
    private suspend fun cleanupStalePeers() {
        while (isRunning) {
            try {
                val now = System.currentTimeMillis()
                val stale = peers.filter { (_, info) ->
                    now - info.lastSeen > PEER_TIMEOUT_MS
                }

                if (stale.isNotEmpty()) {
                    stale.keys.forEach { peerId ->
                        peers.remove(peerId)
                        Timber.i("Removed stale peer: $peerId")
                    }
                    _discoveredPeers.value = peers.toMap()
                }

                delay(CLEANUP_INTERVAL_MS)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Timber.e(e, "Error in cleanup loop")
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }

    /**
     * Get all broadcast addresses for active network interfaces
     */
    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        try {
            // Add general broadcast address
            addresses.add(InetAddress.getByName("255.255.255.255"))

            // Add subnet broadcast addresses
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (iface.isUp && !iface.isLoopback) {
                    iface.interfaceAddresses.forEach { addr ->
                        addr.broadcast?.let { broadcast ->
                            addresses.add(broadcast)
                            Timber.v("Found broadcast address: $broadcast on ${iface.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error getting broadcast addresses")
        }

        return addresses
    }

    /**
     * Acquire WiFi multicast lock
     */
    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifiManager.createMulticastLock("exo_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }

            Timber.d("Acquired multicast lock")
        } catch (e: Exception) {
            Timber.w(e, "Failed to acquire multicast lock")
        }
    }

    /**
     * Release WiFi multicast lock
     */
    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Timber.d("Released multicast lock")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            Timber.w(e, "Error releasing multicast lock")
        }
    }
}

/**
 * Information about a discovered peer
 */
data class PeerInfo(
    val id: String,
    val address: String,
    val port: Int,
    val lastSeen: Long
)

/**
 * Interface for peer discovery mechanisms
 */
interface Discovery {
    val discoveredPeers: StateFlow<Map<String, PeerInfo>>

    suspend fun start()
    suspend fun stop()
}
