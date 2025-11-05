# Android Node Architecture for EXO Cluster
## Full Compute Contribution Implementation

---

## Executive Summary

This document outlines the architecture for a **full Android node** that can contribute compute power to an EXO distributed inference cluster. Unlike a simple HTTP client, this node will:

- Run a gRPC server to receive inference requests
- Execute model shards using on-device GPU/NPU
- Forward tensors through the ring topology
- Manage model weights and memory efficiently
- Operate as a background service for 24/7 availability

---

## 1. SYSTEM ARCHITECTURE OVERVIEW

### 1.1 Android Node Components

```
┌─────────────────────────────────────────────────────┐
│           ANDROID NODE APPLICATION                   │
├─────────────────────────────────────────────────────┤
│                                                       │
│  ┌──────────────────────────────────────────────┐  │
│  │   UI Layer (Jetpack Compose)                  │  │
│  │   - Node status dashboard                     │  │
│  │   - Topology visualization                    │  │
│  │   - Settings & configuration                  │  │
│  │   - Battery/thermal monitoring                │  │
│  └──────────────────────────────────────────────┘  │
│                        │                             │
│                        ↓                             │
│  ┌──────────────────────────────────────────────┐  │
│  │   Node Service (Foreground Service)           │  │
│  │   - gRPC Server (receive inference requests)  │  │
│  │   - gRPC Client (forward to next peer)        │  │
│  │   - Discovery mechanism (UDP broadcast)       │  │
│  │   - Lifecycle management                      │  │
│  └──────────────────────────────────────────────┘  │
│           │                    │                     │
│           ↓                    ↓                     │
│  ┌─────────────────┐  ┌──────────────────────────┐ │
│  │ Inference Engine │  │  Networking Layer         │ │
│  │ (TFLite/ONNX)   │  │  - Peer management        │ │
│  │ - GPU Delegate  │  │  - Topology collection    │ │
│  │ - NNAPI Support │  │  - Request routing        │ │
│  │ - Model loading │  │  - Tensor serialization   │ │
│  └─────────────────┘  └──────────────────────────┘ │
│           │                                          │
│           ↓                                          │
│  ┌──────────────────────────────────────────────┐  │
│  │   Storage & Model Management                  │  │
│  │   - Model shard cache (NN API cache)          │  │
│  │   - Configuration (SharedPreferences/Room)    │  │
│  │   - Device capabilities detection             │  │
│  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### 1.2 Integration with EXO Cluster

```
┌──────────────┐       gRPC        ┌──────────────┐
│  Python Node │ ←────────────────→ │ Android Node │
│  (Desktop)   │   Tensor Forward   │  (Phone)     │
└──────────────┘                    └──────────────┘
       ↑                                    ↑
       │                                    │
       └──────────── Ring Topology ────────┘
                    UDP Discovery
```

---

## 2. CORE INTERFACES TO IMPLEMENT

### 2.1 InferenceEngine (Android Implementation)

Based on `/home/user/cg-exo/exo/inference/inference_engine.py`, we need:

```kotlin
interface InferenceEngine {
    /**
     * Encode text prompt to token IDs
     * @param shard The model shard assignment
     * @param prompt The text input
     * @return Numpy array of token IDs
     */
    suspend fun encode(shard: Shard, prompt: String): FloatArray

    /**
     * Sample next token from logits
     * @param x The input logits
     * @return Sampled token ID
     */
    suspend fun sample(x: FloatArray): FloatArray

    /**
     * Decode token IDs to text
     * @param shard The model shard
     * @param tokens Token IDs to decode
     * @return Decoded text string
     */
    suspend fun decode(shard: Shard, tokens: FloatArray): String

    /**
     * Run inference on tensor data
     * @param requestId Unique request identifier
     * @param shard Model layers this node handles
     * @param inputData Input tensor
     * @param inferenceState Optional cached state (KV cache)
     * @return Output tensor and updated state
     */
    suspend fun inferTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray,
        inferenceState: InferenceState? = null
    ): Pair<FloatArray, InferenceState?>

    /**
     * Load model weights for a shard
     * @param shard The shard to load
     * @param path Local file path to weights
     */
    suspend fun loadCheckpoint(shard: Shard, path: String)

    /**
     * Inference on text prompt (convenience method)
     */
    suspend fun inferPrompt(
        requestId: String,
        shard: Shard,
        prompt: String,
        inferenceState: InferenceState? = null
    ): Pair<FloatArray, InferenceState?>
}
```

### 2.2 Device Capabilities (Android)

Based on `/home/user/cg-exo/exo/topology/device_capabilities.py:25-39`:

```kotlin
data class DeviceFlops(
    val fp32: Float,  // TFLOPS
    val fp16: Float,
    val int8: Float
)

