# EXO Android Node

**Contribute your Android device's compute power to distributed AI inference clusters**

---

## Overview

This Android application allows your phone or tablet to join an EXO distributed inference cluster as a full contributing node. Your device will:

- **Run AI model inference** using on-device GPU/NPU acceleration
- **Communicate via gRPC** with other nodes (Python/Android)
- **Handle model shards** (specific layers of large language models)
- **Forward tensors** through the ring topology
- **Operate 24/7** as a background service (optional)

## Project Status

**Phase 1 Complete ✅**
- [x] Project structure created
- [x] gRPC protocol buffers configured
- [x] Core data classes implemented (Shard, DeviceCapabilities)
- [x] InferenceEngine interface defined
- [x] TFLite inference engine implemented
- [x] Dummy inference engine for testing

**Phase 2 - In Progress 🚧**
- [ ] gRPC server implementation
- [ ] gRPC client (peer communication)
- [ ] UDP discovery mechanism
- [ ] Topology collection

**Phase 3 - TODO 📋**
- [ ] AndroidNode core orchestration
- [ ] Foreground service
- [ ] Jetpack Compose UI
- [ ] Battery/thermal management
- [ ] Integration testing with Python nodes

---

## Architecture

```
┌─────────────────────────────────────┐
│      Android Device                  │
│  ┌────────────────────────────────┐ │
│  │  Compose UI Dashboard           │ │
│  └────────────────────────────────┘ │
│  ┌────────────────────────────────┐ │
│  │  Foreground Service             │ │
│  │  - gRPC Server (port 50051)     │ │
│  │  - gRPC Client (peer comm)      │ │
│  │  - UDP Discovery                │ │
│  └────────────────────────────────┘ │
│  ┌────────────────────────────────┐ │
│  │  TFLite Inference Engine        │ │
│  │  - GPU Delegate enabled         │ │
│  │  - Model shard loading          │ │
│  │  - Tokenization                 │ │
│  └────────────────────────────────┘ │
└─────────────────────────────────────┘
            ↕ gRPC
┌─────────────────────────────────────┐
│   EXO Cluster (Python Nodes)        │
│   - Desktop/Laptop nodes             │
│   - Ring topology                    │
│   - Model partitioning               │
└─────────────────────────────────────┘
```

---

## Key Components

### Data Classes (`com.exo.android.node.data`)

- **`Shard`**: Represents a partition of model layers
  - `modelId`: Model identifier (e.g., "llama-3.2-3b")
  - `startLayer`, `endLayer`: Layer range this node handles
  - `nLayers`: Total layers in the model

- **`DeviceCapabilities`**: Device hardware specifications
  - `model`: Device name (e.g., "Pixel 8 Pro")
  - `chip`: SoC name (e.g., "Qualcomm Snapdragon 8 Gen 3")
  - `memory`: Total RAM in MB
  - `flops`: Compute power (FP32, FP16, INT8 TFLOPS)

### Inference Engine (`com.exo.android.node.inference`)

- **`InferenceEngine`** (interface): Core abstraction
  - `encode()`: Text → tokens
  - `decode()`: Tokens → text
  - `inferTensor()`: Run model inference on tensor
  - `sample()`: Sample next token from logits
  - `loadCheckpoint()`: Load model weights

- **`TFLiteInferenceEngine`**: Production implementation
  - Uses TensorFlow Lite with GPU delegate
  - Hardware acceleration via GPU/NPU
  - Supports quantized models (INT8)

- **`DummyInferenceEngine`**: Testing implementation
  - No real inference, just pass-through
  - Useful for network/protocol testing

---

## Building the Project

### Prerequisites

1. **Android Studio** (Hedgehog 2023.1.1+)
2. **JDK 17**
3. **Android SDK 34**
4. **Gradle 8.2+**

### Build Steps

```bash
cd android-node

# Sync Gradle dependencies
./gradlew build

# Generate gRPC stubs from proto
./gradlew generateProto

# Run tests
./gradlew test

# Build APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug
```

---

## Project Structure

```
android-node/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/exo/android/node/
│   │   │   │   ├── data/
│   │   │   │   │   ├── Shard.kt
│   │   │   │   │   └── DeviceCapabilities.kt
│   │   │   │   ├── inference/
│   │   │   │   │   ├── InferenceEngine.kt
│   │   │   │   │   ├── TFLiteInferenceEngine.kt
│   │   │   │   │   └── DummyInferenceEngine.kt
│   │   │   │   ├── networking/      # TODO: gRPC server/client
│   │   │   │   ├── service/         # TODO: Foreground service
│   │   │   │   └── ui/              # TODO: Compose UI
│   │   │   └── proto/
│   │   │       └── node_service.proto  # gRPC protocol definition
│   │   └── test/                   # Unit tests
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Dependencies

### Core
- **Kotlin** 1.9.20 + Coroutines
- **AndroidX** Core KTX, Lifecycle

### Networking
- **gRPC** 1.70.0 (OkHttp, Protobuf Lite, Stub)
- **Protocol Buffers** 3.24.0

### Inference
- **TensorFlow Lite** 2.14.0
- **TFLite GPU Delegate** 2.14.0

### UI
- **Jetpack Compose** (Material 3)
- **Activity Compose** 1.8.1

### Testing
- **JUnit** 4.13.2
- **Coroutines Test** 1.7.3

---

## Usage (When Complete)

### 1. Install the App

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Start the Node

1. Open the EXO Android Node app
2. Grant necessary permissions (network, storage)
3. Configure node settings:
   - Node ID (auto-generated)
   - gRPC port (default: 50051)
   - Discovery mode (UDP/Manual)
4. Tap "Start Node"

### 3. Join a Cluster

The app will automatically discover nearby Python nodes via UDP broadcast. Or manually configure peer addresses.

### 4. Monitor Status

The dashboard shows:
- Connected peers
- Current shard assignment
- Inference requests processed
- Battery/thermal status
- Network bandwidth

---

## Testing with Dummy Engine

For testing without real models:

```kotlin
// In your test or debug build
val engine = InferenceEngineFactory.create(
    InferenceEngineFactory.EngineType.DUMMY,
    context
)

