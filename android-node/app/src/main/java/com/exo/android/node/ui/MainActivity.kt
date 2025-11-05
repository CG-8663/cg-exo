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
                        getContributionMetrics = { nodeService?.getContributionMetrics() }
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
    getContributionMetrics: () -> com.exo.android.node.data.ContributionMetrics?
) {
    var nodeStatus by remember { mutableStateOf<NodeStatus>(NodeStatus.Stopped) }
    var contributionMetrics by remember { mutableStateOf<com.exo.android.node.data.ContributionMetrics?>(null) }

    // Observe node status
    LaunchedEffect(Unit) {
        while (true) {
            getNodeStatus()?.let { flow ->
                flow.collect { status ->
                    nodeStatus = status
                }
            }
            kotlinx.coroutines.delay(1000)
            contributionMetrics = getContributionMetrics()
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
            contributionMetrics?.let { metrics ->
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

                        MetricRow("Total Requests", metrics.totalInferenceRequests.toString())
                        MetricRow("Tokens Processed", metrics.totalTokensProcessed.toString())
                        MetricRow("Compute Time", "${metrics.totalComputeTimeMs / 1000}s")
                        MetricRow("Contribution Score", "%.2f".format(metrics.calculateContributionScore()))
                        MetricRow("Failed Requests", metrics.failedRequests.toString())
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