data class DeviceCapabilities(
    val model: String,          // e.g., "Pixel 8 Pro"
    val chip: String,           // e.g., "Qualcomm Snapdragon 8 Gen 3"
    val memory: Int,            // MB of RAM
    val flops: DeviceFlops
)

// Chip database for Android devices
val ANDROID_CHIP_FLOPS = mapOf(
    // Qualcomm Snapdragon
    "Qualcomm Snapdragon 8 Gen 3" to DeviceFlops(
        fp32 = 3.5f,   // Estimated
        fp16 = 7.0f,
        int8 = 14.0f
    ),
    "Qualcomm Snapdragon 8 Gen 2" to DeviceFlops(
        fp32 = 3.0f,
        fp16 = 6.0f,
        int8 = 12.0f
    ),
    "Qualcomm Snapdragon 888" to DeviceFlops(
        fp32 = 2.5f,
        fp16 = 5.0f,
        int8 = 10.0f
    ),
    // MediaTek Dimensity
    "MediaTek Dimensity 9300" to DeviceFlops(
        fp32 = 2.8f,
        fp16 = 5.6f,
        int8 = 11.2f
    ),
    // Google Tensor
    "Google Tensor G3" to DeviceFlops(
        fp32 = 2.0f,
        fp16 = 4.0f,
        int8 = 8.0f
    )
)

object DeviceCapabilitiesDetector {
    suspend fun detect(): DeviceCapabilities = withContext(Dispatchers.IO) {
        val model = Build.MODEL
        val chipName = getChipName()
        val memory = getTotalMemoryMB()
        val flops = ANDROID_CHIP_FLOPS[chipName] ?: DeviceFlops(0f, 0f, 0f)

        DeviceCapabilities(
            model = model,
            chip = chipName,
            memory = memory,
            flops = flops
        )
    }

    private fun getChipName(): String {
        return Build.HARDWARE // or use /proc/cpuinfo parsing
    }

    private fun getTotalMemoryMB(): Int {
        val activityManager = context.getSystemService<ActivityManager>()
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }
}
```

### 2.3 Shard (Model Layer Assignment)

From `/home/user/cg-exo/exo/inference/shard.py:4-22`:

```kotlin
@kotlinx.serialization.Serializable
data class Shard(
    val modelId: String,
    val startLayer: Int,
    val endLayer: Int,
    val nLayers: Int
) {
    fun isFirstLayer(): Boolean = startLayer == 0
    fun isLastLayer(): Boolean = endLayer == nLayers - 1
    fun getLayerCount(): Int = endLayer - startLayer + 1

    fun overlaps(other: Shard): Boolean {
        return modelId == other.modelId &&
               maxOf(startLayer, other.startLayer) <= minOf(endLayer, other.endLayer)
    }
}
```

---

## 3. INFERENCE ENGINE OPTIONS FOR ANDROID

### Option A: TensorFlow Lite (RECOMMENDED)

**Pros:**
- Mature and well-supported on Android
- GPU Delegate for hardware acceleration
- NNAPI support (uses device NPU/DSP)
- Quantization support (int8, fp16)
- Good documentation and examples

**Cons:**
- Model conversion required (PyTorch/HF → ONNX → TFLite)
- Limited to specific model architectures
- May not support all transformer features

**Implementation:**
```kotlin
class TFLiteInferenceEngine(
    private val context: Context
) : InferenceEngine {
    private val interpreters = mutableMapOf<String, Interpreter>()

    override suspend fun loadCheckpoint(shard: Shard, path: String) {
        val options = Interpreter.Options().apply {
            // Use GPU delegate for acceleration
            addDelegate(GpuDelegate())
            // Or use NNAPI for NPU
            // setUseNNAPI(true)
            setNumThreads(4)
        }

        val model = FileUtil.loadMappedFile(context, path)
        val interpreter = Interpreter(model, options)
        interpreters[shard.modelId] = interpreter
    }

    override suspend fun inferTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray,
        inferenceState: InferenceState?
    ): Pair<FloatArray, InferenceState?> = withContext(Dispatchers.Default) {
        val interpreter = interpreters[shard.modelId]
            ?: throw IllegalStateException("Model not loaded")

        val outputData = FloatArray(inputData.size) // Adjust based on model
        interpreter.run(inputData, outputData)

        outputData to null
    }

    // Tokenization would use HuggingFace tokenizers or custom impl
    override suspend fun encode(shard: Shard, prompt: String): FloatArray {
        // Use HuggingFace tokenizers library for Android
        // or implement lightweight tokenizer
        TODO("Implement tokenization")
    }

    override suspend fun decode(shard: Shard, tokens: FloatArray): String {
        TODO("Implement detokenization")
    }

    override suspend fun sample(x: FloatArray): FloatArray {
        // Argmax or temperature-based sampling
        val maxIndex = x.indices.maxByOrNull { x[it] } ?: 0
        return floatArrayOf(maxIndex.toFloat())
    }
}
```

### Option B: ONNX Runtime

**Pros:**
- Direct conversion from PyTorch/HuggingFace
- Cross-platform compatibility
- Good performance
- Supports more model architectures

**Cons:**
- Larger binary size
- Less Android-specific optimization

**Dependencies:**
```gradle
dependencies {
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.16.0'
}
```

### Option C: PyTorch Mobile

**Pros:**
- Native PyTorch support
- Direct model export
- Good for research models

**Cons:**
- Larger app size (50MB+)
- Heavier runtime overhead

---

## 4. gRPC IMPLEMENTATION

### 4.1 Generate Kotlin Stubs

```bash
# In your Android project
./gradlew generateProto

