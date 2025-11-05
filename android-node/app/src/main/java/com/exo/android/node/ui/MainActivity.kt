package com.exo.android.node.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.exo.android.node.NodeStatus
import com.exo.android.node.service.ExoNodeService
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main activity for EXO Android Node
 * Displays node status and contribution metrics
 */
class MainActivity : ComponentActivity() {

    private var nodeService: ExoNodeService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ExoNodeService.LocalBinder
            nodeService = binder.getService()
            isBound = true
            Timber.d("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nodeService = null
            isBound = false
            Timber.d("Service disconnected")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Timber.d("Notification permission granted")
        } else {
            Timber.w("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            ExoNodeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NodeScreen(
                        onStartNode = { ExoNodeService.start(this) },
                        onStopNode = { ExoNodeService.stop(this) },
                        getNodeStatus = { nodeService?.getNodeStatus() },
                        getContributionMetrics = { nodeService?.getContributionMetrics() },
                        getConnectedPeers = { nodeService?.getConnectedPeersCount() ?: 0 },
                        onConnectToPeer = { ip, port -> nodeService?.connectToPeer(ip, port) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service
        Intent(this, ExoNodeService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun ExoNodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeScreen(
    onStartNode: () -> Unit,
    onStopNode: () -> Unit,
    getNodeStatus: () -> kotlinx.coroutines.flow.StateFlow<NodeStatus>?,
    getContributionMetrics: () -> com.exo.android.node.data.ContributionMetrics?,
    getConnectedPeers: () -> Int = { 0 },
    onConnectToPeer: (String, Int) -> Unit = { _, _ -> }
) {
    var nodeStatus by remember { mutableStateOf<NodeStatus>(NodeStatus.Stopped) }
    var contributionMetrics by remember { mutableStateOf<com.exo.android.node.data.ContributionMetrics?>(null) }
    var peerIpAddress by remember { mutableStateOf("192.168.68.51") }
    var peerPort by remember { mutableStateOf("50051") }
    var connectedPeersCount by remember { mutableStateOf(0) }

    // Observe node status
    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                while (true) {
                    getNodeStatus()?.let { flow ->
                        flow.collect { status ->
                            nodeStatus = status
                        }
                    }
                    delay(100)
                }
            }

            // Update contribution metrics separately
            launch {
                while (true) {
                    contributionMetrics = getContributionMetrics()
                    connectedPeersCount = getConnectedPeers()
                    delay(1000) // Update every second
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EXO Android Node") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Node Status Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Node Status",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = nodeStatus.toString(),
                            style = MaterialTheme.typography.bodyLarge
                        )

                        StatusIndicator(nodeStatus)
                    }
                }
            }

            // Contribution Metrics Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Contribution Metrics",
                        style = MaterialTheme.typography.titleLarge
                    )

                    val metrics = contributionMetrics
                    if (metrics != null) {
                        MetricRow("Total Requests", metrics.totalInferenceRequests.toString())
                        MetricRow("Tokens Processed", metrics.totalTokensProcessed.toString())
                        MetricRow("Compute Time", "${metrics.totalComputeTimeMs / 1000}s")
                        MetricRow("Contribution Score", "%.2f".format(metrics.calculateContributionScore()))
                        MetricRow("Failed Requests", metrics.failedRequests.toString())
                    } else {
                        Text(
                            text = "Waiting for node to start...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Cluster Connection Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Cluster Connection",
                        style = MaterialTheme.typography.titleLarge
                    )

                    MetricRow("Connected Peers", connectedPeersCount.toString())

                    Text(
                        text = "Connect to Peer",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = peerIpAddress,
                        onValueChange = { peerIpAddress = it },
                        label = { Text("Peer IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = peerPort,
                        onValueChange = { peerPort = it },
                        label = { Text("Peer Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            val port = peerPort.toIntOrNull() ?: 50051
                            onConnectToPeer(peerIpAddress, port)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = nodeStatus == NodeStatus.Running && peerIpAddress.isNotBlank()
                    ) {
                        Text("Connect to Peer")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartNode,
                    modifier = Modifier.weight(1f),
                    enabled = nodeStatus == NodeStatus.Stopped
                ) {
                    Text("Start Node")
                }

                Button(
                    onClick = onStopNode,
                    modifier = Modifier.weight(1f),
                    enabled = nodeStatus == NodeStatus.Running
                ) {
                    Text("Stop Node")
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(status: NodeStatus) {
    val color = when (status) {
        is NodeStatus.Running -> MaterialTheme.colorScheme.primary
        is NodeStatus.Stopped -> MaterialTheme.colorScheme.error
        is NodeStatus.Starting, is NodeStatus.Stopping -> MaterialTheme.colorScheme.secondary
        is NodeStatus.Error -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier.size(16.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = color
    ) {}
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
