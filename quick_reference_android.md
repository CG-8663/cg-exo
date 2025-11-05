# QUICK REFERENCE: Android Client Implementation for EXO

## TL;DR - Start Here

### Option 1: HTTP/REST (Recommended - Start Here First)
```
1. Connect to any running EXO node
2. Use Retrofit to POST to http://node-ip:52415/v1/chat/completions
3. That's it!
```

### Option 2: gRPC (Advanced - Skip for MVP)
```
1. Generate gRPC stubs from node_service.proto
2. Create gRPC channel to node
3. Call NodeServiceGrpc stubs directly
```

---

## Quick Connectivity Test

### Python Test (Before Android)
```bash
# Start EXO cluster
exo

# In another terminal, test the API
curl http://localhost:52415/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
     "model": "llama-3.2-3b",
     "messages": [{"role": "user", "content": "Hello"}],
     "temperature": 0.7
   }'
```

### Get Cluster Info
```bash
# List models
curl http://localhost:52415/v1/models

# Get topology
curl http://localhost:52415/v1/topology

# Health check
curl http://localhost:52415/healthcheck
```

---

## HTTP/REST Client (Android - Retrofit Example)

### Build.gradle
```gradle
dependencies {
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    implementation 'com.squareup.okhttp3:okhttp:4.11.0'
}
```

### Data Models
```kotlin
// Request
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class Message(
    val role: String,  // "user", "assistant", "system"
    val content: String
)

// Response
data class ChatCompletionResponse(
    val id: String,
    val object: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: Message,
    val finish_reason: String?
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

// Models Endpoint
data class ModelsResponse(
    val object: String,
    val data: List<Model>
)

data class Model(
    val id: String,
    val object: String
)
```

### API Service
```kotlin
interface ExoApiService {
    @POST("/v1/chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
    
    @GET("/v1/models")
    suspend fun getModels(): ModelsResponse
    
    @GET("/v1/topology")
    suspend fun getTopology(): TopologyResponse
    
    @GET("/healthcheck")
    suspend fun healthCheck(): HealthCheckResponse
}

data class TopologyResponse(
    val nodes: Map<String, DeviceCapabilities>,
    val peer_graph: Map<String, List<PeerConnection>>,
    val active_node_id: String?
)

data class DeviceCapabilities(
    val model: String,
    val chip: String,
    val memory: Int,
    val flops: DeviceFlops
)

data class DeviceFlops(
    val fp32: Float,
    val fp16: Float,
    val int8: Float
)

data class PeerConnection(
    val from_id: String,
    val to_id: String,
    val description: String?
)

data class HealthCheckResponse(
    val status: String
)
```

### Repository
```kotlin
class ExoRepository(private val nodeIp: String, private val nodePort: Int = 52415) {
    private val apiService: ExoApiService = Retrofit.Builder()
        .baseUrl("http://$nodeIp:$nodePort")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ExoApiService::class.java)

    suspend fun sendMessage(
        prompt: String,
        model: String = "llama-3.2-3b",
        temperature: Float = 0.7f
    ): Result<String> = try {
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(Message("user", prompt)),
            temperature = temperature
        )
        val response = apiService.chatCompletion(request)
        Result.success(response.choices.firstOrNull()?.message?.content ?: "No response")
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getModels(): Result<List<String>> = try {
        val response = apiService.getModels()
        Result.success(response.data.map { it.id })
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getTopology(): Result<TopologyResponse> = try {
        val response = apiService.getTopology()
        Result.success(response)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun healthCheck(): Result<Boolean> = try {
        apiService.healthCheck()
        Result.success(true)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### ViewModel
```kotlin
class ChatViewModel(nodeIp: String) : ViewModel() {
    private val repository = ExoRepository(nodeIp)
    
    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()
    
    private val _selectedModel = MutableStateFlow("llama-3.2-3b")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun sendMessage(text: String) = viewModelScope.launch {
        _uiState.value = ChatUiState.Loading
        _messages.value = _messages.value + Message("user", text)
        
        val result = repository.sendMessage(text, _selectedModel.value)
        result.onSuccess { response ->
            _messages.value = _messages.value + Message("assistant", response)
            _uiState.value = ChatUiState.Success
        }.onFailure { error ->
            _uiState.value = ChatUiState.Error(error.message ?: "Unknown error")
        }
    }

