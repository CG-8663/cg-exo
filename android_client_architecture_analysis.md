# EXO System: Architecture & Client Connection Analysis
## Comprehensive Guide for Android Client Implementation

---

## Executive Summary

The EXO system is a distributed inference framework that allows devices to form clusters for collaborative AI model execution. The architecture is **peer-to-peer** without a master-worker design, using **gRPC** for inter-node communication and **HTTP/REST (aiohttp)** for external client APIs.

**Key Finding**: iOS support has been deprioritized (see README.md), but the architecture is suitable for Android implementation. An Android client can either:
1. Connect via HTTP/REST to the ChatGPT-compatible API endpoint
2. Implement gRPC client stubs to directly communicate with cluster nodes

---

## 1. SYSTEM ARCHITECTURE OVERVIEW

### 1.1 Core Architecture Pattern: P2P with Ring Topology

The system uses **ring-based model partitioning** where:
- Each node holds a portion of model layers (shards)
- Nodes communicate in a ring pattern for inference
- Model is split across devices based on available memory and computational capacity
- No master node - any device can initiate inference

**Key File**: `/home/user/cg-exo/exo/topology/ring_memory_weighted_partitioning_strategy.py`

### 1.2 Four Layer System

```
┌─────────────────────────────────────────────┐
│   Client Layer (HTTP/REST or gRPC)          │
│   - ChatGPT-compatible API (aiohttp)         │
│   - Direct gRPC calls                        │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│   API Layer (ChatGPT API)                    │
│   - HTTP endpoints for chat/completions      │
│   - WebUI support                            │
│   - Model management                         │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│   Orchestration Layer (Node)                 │
│   - Request routing                          │
│   - Tensor forwarding between peers          │
│   - Topology management                      │
│   - Model sharding logic                     │
└─────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────┐
│   Networking Layer                           │
│   - gRPC server/client (inter-node)          │
│   - Discovery (UDP/Tailscale/Manual)         │
│   - PeerHandle abstraction                   │
└─────────────────────────────────────────────┘
```

---

## 2. PROTOCOL ANALYSIS: How Clients Connect

### 2.1 gRPC Protocol (Inter-Node Communication)

**Primary Protocol**: gRPC with Protocol Buffers
- **File**: `/home/user/cg-exo/exo/networking/grpc/node_service.proto`
- **Port**: User-configurable (default varies, commonly 50051+)
- **Compression**: gzip enabled by default
- **Message Size Limits**: 256MB max

#### gRPC Service Definition:

```protobuf
service NodeService {
  rpc SendPrompt(PromptRequest) returns (Tensor) {}
  rpc SendTensor(TensorRequest) returns (Tensor) {}
  rpc SendExample(ExampleRequest) returns (Loss) {}
  rpc CollectTopology(CollectTopologyRequest) returns (Topology) {}
  rpc SendResult(SendResultRequest) returns (Empty) {}
  rpc SendOpaqueStatus(SendOpaqueStatusRequest) returns (Empty) {}
  rpc HealthCheck(HealthCheckRequest) returns (HealthCheckResponse) {}
}
```

#### Key gRPC Messages:

1. **PromptRequest**: Sends text prompt for inference
   - `shard`: Model layer assignment
   - `prompt`: Text input
   - `request_id`: Unique request identifier
   - `inference_state`: Cached state (optional)

2. **TensorRequest**: Sends tensor data through the network
   - `shard`: Layer identification
   - `tensor`: Raw tensor data + metadata (dtype, shape)
   - `request_id`: Request identifier

3. **SendResultRequest**: Final results from inference
   - `request_id`: Associates with original request
   - `result`: Token IDs or output data
   - `is_finished`: Boolean flag for completion

4. **CollectTopologyRequest**: Discovers network topology
   - `visited`: Set of nodes already queried
   - `max_depth`: Recursion limit

**Files**:
- Server: `/home/user/cg-exo/exo/networking/grpc/grpc_server.py`
- Client: `/home/user/cg-exo/exo/networking/grpc/grpc_peer_handle.py`
- Generated code: `/home/user/cg-exo/exo/networking/grpc/node_service_pb2.py` and `node_service_pb2_grpc.py`

### 2.2 HTTP/REST Protocol (External Client API)

**Primary Endpoint**: ChatGPT-compatible API
- **Host**: 0.0.0.0 (configurable)
- **Port**: 52415 (default, configurable with `--chatgpt-api-port`)
- **Framework**: aiohttp (asyncio-based)
- **CORS**: Enabled for all origins