// Test inference
val shard = Shard("test-model", 0, 10, 32)
val prompt = "Hello, world!"

val tokens = engine.encode(shard, prompt)
val (output, state) = engine.inferTensor("req-123", shard, tokens, null)
val text = engine.decode(shard, output)
```

---

## Device Requirements

### Minimum
- Android 8.0 (API 26)
- 3GB RAM
- WiFi connectivity
- 2GB free storage

### Recommended
- Android 12+ (API 31+)
- 6GB+ RAM
- Modern SoC (Snapdragon 8 Gen 2+, Tensor G3, Dimensity 9000+)
- WiFi 5/6
- 10GB+ free storage (for model weights)

### Supported SoCs

The app detects and optimizes for:
- Qualcomm Snapdragon (8 Gen 3/2/1, 888, 870, 865)
- MediaTek Dimensity (9300, 9200, 9000)
- Google Tensor (G3, G2)
- Samsung Exynos (2400, 2200)

---

## Performance Expectations

Typical inference performance on modern Android devices:

| Device | Chip | Inference Speed* |
|--------|------|-----------------|
| Pixel 8 Pro | Tensor G3 | ~15 tokens/sec |
| Samsung S24 Ultra | Snapdragon 8 Gen 3 | ~20 tokens/sec |
| OnePlus 12 | Snapdragon 8 Gen 3 | ~20 tokens/sec |
| Pixel 7 Pro | Tensor G2 | ~12 tokens/sec |

*Estimated for Llama 3.2 3B quantized (INT8)

---

## Battery Management

The app includes battery optimization features:

- **Battery Threshold**: Only run when battery > X%
- **Thermal Throttling**: Reduce load if device overheats
- **WiFi-Only Mode**: Disable on mobile data
- **Doze Mode Handling**: Request exemption for 24/7 operation
- **Wake Locks**: Keep CPU awake during inference

---

## Security Considerations

- **Local Network Only**: Default UDP discovery only works on LAN
- **No Authentication**: MVP does not include auth (add before production)
- **Model Trust**: Only load models from trusted sources
- **Network Encryption**: Consider TLS for gRPC in production

---

## Troubleshooting

### gRPC Connection Failed
- Check firewall settings
- Verify peer IP/port
- Ensure devices on same network

### Model Loading Failed
- Check available storage
- Verify model format (TFLite required)
- Check file permissions

### GPU Delegate Error
- Fallback to CPU is automatic
- Check device GPU compatibility
- Update TFLite library

### High Battery Drain
- Reduce duty cycle
- Enable thermal throttling
- Use battery threshold > 50%

---

## Development Roadmap

### v0.1.0 - MVP (Current)
- [x] Core data structures
- [x] Inference engine interface
- [x] TFLite implementation
- [x] Dummy engine for testing

### v0.2.0 - Networking
- [ ] gRPC server
- [ ] gRPC client
- [ ] UDP discovery
- [ ] Peer management

### v0.3.0 - Orchestration
- [ ] AndroidNode core
- [ ] Request routing
- [ ] Tensor forwarding
- [ ] Shard assignment

### v0.4.0 - Service
- [ ] Foreground service
- [ ] Background execution
- [ ] Battery optimization
- [ ] Notification management

### v0.5.0 - UI
- [ ] Jetpack Compose dashboard
- [ ] Real-time monitoring
- [ ] Settings screen
- [ ] Topology visualization

### v1.0.0 - Production
- [ ] Full integration testing
- [ ] Performance optimization
- [ ] Security hardening
- [ ] Documentation complete

---

## Contributing

This is part of the EXO project. See main repository for contribution guidelines.

---

## References

- **Main EXO Project**: `/home/user/cg-exo`
- **Architecture Doc**: `/home/user/cg-exo/ANDROID_NODE_ARCHITECTURE.md`
- **Python Node**: `/home/user/cg-exo/exo/orchestration/node.py`
- **gRPC Proto**: `/home/user/cg-exo/exo/networking/grpc/node_service.proto`

---

## License

Same as main EXO project

---

## Contact

For questions about the Android implementation, see the main EXO project issues.