    fun loadModels() = viewModelScope.launch {
        val result = repository.getModels()
        result.onSuccess {
            _models.value = it
        }
    }

    fun selectModel(model: String) {
        _selectedModel.value = model
    }
}

sealed class ChatUiState {
    object Idle : ChatUiState()
    object Loading : ChatUiState()
    object Success : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}
```

### Compose UI
```kotlin
@Composable
fun ChatScreen(nodeIp: String) {
    val viewModel: ChatViewModel = remember { ChatViewModel(nodeIp) }
    
    LaunchedEffect(Unit) {
        viewModel.loadModels()
    }
    
    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val models by viewModel.models.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Model:", modifier = Modifier.align(Alignment.CenterVertically))
            DropdownMenu(
                expanded = false,
                onDismissRequest = {},
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = { viewModel.selectModel(model) }
                    )
                }
            }
            Text(selectedModel, modifier = Modifier.align(Alignment.CenterVertically))
        }
        
        Divider()
        
        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatMessageCard(message)
            }
        }
        
        // Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var text by remember { mutableStateOf("") }
            
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                placeholder = { Text("Your message...") },
                enabled = uiState !is ChatUiState.Loading
            )
            
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        viewModel.sendMessage(text)
                        text = ""
                    }
                },
                enabled = uiState !is ChatUiState.Loading
            ) {
                if (uiState is ChatUiState.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Send")
                }
            }
        }
        
        // Error message
        if (uiState is ChatUiState.Error) {
            Text(
                (uiState as ChatUiState.Error).message,
                color = Color.Red,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun ChatMessageCard(message: Message) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.role == "user") Color.LightBlue else Color.LightGray
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = message.role.uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(text = message.content)
        }
    }
}
```

---

## Debugging Network Issues

### Check Node is Accessible
```bash
# From Android device/emulator
adb shell ping -c 3 192.168.1.100

# Check port is open
adb shell nc -zv 192.168.1.100 52415
```

### Common Issues

| Issue | Solution |
|-------|----------|
| Connection refused | Node not running or wrong port |
| Timeout | Firewall blocking, try `--node-host 0.0.0.0` |
| Model not found | Download with `POST /download` first |
| Out of memory | Reduce model size or add more nodes |
| Streaming not working | Check gzip compression settings |

---

## File Locations Reference

### Protocol Definitions
- `/home/user/cg-exo/exo/networking/grpc/node_service.proto`

### API Implementation
- `/home/user/cg-exo/exo/api/chatgpt_api.py` (HTTP endpoints)
- `/home/user/cg-exo/exo/networking/grpc/grpc_server.py` (gRPC server)

### Node Core
- `/home/user/cg-exo/exo/orchestration/node.py`
- `/home/user/cg-exo/exo/main.py` (startup)

### Configuration Examples
- `/home/user/cg-exo/exo/networking/manual/test_data/test_config.json`

### Examples
- `/home/user/cg-exo/examples/function_calling.py` (HTTP client pattern)
- `/home/user/cg-exo/examples/chatgpt_api.sh` (curl examples)

---

## What Android App Needs to Do

### Minimum (MVP)
- [ ] HTTP client setup (Retrofit)
- [ ] Chat UI with message list
- [ ] Text input field
- [ ] Send button
- [ ] Parse responses
- [ ] Error handling

### Phase 1 (Polish)
- [ ] Model selection dropdown
- [ ] Loading indicators
- [ ] Retry logic
- [ ] Message persistence (local DB)
- [ ] Copy/share message buttons

### Phase 2 (Advanced)
- [ ] Streaming responses
- [ ] Topology visualization
- [ ] Node status display
- [ ] Download models
- [ ] Image generation support

---

## Key Endpoints for Android

```
Base URL: http://{node-ip}:52415

POST /v1/chat/completions
  Request: { model, messages, temperature, stream }
  Response: { choices[0].message.content }

GET /v1/models
  Response: { data[].id }

GET /v1/topology
  Response: { nodes, peer_graph, active_node_id }

GET /healthcheck
  Response: { status }
```