#### Endpoints:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/v1/chat/completions` | POST | Chat completion (main client endpoint) |
| `/v1/models` | GET | List available models |
| `/v1/chat/token/encode` | POST | Tokenize text |
| `/v1/image/generations` | POST | Generate images (SD models) |
| `/v1/topology` | GET | Cluster topology info |
| `/healthcheck` | GET | Health status |
| `/models/{model_name}` | DELETE | Delete model |
| `/download` | POST | Download model |
| `/modelpool` | GET | Supported models |

**File**: `/home/user/cg-exo/exo/api/chatgpt_api.py`

#### Chat Completion Request Format:

```python
{
  "model": "llama-3.2-3b",
  "messages": [
    {"role": "user", "content": "Your prompt here"}
  ],
  "temperature": 0.7,
  "stream": false  # or true for streaming
}
```

#### Example Usage (Python):

```python
import requests

response = requests.post(
  "http://localhost:52415/v1/chat/completions",
  json={
    "model": "llama-3.2-3b",
    "messages": [{"role": "user", "content": "What is exo?"}],
    "temperature": 0.7
  }
)
print(response.json())
```

---

## 3. CLIENT CONNECTION FLOW

### 3.1 Connection Flow Diagram

```
Android Client
       │
       ├─→ HTTP/REST Connection
       │   ├─→ POST /v1/chat/completions
       │   ├─→ GET /v1/models
       │   └─→ GET /v1/topology
       │
       └─→ gRPC Connection (Advanced)
           ├─→ SendPrompt
           ├─→ SendTensor
           └─→ HealthCheck
               │
               ↓
        Discovery Module
        ├─ UDP Discovery
        ├─ Tailscale Discovery
        └─ Manual Discovery (JSON config)
            │
            ↓
        Node Cluster
        ├─ Node 1 (gRPC server on port N)
        ├─ Node 2 (gRPC server on port M)
        └─ Node N (gRPC server on port K)
```

### 3.2 HTTP Request Flow (Recommended for Android)

1. **Client sends HTTP POST** to `/v1/chat/completions`
2. **ChatGPT API layer** parses request and creates chat prompt
3. **Node (Orchestration layer)**:
   - Tokenizes prompt
   - Determines current shard location
   - Calls `process_prompt()`
4. **Inference forwarding**:
   - If node is first layer: Run inference locally
   - Else: Forward tensor via gRPC to next peer
5. **Ring propagation**: Tensor travels through ring of nodes
6. **Final layer**: Returns tokens to API
7. **API** converts tokens back to text
8. **Response sent** back to Android client

---

## 4. NODE DISCOVERY & TOPOLOGY

### 4.1 Discovery Mechanisms

The system supports three discovery methods:

#### A. UDP Discovery (Default, Automatic)
- **Port**: Configurable listen/broadcast port (default 5678)
- **Mechanism**: Broadcast UDP packets announcing node presence
- **Interval**: ~2.5 seconds
- **Best for**: Same network/subnet
- **File**: `/home/user/cg-exo/exo/networking/udp/udp_discovery.py`

```bash
exo --discovery-module udp --listen-port 5678 --broadcast-port 5678
```

#### B. Manual Discovery (Static Configuration)
- **Configuration**: JSON file with all nodes listed
- **Mechanism**: Read static JSON config with peer addresses
- **Best for**: Fixed clusters, corporate networks
- **File**: `/home/user/cg-exo/exo/networking/manual/manual_discovery.py`

```bash
exo --discovery-module manual --discovery-config-path ./network.json
```

**Configuration Format** (`network.json`):

```json
{
  "peers": {
    "node1": {
      "address": "192.168.1.100",
      "port": 50051,
      "device_capabilities": {
        "model": "MacBook Pro",
        "chip": "Apple M1 Pro",
        "memory": 16384,
        "flops": {
          "fp32": 5.3,
          "fp16": 10.6,
          "int8": 21.2
        }
      }
    },
    "node2": {
      "address": "192.168.1.101",
      "port": 50052,
      "device_capabilities": {
        "model": "RTX 4090",
        "chip": "NVIDIA RTX 4090",
        "memory": 24576,
        "flops": {
          "fp32": 82.58,
          "fp16": 165.16,
          "int8": 330.32
        }
      }
    }
  }
}
```

#### C. Tailscale Discovery
- **Mechanism**: VPN-based discovery via Tailscale API
- **Best for**: Remote clusters across networks
- **Files**: `/home/user/cg-exo/exo/networking/tailscale/`

