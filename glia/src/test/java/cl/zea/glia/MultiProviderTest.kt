package cl.zea.glia

import cl.zea.glia.core.client.AgentProvider
import cl.zea.glia.core.client.GliaClient
import cl.zea.glia.core.client.GliaClientProtocol
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import cl.zea.glia.core.providers.phoenix.ZeaPhoenixProvider
import cl.zea.glia.core.providers.sse.SseAgentProvider
import cl.zea.glia.core.providers.sse.SseOptions
import cl.zea.glia.ui.GliaChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomMockProvider : AgentProvider {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    var lastPrompt: String? = null

    override suspend fun connect() {
        _isConnected.value = true
        _events.emit(GliaStreamEvent.Status("connected"))
    }

    override suspend fun disconnect() {
        _isConnected.value = false
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
        lastPrompt = prompt
        _events.emit(GliaStreamEvent.ThinkingDelta("Reasoning..."))
        _events.emit(GliaStreamEvent.MessageDelta("Response to: $prompt"))
        _events.emit(GliaStreamEvent.Done("Response to: $prompt"))
    }

    override fun close() {
        _isConnected.value = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MultiProviderTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testGliaClientWithCustomAgentProvider() = runBlocking {
        val mockProvider = CustomMockProvider()
        val client = GliaClient.create(mockProvider)

        assertFalse(client.isConnected.value)
        client.connect()
        assertTrue(client.isConnected.value)

        client.send("Hello custom agent")
        assertEquals("Hello custom agent", mockProvider.lastPrompt)

        client.disconnect()
        assertFalse(client.isConnected.value)
    }

    @Test
    fun testGliaChatViewModelWithDecoupledProvider() = runBlocking {
        val mockProvider = CustomMockProvider()
        val client = GliaClient.create(mockProvider)
        val viewModel = GliaChatViewModel(client)

        client.connect()
        viewModel.send("Test message from UI")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Test message from UI", mockProvider.lastPrompt)
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun testZeaPhoenixProviderInstantiation() {
        val provider = ZeaPhoenixProvider(
            gatewayUrl = "wss://api.zea.cl",
            appId = "agent-123",
            userId = "usr-456",
            token = "jwt-sample"
        )
        assertEquals("session:agent-123:usr-456", provider.options.topic)
        assertFalse(provider.isConnected.value)
    }

    @Test
    fun testSseAgentProviderInstantiation() {
        val sseOptions = SseOptions(
            endpointUrl = "https://agent.example.com/v1/chat-messages",
            token = "secret-token",
            userId = "usr-sse"
        )
        val provider = SseAgentProvider(sseOptions)
        assertEquals("https://agent.example.com/v1/chat-messages", provider.options.gatewayUrl)
        assertFalse(provider.isConnected.value)
    }

    @Test
    fun testBackwardCompatibilityGliaClientProtocolAlias() {
        val mockProvider: GliaClientProtocol = CustomMockProvider()
        assertNotNull(mockProvider)

        val client = GliaClient(
            GliaOptions(
                gatewayUrl = "ws://localhost:4003",
                appId = "test-app",
                userId = "test-user"
            )
        )
        assertNotNull(client)
    }
}