# Or manually
protoc \
  --java_out=app/src/main/java \
  --grpc-java_out=app/src/main/java \
  --plugin=protoc-gen-grpc-java=path/to/protoc-gen-grpc-java \
  exo/networking/grpc/node_service.proto
```

### 4.2 gRPC Server Implementation

```kotlin
class AndroidNodeService(
    private val node: AndroidNode,
    private val port: Int
) {
    private var server: Server? = null

    fun start() {
        server = ServerBuilder
            .forPort(port)
            .addService(NodeServiceImpl(node))
            .build()
            .start()

        Log.i("AndroidNode", "gRPC server started on port $port")
    }

    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}

class NodeServiceImpl(
    private val node: AndroidNode
) : NodeServiceGrpc.NodeServiceImplBase() {

    override fun sendPrompt(
        request: NodeServiceProto.PromptRequest,
        responseObserver: StreamObserver<NodeServiceProto.Tensor>
    ) {
        GlobalScope.launch {
            try {
                val shard = request.shard.toShard()
                val result = node.processPrompt(
                    requestId = request.requestId,
                    shard = shard,
                    prompt = request.prompt,
                    inferenceState = request.inferenceState.toMap()
                )

                responseObserver.onNext(result.toProto())
                responseObserver.onCompleted()
            } catch (e: Exception) {
                responseObserver.onError(e)
            }
        }
    }

    override fun sendTensor(
        request: NodeServiceProto.TensorRequest,
        responseObserver: StreamObserver<NodeServiceProto.Tensor>
    ) {
        GlobalScope.launch {
            try {
                val shard = request.shard.toShard()
                val inputData = request.tensor.dataList.toFloatArray()

                val result = node.processTensor(
                    requestId = request.requestId,
                    shard = shard,
                    inputData = inputData
                )

                responseObserver.onNext(result.toProto())
                responseObserver.onCompleted()
            } catch (e: Exception) {
                responseObserver.onError(e)
            }
        }
    }

    override fun healthCheck(
        request: NodeServiceProto.HealthCheckRequest,
        responseObserver: StreamObserver<NodeServiceProto.HealthCheckResponse>
    ) {
        val response = NodeServiceProto.HealthCheckResponse.newBuilder()
            .setStatus("ok")
            .build()
        responseObserver.onNext(response)
        responseObserver.onCompleted()
    }
}
```

### 4.3 gRPC Client (Peer Communication)

```kotlin
class GrpcPeerHandle(
    private val peerId: String,
    private val address: String,
    private val port: Int
) : PeerHandle {
    private val channel = ManagedChannelBuilder
        .forAddress(address, port)
        .usePlaintext()
        .build()

    private val stub = NodeServiceGrpc.newBlockingStub(channel)

    override suspend fun sendPrompt(
        shard: Shard,
        prompt: String,
        requestId: String
    ): FloatArray = withContext(Dispatchers.IO) {
        val request = NodeServiceProto.PromptRequest.newBuilder()
            .setShard(shard.toProto())
            .setPrompt(prompt)
            .setRequestId(requestId)
            .build()

        val response = stub.sendPrompt(request)
        response.dataList.toFloatArray()
    }

    override suspend fun sendTensor(
        shard: Shard,
        tensor: FloatArray,
        requestId: String
    ): FloatArray = withContext(Dispatchers.IO) {
        val tensorProto = NodeServiceProto.Tensor.newBuilder()
            .addAllData(tensor.toList())
            .build()

        val request = NodeServiceProto.TensorRequest.newBuilder()
            .setShard(shard.toProto())
            .setTensor(tensorProto)
            .setRequestId(requestId)
            .build()

        val response = stub.sendTensor(request)
        response.dataList.toFloatArray()
    }

    fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}