```bash
exo --discovery-module tailscale \
    --tailscale-api-key YOUR_API_KEY \
    --tailnet-name your-tailnet.com
```

### 4.2 Topology Information

**Endpoint**: `GET /v1/topology`

**Response Format**:

```json
{
  "nodes": {
    "node_id_1": {
      "model": "Device Model",
      "chip": "Chip Name",
      "memory": 16384,
      "flops": {
        "fp32": 5.3,
        "fp16": 10.6,
        "int8": 21.2
      }
    }
  },
  "peer_graph": {
    "node_id_1": [
      {
        "from_id": "node_id_1",
        "to_id": "node_id_2",
        "description": "gRPC peer"
      }
    ]
  },
  "active_node_id": "node_id_1"
}
```

---

## 5. GATEWAYS FOR ANDROID CLIENTS

### Option A: HTTP/REST (Recommended - Simple)

**Pros**:
- Standard HTTP client libraries available
- Works across networks
- Easy to implement
- Built-in error handling
- Streaming support

**Cons**:
- HTTP overhead
- Single point of connection

**Implementation**:
1. Connect to any node's HTTP endpoint (port 52415)
2. Use standard HTTP client (Retrofit, OkHttp, etc.)
3. Send chat/completion requests
4. Parse JSON responses

**Example Android Implementation**:

```kotlin
// Using Retrofit
interface ExoApiService {
    @POST("/v1/chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
    
    @GET("/v1/models")
    suspend fun getModels(): ModelsResponse
    
    @GET("/v1/topology")
    suspend fun getTopology(): TopologyResponse
}

// Usage
val apiService = Retrofit.Builder()
    .baseUrl("http://node-ip:52415")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(ExoApiService::class.java)

val response = apiService.chatCompletion(
    ChatCompletionRequest(
        model = "llama-3.2-3b",
        messages = listOf(
            Message(role = "user", content = "Hello")
        ),
        temperature = 0.7
    )
)
```

### Option B: gRPC (Advanced - Direct P2P)

**Pros**:
- Direct node communication
- Lower latency
- More control
- Binary protocol (efficient)

**Cons**:
- More complex
- Need to generate gRPC stubs for Android
- Manual peer discovery

**Implementation Steps**:

1. **Generate gRPC stubs for Android**:
   ```bash
   # Copy node_service.proto to Android project
   # Use protoc compiler with grpc-java plugin
   protoc --java_out=. --grpc-java_out=. node_service.proto
   ```

2. **Add gRPC dependencies** (Gradle):
   ```gradle
   implementation 'io.grpc:grpc-android:1.70.0'
   implementation 'io.grpc:grpc-protobuf-lite:1.70.0'
   ```

3. **Connect to node**:
   ```kotlin
   val channel = ManagedChannelBuilder
       .forAddress("node-ip", 50051)
       .usePlaintext()
       .build()
   
   val stub = NodeServiceGrpc.newBlockingStub(channel)
   val response = stub.sendPrompt(promptRequest)
   ```

---

## 6. NODE ENTRY POINTS & STARTUP

### 6.1 Main Entry Point

**File**: `/home/user/cg-exo/exo/main.py`

**Startup Command**:

```bash
exo [command] [model_name] [options]
```

**Commands**:
- `run`: Run a model directly with CLI
- `eval`: Evaluate on dataset
- `train`: Fine-tune on dataset
- (none): Start as server (default)

**Key Startup Arguments**:

```bash
exo run llama-3.2-3b [options]

# Key options:
--node-id <id>                    # Unique node identifier
--node-host <ip>                  # Listen IP (default 0.0.0.0)
--node-port <port>                # gRPC port (auto-assigned if not set)
--discovery-module <udp|tailscale|manual>  # Discovery method
--discovery-config-path <path>    # JSON config for manual discovery
--chatgpt-api-port <port>         # HTTP API port (default 52415)
--inference-engine <mlx|tinygrad|dummy>
--max-generate-tokens <num>       # Max tokens to generate
--system-prompt <text>            # Custom system prompt
```

### 6.2 Node Initialization Flow

```
main.py
  ├─→ Parse arguments
  ├─→ Load inference engine (MLX/Tinygrad/Dummy)
  ├─→ Initialize discovery module
  │   └─→ Start discovering peers (async)
  ├─→ Create Node instance
  │   ├─→ Initialize gRPC server
  │   ├─→ Start discovery
  │   └─→ Collect topology from peers
  ├─→ Start ChatGPT API (aiohttp server on port 52415)
  └─→ Enter event loop (uvloop for performance)
```

