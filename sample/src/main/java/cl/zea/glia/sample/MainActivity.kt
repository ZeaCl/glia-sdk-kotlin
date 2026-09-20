package cl.zea.glia.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.zea.glia.core.client.AgentProvider
import cl.zea.glia.core.client.GliaClient
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import cl.zea.glia.core.providers.phoenix.ZeaPhoenixProvider
import cl.zea.glia.core.providers.sse.SseAgentProvider
import cl.zea.glia.ui.GliaChat
import cl.zea.glia.ui.GliaChatViewModel
import cl.zea.glia.ui.GliaTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Offline simulated agent provider generating realistic streaming responses,
 * reasoning deltas, and tool execution without requiring network credentials.
 */
class MockEchoAgentProvider(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AgentProvider {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _isConnected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    override suspend fun connect() {
        _isConnected.value = true
        _events.emit(GliaStreamEvent.Status("ready"))
    }

    override suspend fun disconnect() {
        _isConnected.value = false
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
        scope.launch {
            _events.emit(GliaStreamEvent.Status("thinking"))
            _events.emit(GliaStreamEvent.ThinkingDelta("Analyzing your request: \"$prompt\"..."))
            delay(500)
            _events.emit(GliaStreamEvent.ThinkingDelta("\nChecking internal knowledge base and active tools."))
            delay(400)

            // Simulate tool call
            val toolArgs = buildJsonObject { put("query", prompt) }
            _events.emit(GliaStreamEvent.ToolCall("knowledge_lookup", toolArgs))
            delay(600)
            val toolRes = buildJsonObject { put("status", "success"); put("items_found", 3) }
            _events.emit(GliaStreamEvent.ToolResult("knowledge_lookup", toolRes))
            delay(300)

            // Stream response chunks
            val chunks = listOf(
                "Hello! ",
                "I am your ",
                "Glia AI Assistant. ",
                "You said: \"$prompt\".\n\n",
                "This response is being streamed live ",
                "via the pluggable AgentProvider architecture.\n",
                "• Compose UI: GliaChat\n",
                "• Provider: Offline Mock Simulator"
            )

            var accumulated = ""
            for (chunk in chunks) {
                accumulated += chunk
                _events.emit(GliaStreamEvent.MessageDelta(chunk))
                delay(120)
            }

            _events.emit(GliaStreamEvent.Done(accumulated))
        }
    }

    override fun close() {
        _isConnected.value = false
    }
}

enum class ProviderType {
    MOCK_OFFLINE,
    ZEA_PHOENIX,
    SSE_LANGGRAPH
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GliaSampleScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GliaSampleScreen() {
    val coroutineScope = rememberCoroutineScope()

    var selectedProviderType by remember { mutableStateOf(ProviderType.MOCK_OFFLINE) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Configuration state for ZEA Phoenix
    var phoenixUrl by remember { mutableStateOf("wss://api.zea.cl") }
    var phoenixAppId by remember { mutableStateOf("demo-app") }
    var phoenixUserId by remember { mutableStateOf("user-demo") }
    var phoenixToken by remember { mutableStateOf("") }

    // Configuration state for SSE
    var sseUrl by remember { mutableStateOf("https://api.dify.ai/v1/chat-messages") }
    var sseToken by remember { mutableStateOf("") }

    // Create current provider & client
    var currentClient by remember {
        mutableStateOf(GliaClient.create(MockEchoAgentProvider()))
    }
    var currentViewModel by remember(currentClient) {
        mutableStateOf(GliaChatViewModel(currentClient))
    }

    val isConnected by currentClient.isConnected.collectAsState()

    fun switchProvider(type: ProviderType) {
        selectedProviderType = type
        coroutineScope.launch {
            currentClient.disconnect()
            val newProvider: AgentProvider = when (type) {
                ProviderType.MOCK_OFFLINE -> MockEchoAgentProvider()
                ProviderType.ZEA_PHOENIX -> ZeaPhoenixProvider(
                    gatewayUrl = phoenixUrl,
                    appId = phoenixAppId,
                    userId = phoenixUserId,
                    token = phoenixToken.ifBlank { null }
                )
                ProviderType.SSE_LANGGRAPH -> SseAgentProvider(
                    endpointUrl = sseUrl,
                    token = sseToken.ifBlank { null }
                )
            }
            currentClient = GliaClient.create(newProvider)
            currentViewModel = GliaChatViewModel(currentClient)
            currentClient.connect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Glia Agent Demo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (selectedProviderType) {
                                    ProviderType.MOCK_OFFLINE -> "Provider: Mock (Offline)"
                                    ProviderType.ZEA_PHOENIX -> "Provider: ZEA Cloud (Phoenix)"
                                    ProviderType.SSE_LANGGRAPH -> "Provider: SSE (LangGraph/Dify)"
                                },
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Switch Provider"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16181D),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GliaChat(
                viewModel = currentViewModel,
                theme = GliaTheme(),
                title = "Glia Assistant",
                welcomeMessage = "Welcome to the Glia SDK Demo!\n\nYou are currently running with the pluggable ${selectedProviderType.name} provider. Type a message below to test real-time reasoning and streaming deltas.",
                placeholder = "Ask anything..."
            )
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Select Agent Provider") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Option 1: Mock
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedProviderType == ProviderType.MOCK_OFFLINE,
                            onClick = { switchProvider(ProviderType.MOCK_OFFLINE) }
                        )
                        Text("Mock Offline (Zero setup / Local echo)")
                    }

                    // Option 2: ZEA Phoenix
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedProviderType == ProviderType.ZEA_PHOENIX,
                            onClick = { switchProvider(ProviderType.ZEA_PHOENIX) }
                        )
                        Text("ZEA Cloud (Phoenix v2)")
                    }
                    if (selectedProviderType == ProviderType.ZEA_PHOENIX) {
                        OutlinedTextField(
                            value = phoenixUrl,
                            onValueChange = { phoenixUrl = it },
                            label = { Text("Gateway URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = phoenixAppId,
                            onValueChange = { phoenixAppId = it },
                            label = { Text("App ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Option 3: SSE
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedProviderType == ProviderType.SSE_LANGGRAPH,
                            onClick = { switchProvider(ProviderType.SSE_LANGGRAPH) }
                        )
                        Text("HTTP SSE (LangGraph / Dify)")
                    }
                    if (selectedProviderType == ProviderType.SSE_LANGGRAPH) {
                        OutlinedTextField(
                            value = sseUrl,
                            onValueChange = { sseUrl = it },
                            label = { Text("Endpoint URL") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