```

---

## 5. NODE DISCOVERY (UDP Broadcast)

```kotlin
class UdpDiscovery(
    private val nodeId: String,
    private val port: Int,
    private val onPeerDiscovered: (String, String, Int) -> Unit
) {
    private val broadcastPort = 5678
    private var socket: DatagramSocket? = null
    private var isRunning = false

    suspend fun start() = withContext(Dispatchers.IO) {
        isRunning = true
        socket = DatagramSocket(broadcastPort)
        socket?.broadcast = true

        // Start broadcaster
        launch { broadcastPresence() }

        // Start listener
        launch { listenForPeers() }
    }

    private suspend fun broadcastPresence() {
        val message = "$nodeId:$port".toByteArray()
        val packet = DatagramPacket(
            message,
            message.size,
            InetAddress.getByName("255.255.255.255"),
            broadcastPort
        )

        while (isRunning) {
            socket?.send(packet)
            delay(2500) // Broadcast every 2.5 seconds
        }
    }

    private suspend fun listenForPeers() {
        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning) {
            socket?.receive(packet)
            val message = String(packet.data, 0, packet.length)
            val parts = message.split(":")

            if (parts.size == 2 && parts[0] != nodeId) {
                val peerId = parts[0]
                val peerPort = parts[1].toIntOrNull() ?: continue
                val peerAddress = packet.address.hostAddress ?: continue

                onPeerDiscovered(peerId, peerAddress, peerPort)
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
    }
}
```

---

## 6. ANDROID NODE CORE

```kotlin
class AndroidNode(
    private val context: Context,
    private val nodeId: String,
    private val inferenceEngine: InferenceEngine,
    private val grpcPort: Int
) {
    private val peers = mutableMapOf<String, GrpcPeerHandle>()
    private var topology: Topology = Topology()
    private lateinit var deviceCapabilities: DeviceCapabilities
    private lateinit var grpcServer: AndroidNodeService
    private lateinit var discovery: UdpDiscovery

    suspend fun start() {
        // Detect device capabilities
        deviceCapabilities = DeviceCapabilitiesDetector.detect()

        // Start gRPC server
        grpcServer = AndroidNodeService(this, grpcPort)
        grpcServer.start()

        // Start discovery
        discovery = UdpDiscovery(nodeId, grpcPort) { peerId, address, port ->
            addPeer(peerId, address, port)
        }
        discovery.start()

        // Collect topology
        collectTopology()

        // Periodic topology updates
        GlobalScope.launch {
            while (true) {
                delay(2000)
                collectTopology()
            }
        }
    }

    private fun addPeer(peerId: String, address: String, port: Int) {
        if (!peers.containsKey(peerId)) {
            peers[peerId] = GrpcPeerHandle(peerId, address, port)
            Log.i("AndroidNode", "Discovered peer: $peerId at $address:$port")
        }
    }

    suspend fun processPrompt(
        requestId: String,
        shard: Shard,
        prompt: String,
        inferenceState: Map<String, Any>?
    ): FloatArray {
        // Check if we handle first layer
        if (shard.isFirstLayer()) {
            // Run inference locally
            val (output, _) = inferenceEngine.inferPrompt(
                requestId, shard, prompt, null
            )

            // Forward to next layer if not last
            return if (!shard.isLastLayer()) {
                forwardToNextPeer(requestId, shard, output)
            } else {
                output
            }
        } else {
            // Forward to previous peer to start the chain
            val prevPeer = findPeerForShard(shard.copy(startLayer = 0))
            return prevPeer.sendPrompt(shard, prompt, requestId)
        }
    }

    suspend fun processTensor(
        requestId: String,
        shard: Shard,
        inputData: FloatArray
    ): FloatArray {
        // Run inference on this shard
        val (output, _) = inferenceEngine.inferTensor(
            requestId, shard, inputData, null
        )

        // Forward if not last layer
        return if (!shard.isLastLayer()) {
            forwardToNextPeer(requestId, shard, output)
        } else {
            output
        }
    }

    private suspend fun forwardToNextPeer(
        requestId: String,
        shard: Shard,
        tensor: FloatArray
    ): FloatArray {
        val nextShard = shard.copy(
            startLayer = shard.endLayer + 1,
            endLayer = minOf(shard.endLayer + shard.getLayerCount(), shard.nLayers - 1)
        )
        val nextPeer = findPeerForShard(nextShard)
        return nextPeer.sendTensor(nextShard, tensor, requestId)
    }

    private fun findPeerForShard(shard: Shard): GrpcPeerHandle {
        // Simplified: would use topology and partitioning strategy
        return peers.values.first()
    }

    private suspend fun collectTopology() {
        // Collect topology from all peers
        // Simplified implementation
        topology = Topology(
            nodes = mapOf(nodeId to deviceCapabilities) +
                    peers.mapValues { DeviceCapabilities("", "", 0, DeviceFlops(0f, 0f, 0f)) }
        )
    }

    fun stop() {
        grpcServer.stop()
        discovery.stop()
        peers.values.forEach { it.close() }
    }
}
```

---

## 7. ANDROID FOREGROUND SERVICE

```kotlin
class ExoNodeService : Service() {
    private lateinit var node: AndroidNode
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ExoNodeService = this@ExoNodeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Initialize node
        val nodeId = "android_${Build.MODEL}_${UUID.randomUUID()}"
        val inferenceEngine = TFLiteInferenceEngine(this)
        node = AndroidNode(
            context = this,
            nodeId = nodeId,
            inferenceEngine = inferenceEngine,
            grpcPort = findAvailablePort()
        )

        // Start node
        GlobalScope.launch {
            node.start()
        }
    }

    override fun onDestroy() {
        node.stop()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EXO Node Running")
            .setContentText("Contributing compute power to cluster")
            .setSmallIcon(R.drawable.ic_node)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EXO Node Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "exo_node_service"
        private const val NOTIFICATION_ID = 1
    }
}
```

---

## 8. DEPENDENCIES (build.gradle)

```gradle
dependencies {
    // Kotlin
    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.9.20"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
    implementation "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0"

    // AndroidX
    implementation "androidx.core:core-ktx:1.12.0"
    implementation "androidx.lifecycle:lifecycle-service:2.6.2"

    // gRPC
    implementation "io.grpc:grpc-okhttp:1.70.0"
    implementation "io.grpc:grpc-protobuf-lite:1.70.0"
    implementation "io.grpc:grpc-stub:1.70.0"

    // TensorFlow Lite
    implementation "org.tensorflow:tensorflow-lite:2.14.0"
    implementation "org.tensorflow:tensorflow-lite-gpu:2.14.0"
    implementation "org.tensorflow:tensorflow-lite-support:0.4.4"

    // OR ONNX Runtime
    // implementation "com.microsoft.onnxruntime:onnxruntime-android:1.16.0"

    // Networking
    implementation "com.squareup.okhttp3:okhttp:4.12.0"

    // UI (Compose)
    implementation "androidx.compose.ui:ui:1.5.4"
    implementation "androidx.compose.material3:material3:1.1.2"
    implementation "androidx.activity:activity-compose:1.8.1"
}
```

---

## 9. IMPLEMENTATION PHASES

### Phase 1: Foundation (2-3 weeks)
- [x] Generate gRPC stubs from proto
- [ ] Implement Shard, DeviceCapabilities data classes
- [ ] Device capabilities detection for Android
- [ ] Basic TFLite inference engine (load model, run inference)
- [ ] Simple tokenizer implementation or integration

### Phase 2: Networking (2-3 weeks)
- [ ] gRPC server implementation
- [ ] gRPC client (peer handle)
- [ ] UDP discovery mechanism
- [ ] Peer management and topology collection
- [ ] Tensor serialization/deserialization

### Phase 3: Node Orchestration (2-3 weeks)
- [ ] AndroidNode core implementation
- [ ] Request routing logic
- [ ] Tensor forwarding through ring
- [ ] Shard assignment based on topology
- [ ] Integration testing with Python nodes

### Phase 4: Android Integration (2 weeks)
- [ ] Foreground service implementation
- [ ] Battery optimization (Doze mode exemption)
- [ ] Wake locks for continuous operation
- [ ] Network connectivity monitoring
- [ ] Storage management for models

### Phase 5: UI & Monitoring (1-2 weeks)
- [ ] Jetpack Compose dashboard
- [ ] Real-time status display
- [ ] Topology visualization
- [ ] Settings and configuration
- [ ] Battery/thermal monitoring

### Phase 6: Testing & Optimization (2-3 weeks)
- [ ] Integration testing with full cluster
- [ ] Performance profiling
- [ ] Memory optimization
- [ ] Battery life optimization
- [ ] Model quantization testing

**Total Estimated Time: 11-16 weeks (3-4 months)**

---

## 10. CHALLENGES & MITIGATIONS

### Challenge 1: Model Conversion
**Issue:** Convert PyTorch/HuggingFace models to TFLite
**Mitigation:**
- Use ONNX as intermediate format
- Implement custom operators if needed
- Consider ONNX Runtime instead of TFLite

### Challenge 2: Limited Memory
**Issue:** Mobile devices have less RAM than desktops
**Mitigation:**
- Aggressive quantization (int8)
- Handle smaller shards
- Memory-mapped model loading
- Unload unused models

### Challenge 3: Battery Drain
**Issue:** Continuous inference consumes battery
**Mitigation:**
- Battery threshold settings (only run when >50%)
- Throttling based on temperature
- Power-efficient GPU delegates
- User-configurable duty cycle

### Challenge 4: Network Reliability
**Issue:** Mobile networks are less stable
**Mitigation:**
- Retry logic with exponential backoff
- Request timeout handling
- Graceful peer disconnection
- WiFi-only mode option

### Challenge 5: Background Execution
**Issue:** Android kills background services
**Mitigation:**
- Foreground service with notification
- Battery optimization exemption
- Partial wake locks
- WorkManager for periodic tasks

---

## 11. TESTING STRATEGY

### Unit Tests
```kotlin
@Test
fun testShardOverlap() {
    val shard1 = Shard("llama-3b", 0, 10, 32)
    val shard2 = Shard("llama-3b", 5, 15, 32)
    assertTrue(shard1.overlaps(shard2))
}