### 6.3 GRPCServer Initialization

**File**: `/home/user/cg-exo/exo/networking/grpc/grpc_server.py`

```python
class GRPCServer:
    async def start(self):
        # Configure gRPC server
        self.server = grpc.aio.server(
            futures.ThreadPoolExecutor(max_workers=32),
            options=[
                ("grpc.max_metadata_size", 32*1024*1024),
                ("grpc.max_send_message_length", 256*1024*1024),
                ("grpc.max_receive_message_length", 256*1024*1024),
                ("grpc.keepalive_time_ms", 10000),
                # ... more options
            ]
        )
        # Register service and start
        node_service_pb2_grpc.add_NodeServiceServicer_to_server(self, self.server)
        self.server.add_insecure_port(f"{self.host}:{self.port}")
        await self.server.start()
```

---

## 7. REQUEST PROCESSING PIPELINE

### 7.1 Complete Request Flow for Chat Completions

```
HTTP Request: POST /v1/chat/completions
  │
  ├─→ ChatGPT API receives request
  │   ├─→ Parse JSON body
  │   ├─→ Validate model name
  │   └─→ Load tokenizer for model
  │
  ├─→ Build prompt using chat template
  │   └─→ tokenizer.apply_chat_template()
  │
  ├─→ Node.process_prompt(shard, prompt, request_id)
  │   ├─→ Determine current shard
  │   │   └─→ Get shard boundaries for this device
  │   │
  │   ├─→ Is this first layer?
  │   │   YES:
  │   │   ├─→ Tokenize prompt (encode)
  │   │   ├─→ Run inference_engine.infer_prompt()
  │   │   ├─→ Get output tensor
  │   │   └─→ Process result (sample token)
  │   │
  │   │   NO:
  │   │   └─→ Forward to previous node via gRPC
  │   │
  │   └─→ For each subsequent layer:
  │       ├─→ Send tensor via gRPC SendTensor
  │       ├─→ Receive processed tensor
  │       └─→ Forward to next layer
  │
  ├─→ Last layer outputs tokens
  │
  ├─→ Decode tokens to text
  │   └─→ tokenizer.decode(tokens)
  │
  └─→ Return HTTP response with generated text
```

### 7.2 Key Node Methods

**File**: `/home/user/cg-exo/exo/orchestration/node.py`

| Method | Purpose | Called From |
|--------|---------|-------------|
| `process_prompt()` | Handle text prompt | gRPC API or direct |
| `process_tensor()` | Handle tensor data | gRPC SendTensor |
| `forward_prompt()` | Send prompt to next peer | process_prompt (if not first layer) |
| `forward_tensor()` | Send tensor to next peer | Ring propagation |
| `broadcast_result()` | Send final tokens to origin | When last layer completes |
| `collect_topology()` | Discover peer capabilities | On startup |

---

## 8. CONFIGURATION & SETUP REQUIREMENTS

### 8.1 System Requirements

**Hardware**:
- Minimum: Device with enough RAM to hold a model shard (varies by model and precision)
- GPU/accelerator optional but recommended
- Network connectivity to other nodes

**Software**:
- Python 3.12+
- Dependencies: See `/home/user/cg-exo/setup.py`

**Key Dependencies**:
- `grpcio==1.70.0` and `grpcio-tools==1.70.0`
- `aiohttp==3.10.11` (HTTP server)
- `transformers==4.46.3` (tokenization)
- `numpy==2.0.0`
- Inference engines: `tinygrad` or `mlx` (Apple Silicon)

### 8.2 Environment Variables

```bash
DEBUG=<0-9>              # Debug logging level (0=off, 9=max)
EXO_HOME=~/.cache/exo    # Model cache directory
HF_ENDPOINT=<url>        # HuggingFace mirror endpoint
GRPC_VERBOSITY=error     # Suppress gRPC logs
TINYGRAD_DEBUG=<1-6>     # Tinygrad-specific logging
```

### 8.3 Model Configuration

**Supported Models** (as of last update):
- Llama (3.1, 3.2)
- Mistral
- Qwen
- DeepSeek
- LlaVA (vision)
- Stable Diffusion (image generation)

**Model Loading**:
- Automatic download from Hugging Face
- Models stored in `~/.cache/exo/downloads/`
- Configurable via `--models-seed-dir`

---

## 9. EXISTING CODE ADAPTABLE FOR ANDROID

