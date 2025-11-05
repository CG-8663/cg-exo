package com.exo.android.node

import android.content.Context
import com.exo.android.node.data.ContributionTracker
import com.exo.android.node.data.DeviceCapabilities
import com.exo.android.node.data.DeviceCapabilitiesDetector
import com.exo.android.node.data.Shard
import com.exo.android.node.inference.InferenceEngine
import com.exo.android.node.inference.InferenceState
import com.exo.android.node.networking.*
import com.exo.android.node.utils.ProtoConverters
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core Android node implementation for EXO distributed inference
 * Mirrors Python Node from exo/orchestration/node.py
 *
 * This node can:
 * - Join a cluster via UDP discovery
 * - Handle inference requests for assigned model shards
 * - Forward tensors through the ring topology
 * - Track contribution metrics for reward distribution
 */
class AndroidNode(
    private val context: Context,
    val nodeId: String = generateNodeId(),
    private val inferenceEngine: InferenceEngine,
    private val grpcPort: Int = findAvailablePort(),
    private val discoveryPort: Int = 5678
) : NodeRequestHandler {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Networking components
    private lateinit var grpcServer: GrpcServer
    private lateinit var discovery: Discovery
    private val peers = ConcurrentHashMap<String, PeerHandle>()

    // Node state
    private lateinit var deviceCapabilities: DeviceCapabilities
    private var currentShard: Shard? = null
    private val _topology = MutableStateFlow<ProtoConverters.Topology?>(null)
    val topology: StateFlow<ProtoConverters.Topology?> = _topology

    // Contribution tracking for smart contract rewards
    private val contributionTracker = ContributionTracker(nodeId)

    // Request tracking
    private val outstandingRequests = ConcurrentHashMap<String, Long>()

    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _nodeStatus = MutableStateFlow<NodeStatus>(NodeStatus.Stopped)
    val nodeStatus: StateFlow<NodeStatus> = _nodeStatus

    /**
     * Start the node
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            Timber.w("Node already running")
            return@withContext
        }

        Timber.i("Starting Android node: $nodeId")
        _nodeStatus.value = NodeStatus.Starting

        try {
            // Detect device capabilities
            deviceCapabilities = DeviceCapabilitiesDetector.detect(context)
            Timber.i("Device capabilities: $deviceCapabilities")

            // Start gRPC server
            grpcServer = GrpcServer(grpcPort, this@AndroidNode)
            grpcServer.start()

            // Start discovery with device capabilities
            discovery = UdpDiscovery(context, nodeId, grpcPort, discoveryPort, deviceCapabilities)
            discovery.start()

            // Observe discovered peers
            scope.launch {
                discovery.discoveredPeers.collect { peerMap ->
                    updatePeers(peerMap)
                }
            }

            // Start topology collection
            scope.launch {
                periodicTopologyCollection()
            }

            _isRunning.value = true
            _nodeStatus.value = NodeStatus.Running

            Timber.i("Android node started successfully on port $grpcPort")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start node")
            _nodeStatus.value = NodeStatus.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Stop the node
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!_isRunning.value) {
            return@withContext
        }

        Timber.i("Stopping Android node: $nodeId")
        _nodeStatus.value = NodeStatus.Stopping

        try {
            // Stop discovery
            discovery.stop()

            // Stop gRPC server
            grpcServer.stop()

            // Close all peer connections
            peers.values.forEach { it.close() }
            peers.clear()

            _isRunning.value = false
            _nodeStatus.value = NodeStatus.Stopped

            Timber.i("Android node stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping node")
            _nodeStatus.value = NodeStatus.Error(e.message ?: "Unknown error")
        }
    }

    // ========== NodeRequestHandler Implementation ==========

    override suspend fun handlePromptRequest(
        shard: Shard,
        prompt: String,
        requestId: String,
        inferenceState: InferenceState?
    ): FloatArray = withContext(Dispatchers.Default) {
        Timber.d("Handling prompt request: $requestId")
        val startTime = System.currentTimeMillis()

        try {
            outstandingRequests[requestId] = startTime

            // Check if we handle the first layer
            val result = if (shard.isFirstLayer()) {
                // Run inference locally
                Timber.d("Processing prompt locally (first layer)")
                val (output, _) = inferenceEngine.inferPrompt(requestId, shard, prompt, inferenceState)

                // If not last layer, forward to next peer
                if (!shard.isLastLayer()) {
                    forwardToNextLayer(requestId, shard, output)
                } else {
                    output
                }
            } else {
                // Forward to peer that handles first layer
                Timber.d("Forwarding prompt to peer with first layer")
                val firstLayerPeer = findPeerForFirstLayer(shard)
                firstLayerPeer.sendPrompt(shard, prompt, requestId, inferenceState)
            }

            // Record contribution metrics
            val computeTime = System.currentTimeMillis() - startTime
            val tokensProcessed = prompt.split(" ").size // Simple estimate
            val bytesProcessed = prompt.toByteArray().size.toLong()
            contributionTracker.recordPromptRequest(tokensProcessed, computeTime, bytesProcessed)

            Timber.d("Prompt request completed: $requestId in ${computeTime}ms")
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle prompt request: $requestId")
            contributionTracker.recordFailedRequest()
            throw e
        } finally {
            outstandingRequests.remove(requestId)
        }
    }

    override suspend fun handleTensorRequest(
        shard: Shard,
        inputData: FloatArray,
        requestId: String,
        inferenceState: InferenceState?
    ): FloatArray = withContext(Dispatchers.Default) {
        Timber.d("Handling tensor request: $requestId (${inputData.size} floats)")
        val startTime = System.currentTimeMillis()

        try {
            outstandingRequests[requestId] = startTime

            // Run inference on this shard
            val (output, _) = inferenceEngine.inferTensor(requestId, shard, inputData, inferenceState)

            // Forward if not last layer
            val result = if (!shard.isLastLayer()) {
                forwardToNextLayer(requestId, shard, output)
            } else {
                output
            }

            // Record contribution metrics
            val computeTime = System.currentTimeMillis() - startTime
            val bytesProcessed = (inputData.size * 4).toLong()
            contributionTracker.recordTensorRequest(computeTime, bytesProcessed)

            Timber.d("Tensor request completed: $requestId in ${computeTime}ms")
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle tensor request: $requestId")
            contributionTracker.recordFailedRequest()
            throw e
        } finally {
            outstandingRequests.remove(requestId)
        }
    }

    override suspend fun handleTopologyRequest(
        visited: Set<String>,
        maxDepth: Int
    ): ProtoConverters.Topology {
        Timber.d("Handling topology request (depth: $maxDepth)")

        // Don't revisit this node
        if (visited.contains(nodeId)) {
            return ProtoConverters.Topology(emptyMap(), emptyMap())
        }

        val newVisited = visited + nodeId

        // Collect from peers if depth allows
        val peerTopologies = if (maxDepth > 0) {
            peers.values.mapNotNull { peer ->
                try {
                    peer.collectTopology(newVisited, maxDepth - 1)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to collect topology from ${peer.peerId}")
                    null
                }
            }
        } else {
            emptyList()
        }

        // Merge topologies
        val allNodes = mutableMapOf<String, DeviceCapabilities>()
        val allConnections = mutableMapOf<String, MutableList<ProtoConverters.PeerConnection>>()

        // Add this node
        allNodes[nodeId] = deviceCapabilities

        // Add peer connections from this node
        allConnections[nodeId] = peers.values.map {
            ProtoConverters.PeerConnection(it.peerId, "gRPC peer")
        }.toMutableList()

        // Merge peer topologies
        peerTopologies.forEach { topology ->
            allNodes.putAll(topology.nodes)
            topology.peerGraph.forEach { (nodeId, conns) ->
                allConnections.getOrPut(nodeId) { mutableListOf() }.addAll(conns)
            }
        }

        return ProtoConverters.Topology(
            nodes = allNodes,
            peerGraph = allConnections
        ).also {
            _topology.value = it
        }
    }

    override suspend fun handleResultRequest(
        requestId: String,
        result: List<Int>,
        isFinished: Boolean
    ) {
        Timber.d("Received result for request: $requestId (finished: $isFinished)")
        // Could emit to UI or callback system
    }

    override suspend fun handleOpaqueStatus(requestId: String, status: String) {
        Timber.d("Received opaque status for $requestId: $status")
        // Could parse and update node status
    }

    override fun isHealthy(): Boolean {
        return _isRunning.value && deviceCapabilities != DeviceCapabilities.UNKNOWN
    }

    // ========== Private Helper Methods ==========

    /**
     * Update peer connections from discovery
     */
    private fun updatePeers(discoveredPeers: Map<String, PeerInfo>) {
        // Add new peers
        discoveredPeers.forEach { (peerId, info) ->
            if (!peers.containsKey(peerId)) {
                val peerHandle = GrpcPeerHandle(peerId, info.address, info.port)
                peers[peerId] = peerHandle
                Timber.i("Connected to peer: $peerId at ${info.address}:${info.port}")
            }
        }

        // Remove disconnected peers
        val disconnected = peers.keys - discoveredPeers.keys
        disconnected.forEach { peerId ->
            peers.remove(peerId)?.close()
            Timber.i("Disconnected from peer: $peerId")
        }
    }

    /**
     * Forward tensor to next layer in the ring
     */
    private suspend fun forwardToNextLayer(
        requestId: String,
        currentShard: Shard,
        tensor: FloatArray
    ): FloatArray {
        val nextShard = Shard(
            modelId = currentShard.modelId,
            startLayer = currentShard.endLayer + 1,
            endLayer = minOf(
                currentShard.endLayer + currentShard.getLayerCount(),
                currentShard.nLayers - 1
            ),
            nLayers = currentShard.nLayers
        )

        val nextPeer = findPeerForShard(nextShard)
        return nextPeer.sendTensor(nextShard, tensor, requestId)
    }

    /**
     * Find peer that handles the first layer
     */
    private fun findPeerForFirstLayer(shard: Shard): PeerHandle {
        // Simplified: in production, use partitioning strategy
        return peers.values.firstOrNull()
            ?: throw IllegalStateException("No peers available")
    }

    /**
     * Find peer that handles a specific shard
     */
    private fun findPeerForShard(shard: Shard): PeerHandle {
        // Simplified: in production, use topology and partitioning strategy
        // For now, round-robin or random selection
        return peers.values.firstOrNull()
            ?: throw IllegalStateException("No peers available for shard: $shard")
    }

    /**
     * Periodically collect topology from peers
     */
    private suspend fun periodicTopologyCollection() {
        while (_isRunning.value) {
            try {
                delay(5000) // Every 5 seconds
                if (peers.isNotEmpty()) {
                    handleTopologyRequest(emptySet(), maxDepth = 2)
                    Timber.v("Topology updated: ${_topology.value?.nodes?.size ?: 0} nodes")
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Timber.w(e, "Error collecting topology")
            }
        }
    }

    /**
     * Get current contribution metrics
     */
    fun getContributionMetrics() = contributionTracker.getMetrics()

    /**
     * Reset contribution metrics (e.g., after reward distribution)
     */
    fun resetContributionMetrics() = contributionTracker.reset()

    /**
     * Get number of connected peers
     */
    fun getConnectedPeersCount(): Int = peers.size

    /**
     * Manually connect to a specific peer
     */
    suspend fun connectToPeer(ipAddress: String, port: Int) = withContext(Dispatchers.IO) {
        val peerId = "manual_${ipAddress}_${port}"

        if (peers.containsKey(peerId)) {
            Timber.w("Already connected to peer: $peerId")
            return@withContext
        }

        try {
            Timber.i("Manually connecting to peer at $ipAddress:$port")
            val peerHandle = GrpcPeerHandle(peerId, ipAddress, port)

            // Test connection with health check
            if (peerHandle.healthCheck()) {
                peers[peerId] = peerHandle
                Timber.i("Successfully connected to peer: $peerId")

                // Collect topology from new peer
                try {
                    handleTopologyRequest(emptySet(), maxDepth = 2)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to collect topology from new peer")
                }
            } else {
                peerHandle.close()
                throw IllegalStateException("Peer health check failed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to peer at $ipAddress:$port")
            throw e
        }
    }

    companion object {
        private fun generateNodeId(): String {
            val uuid = UUID.randomUUID().toString().take(8)
            val model = android.os.Build.MODEL.replace(" ", "_")
            return "android_${model}_$uuid"
        }

        private fun findAvailablePort(): Int {
            // Try to find an available port starting from 50051
            return 50051 // Simplified for MVP
        }
    }
}

/**
 * Node status
 */
sealed class NodeStatus {
    object Stopped : NodeStatus()
    object Starting : NodeStatus()
    object Running : NodeStatus()
    object Stopping : NodeStatus()
    data class Error(val message: String) : NodeStatus()

    override fun toString(): String = when (this) {
        is Stopped -> "Stopped"
        is Starting -> "Starting"
        is Running -> "Running"
        is Stopping -> "Stopping"
        is Error -> "Error: $message"
    }
}
