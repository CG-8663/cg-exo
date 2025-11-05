package com.exo.android.node.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.exo.android.node.AndroidNode
import com.exo.android.node.NodeStatus
import com.exo.android.node.R
import com.exo.android.node.data.ContributionMetrics
import com.exo.android.node.inference.InferenceEngine
import com.exo.android.node.inference.InferenceEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service for running EXO node 24/7
 * Keeps the node alive even when app is in background
 */
class ExoNodeService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var node: AndroidNode? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): ExoNodeService = this@ExoNodeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("ExoNodeService created")

        // Create notification channel (required for Android 8.0+)
        createNotificationChannel()

        // Start as foreground service
        val notification = createNotification(
            title = "EXO Node Initializing",
            text = "Starting compute node..."
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("ExoNodeService start command received")

        when (intent?.action) {
            ACTION_START_NODE -> startNode()
            ACTION_STOP_NODE -> stopNode()
            ACTION_RESTART_NODE -> restartNode()
        }

        // Restart service if killed by system
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("ExoNodeService destroyed")

        scope.launch {
            stopNode()
        }

        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Start the EXO node
     */
    private fun startNode() {
        if (node != null && node?.isRunning?.value == true) {
            Timber.w("Node already running")
            return
        }

        scope.launch {
            try {
                Timber.i("Starting EXO node")

                // Acquire wake lock to prevent CPU sleep
                acquireWakeLock()

                // Create inference engine
                val engineType = getPreferredEngineType()
                val inferenceEngine = InferenceEngineFactory.create(engineType, this@ExoNodeService)

                // Create and start node
                node = AndroidNode(
                    context = this@ExoNodeService,
                    inferenceEngine = inferenceEngine,
                    grpcPort = getPreferredGrpcPort(),
                    discoveryPort = getPreferredDiscoveryPort()
                )

                node?.start()

                // Update notification
                updateNotification(
                    title = "EXO Node Running",
                    text = "Node ID: ${node?.nodeId} | Port: ${getPreferredGrpcPort()}"
                )

                Timber.i("EXO node started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start node")
                updateNotification(
                    title = "EXO Node Error",
                    text = "Failed to start: ${e.message}"
                )
            }
        }
    }

    /**
     * Stop the EXO node
     */
    private fun stopNode() {
        scope.launch {
            try {
                Timber.i("Stopping EXO node")

                node?.stop()
                node = null

                releaseWakeLock()

                updateNotification(
                    title = "EXO Node Stopped",
                    text = "Node is no longer running"
                )

                Timber.i("EXO node stopped successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping node")
            }
        }
    }

    /**
     * Restart the node
     */
    private fun restartNode() {
        scope.launch {
            stopNode()
            kotlinx.coroutines.delay(1000)
            startNode()
        }
    }

    /**
     * Get node status
     */
    fun getNodeStatus(): StateFlow<NodeStatus>? = node?.nodeStatus

    /**
     * Get node instance
     */
    fun getNode(): AndroidNode? = node

    /**
     * Get contribution metrics
     */
    fun getContributionMetrics(): ContributionMetrics? = node?.getContributionMetrics()

    /**
     * Get connected peers count
     */
    fun getConnectedPeersCount(): Int = node?.getConnectedPeersCount() ?: 0

    /**
     * Connect to a specific peer manually
     */
    fun connectToPeer(ipAddress: String, port: Int) {
        scope.launch {
            try {
                Timber.i("Connecting to peer at $ipAddress:$port")
                node?.connectToPeer(ipAddress, port)
                updateNotification(
                    title = "EXO Node Running",
                    text = "Connected to peer at $ipAddress:$port"
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to peer at $ipAddress:$port")
                updateNotification(
                    title = "EXO Node Running",
                    text = "Failed to connect to peer: ${e.message}"
                )
            }
        }
    }

    /**
     * Create notification channel (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EXO Node Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps EXO node running in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(title: String, text: String): Notification {
        // Intent to open main activity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_node)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Update the notification
     */
    private fun updateNotification(title: String, text: String) {
        val notification = createNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Acquire wake lock to prevent CPU sleep during inference
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ExoNode::WakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Timber.d("Wake lock acquired")
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.d("Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Get preferred engine type from settings
     */
    private fun getPreferredEngineType(): InferenceEngineFactory.EngineType {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val engineName = prefs.getString(PREF_ENGINE_TYPE, "DUMMY")
        return try {
            InferenceEngineFactory.EngineType.valueOf(engineName ?: "DUMMY")
        } catch (e: Exception) {
            InferenceEngineFactory.EngineType.DUMMY
        }
    }

    /**
     * Get preferred gRPC port from settings
     */
    private fun getPreferredGrpcPort(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREF_GRPC_PORT, 50051)
    }

    /**
     * Get preferred discovery port from settings
     */
    private fun getPreferredDiscoveryPort(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREF_DISCOVERY_PORT, 5678)
    }

    companion object {
        private const val CHANNEL_ID = "exo_node_service"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "exo_node_prefs"
        private const val PREF_ENGINE_TYPE = "engine_type"
        private const val PREF_GRPC_PORT = "grpc_port"
        private const val PREF_DISCOVERY_PORT = "discovery_port"

        const val ACTION_START_NODE = "com.exo.android.node.START_NODE"
        const val ACTION_STOP_NODE = "com.exo.android.node.STOP_NODE"
        const val ACTION_RESTART_NODE = "com.exo.android.node.RESTART_NODE"

        /**
         * Start the service
         */
        fun start(context: Context) {
            val intent = Intent(context, ExoNodeService::class.java).apply {
                action = ACTION_START_NODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the service
         */
        fun stop(context: Context) {
            val intent = Intent(context, ExoNodeService::class.java).apply {
                action = ACTION_STOP_NODE
            }
            context.startService(intent)
        }
    }
}