### 9.1 Client-Side Code (Already Works)

**Function Calling Example**: `/home/user/cg-exo/examples/function_calling.py`
- Shows how to make HTTP requests
- Demonstrates tool/function calling
- Can be directly ported to Android with HTTP client

**Pattern**:
```python
import requests

response = requests.post(
    "http://api-endpoint:52415/v1/chat/completions",
    json={"model": "...", "messages": [...]}
)
result = response.json()
```

### 9.2 Protocol Buffer Definitions

**File**: `/home/user/cg-exo/exo/networking/grpc/node_service.proto`

Already has all message definitions needed:
- `PromptRequest`, `TensorRequest`
- `Tensor`, `Loss`, `Topology`
- `DeviceCapabilities`, `DeviceFlops`

Can be compiled for Android using:
```bash
protoc --java_out=. --grpc-java_out=. node_service.proto
```

### 9.3 iOS Reference (Deprecated but Informative)

**Location**: `/home/user/cg-exo/examples/astra/`
- SwiftUI example app
- Demonstrates mobile architecture
- Shows WebUI integration
- **Status**: Deprecated - "iOS implementation has fallen behind"

---

## 10. ARCHITECTURE DIAGRAM: ANDROID CLIENT INTEGRATION

```
┌─────────────────────────────────────────┐
│        ANDROID CLIENT                   │
│  ┌─────────────────────────────────────┐│
│  │ UI Layer (Compose/XML)               ││
│  │ - Chat UI                            ││
│  │ - Model selection                    ││
│  │ - Settings                           ││
│  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────┐│
│  │ API Client Layer                     ││
│  │ - HTTP/REST (Retrofit)              ││
│  │ - gRPC (optional)                   ││
│  │ - Request/response handling         ││
│  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────┐│
│  │ Data Layer                           ││
│  │ - Caching                           ││
│  │ - Preferences                       ││
│  │ - Request history                   ││
│  └─────────────────────────────────────┘│
└─────────────────────────────────────────┘
            │
            │ HTTP/gRPC
            ↓
┌─────────────────────────────────────────┐
│   EXO CLUSTER (Any Node as Gateway)     │
│  ┌─────────────────────────────────────┐│
│  │ HTTP API Server (aiohttp)            ││
│  │ Port: 52415                          ││
│  │ /v1/chat/completions                ││
│  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────┐│
│  │ Node Orchestration                   ││
│  │ - Request routing                   ││
│  │ - Tensor forwarding                 ││
│  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────┐│
│  │ gRPC Inter-Node Network              ││
│  │ Ring topology for inference         ││
│  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────┐│
│  │ Inference Engines (Distributed)      ││
│  │ - Node 1: Layers 0-5                 ││
│  │ - Node 2: Layers 6-10                ││
│  │ - Node 3: Layers 11-15               ││
│  └─────────────────────────────────────┘│
└─────────────────────────────────────────┘
```

---

## 11. KEY FILES FOR ANDROID DEVELOPER REFERENCE

### Core Networking
- **Protocol Definition**: `/home/user/cg-exo/exo/networking/grpc/node_service.proto`
- **gRPC Client**: `/home/user/cg-exo/exo/networking/grpc/grpc_peer_handle.py`
- **gRPC Server**: `/home/user/cg-exo/exo/networking/grpc/grpc_server.py`
- **PeerHandle Interface**: `/home/user/cg-exo/exo/networking/peer_handle.py`

### API & Client Facing
- **HTTP API**: `/home/user/cg-exo/exo/api/chatgpt_api.py`
- **Main Entry**: `/home/user/cg-exo/exo/main.py`

### Node & Orchestration
- **Node Core**: `/home/user/cg-exo/exo/orchestration/node.py`
- **Topology**: `/home/user/cg-exo/exo/topology/topology.py`
- **Device Capabilities**: `/home/user/cg-exo/exo/topology/device_capabilities.py`

### Discovery
- **UDP Discovery**: `/home/user/cg-exo/exo/networking/udp/udp_discovery.py`
- **Manual Discovery**: `/home/user/cg-exo/exo/networking/manual/manual_discovery.py`
- **Tailscale Discovery**: `/home/user/cg-exo/exo/networking/tailscale/`

### Examples
- **Function Calling**: `/home/user/cg-exo/examples/function_calling.py`
- **Chat API Script**: `/home/user/cg-exo/examples/chatgpt_api.sh`
- **iOS Reference**: `/home/user/cg-exo/examples/astra/` (deprecated)

---