@Test
fun testDeviceCapabilitiesDetection() = runBlocking {
    val caps = DeviceCapabilitiesDetector.detect()
    assertNotNull(caps.chip)
    assertTrue(caps.memory > 0)
}
```

### Integration Tests
```kotlin
@Test
fun testGrpcCommunication() = runBlocking {
    // Start server
    val server = AndroidNodeService(mockNode, 50051)
    server.start()

    // Create client
    val peer = GrpcPeerHandle("test", "localhost", 50051)

    // Send request
    val shard = Shard("llama-3b", 0, 10, 32)
    val result = peer.sendPrompt(shard, "Hello", "test-123")

    assertNotNull(result)
    server.stop()
}
```

### Device Testing
- Test on multiple devices (Pixel, Samsung, OnePlus)
- Test on different Android versions (11+)
- Test with different network conditions
- Long-duration battery tests (24h+)
- Thermal stress testing

---

## 12. NEXT STEPS

1. **Set up Android project structure**
2. **Copy node_service.proto and generate Kotlin stubs**
3. **Implement core data classes (Shard, DeviceCapabilities)**
4. **Build TFLite inference engine wrapper**
5. **Test basic inference on device**
6. **Implement gRPC server/client**
7. **Test communication with Python node**
8. **Build discovery mechanism**
9. **Integrate into Android service**
10. **Build UI for monitoring**

**Ready to start implementation?** Let's begin with Phase 1!