## 12. RECOMMENDED ANDROID CLIENT IMPLEMENTATION STRATEGY

### Phase 1: HTTP/REST Client (Recommended Start)
**Complexity**: Low | **Time**: 1-2 weeks

```
1. Set up Android project with Retrofit/OkHttp
2. Implement API client for /v1/chat/completions
3. Create Models/DataClasses for API DTOs
4. Build chat UI with message history
5. Add model selection and settings
6. Test against running exo cluster
```

**Advantages**:
- No gRPC code generation needed
- Works across any network (NAT-friendly)
- Standard HTTP libraries
- Easy to debug

### Phase 2: Enhanced Features (2-4 weeks)
- Topology discovery visualization
- Multi-node status monitoring
- Streaming support
- Offline detection and retry logic
- Token counting before sending

### Phase 3: gRPC Direct (Advanced, Optional)
**Complexity**: Medium-High | **Time**: 3-4 weeks

```
1. Generate gRPC stubs from proto
2. Implement peer discovery
3. Direct node connection logic
4. Ring topology visualization
5. Direct tensor communication (advanced)
```

**Only needed for**:
- Lower latency requirements
- P2P mesh without gateway node
- Advanced features (direct topology queries)

---

## 13. CRITICAL ARCHITECTURAL INSIGHTS FOR ANDROID

### 13.1 Stateless Design
- Android client is completely **stateless** from server perspective
- Every request includes full context (request_id)
- Server tracks state via `request_id` strings
- No session management needed

### 13.2 Async-Everything
- All operations are **async** (no blocking)
- Use coroutines in Android (Kotlin)
- Response streaming for long operations

### 13.3 No Master Node
- Can connect to **any node** in cluster
- Node will forward requests automatically
- Topology updates broadcast via opaque_status messages

### 13.4 Tensor Serialization
- Tensors sent as **raw bytes** + metadata
- Format: `{bytes, shape, dtype}`
- Android must handle numpy array serialization

### 13.5 Model Sharding Transparent to Client
- Client sends request to any node
- System automatically routes through ring
- Client sees unified model interface

---

## 14. ERROR HANDLING & RESILIENCE

### Common Failure Modes

1. **Node Unavailable**
   - HTTP: 503 Service Unavailable
   - gRPC: Connection refused
   - Solution: Retry with exponential backoff

2. **Model Not Downloaded**
   - HTTP: 400 with "Unsupported model"
   - Solution: Call `/download` endpoint first

3. **Tensor Too Large**
   - HTTP: 413 Payload Too Large
   - gRPC: Resource exhausted
   - Solution: Check max_message_length (256MB)

4. **Topology Not Ready**
   - HTTP: 503 Service Unavailable
   - Solution: Wait for topology discovery

### Recommended Client Resilience

```kotlin
// Pseudo-code for Android
suspend fun sendPrompt(prompt: String, maxRetries: Int = 3): Response {
    repeat(maxRetries) { attempt ->
        return try {
            apiClient.chatCompletion(request)
        } catch (e: IOException) {
            if (attempt < maxRetries - 1) {
                delay(1000 * (attempt + 1)) // Exponential backoff
            } else {
                throw
            }
        }
    }
}
```

---

## SUMMARY TABLE: Client Options Comparison

| Feature | HTTP/REST | gRPC |
|---------|-----------|------|
| **Complexity** | Low | High |
| **Setup Time** | 1-2 weeks | 3-4 weeks |
| **Network Friendly** | Yes (NAT friendly) | No (requires port forwarding) |
| **Bandwidth** | Higher (JSON) | Lower (binary) |
| **Latency** | Medium | Low |
| **Android Libraries** | Retrofit (mature) | gRPC-java (newer) |
| **Debugging** | Easy (curl) | Medium |
| **Recommended for MVP** | **YES** | No |

---

## CONCLUSION

The EXO system is well-architected for Android client implementation. The **recommended approach** is:

1. **Start with HTTP/REST** - implement a chat client connecting to any node's port 52415
2. **Use Retrofit** for HTTP client (Android standard)
3. **Connect to cluster** via any node's HTTP endpoint
4. **No gRPC needed** for basic functionality
5. **Can upgrade to gRPC later** if performance critical

The system handles all complexity (model sharding, peer communication, topology management) on the server side. Android client just needs to:
- Send HTTP POST to `/v1/chat/completions`
- Parse JSON response
- Handle streaming if desired
- Implement retry/error handling

All the infrastructure is ready - just need to build the UI and HTTP client layer!

