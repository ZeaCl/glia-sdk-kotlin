package cl.zea.glia

import cl.zea.glia.core.client.GliaClientProtocol
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import cl.zea.glia.ui.GliaChatMessage
import cl.zea.glia.ui.GliaChatViewModel
import cl.zea.glia.ui.GliaMessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockGliaClient : GliaClientProtocol {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    val sentPrompts = mutableListOf<String>()

    override suspend fun connect() {
        _isConnected.value = true
    }

    override suspend fun disconnect() {
        _isConnected.value = false
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
        sentPrompts.add(prompt)
    }

    suspend fun emit(event: GliaStreamEvent) {
        _events.emit(event)
    }

    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class GliaChatViewModelTest {

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
    fun testConnectAndAccumulateDeltas() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)

        viewModel.send("Hola")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals("Hola", viewModel.uiState.value.messages[0].content)
        assertTrue(viewModel.uiState.value.isStreaming)

        mockClient.emit(GliaStreamEvent.ThinkingDelta("Pensando..."))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Pensando...", viewModel.uiState.value.currentThinking)

        mockClient.emit(GliaStreamEvent.MessageDelta("Respuesta "))
        mockClient.emit(GliaStreamEvent.MessageDelta("completa."))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Respuesta completa.", viewModel.uiState.value.currentText)

        mockClient.emit(GliaStreamEvent.Done("Respuesta completa."))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals(GliaMessageRole.ASSISTANT, viewModel.uiState.value.messages[1].role)
        assertEquals("Respuesta completa.", viewModel.uiState.value.messages[1].content)
        assertEquals("Pensando...", viewModel.uiState.value.messages[1].thinking)
    }

    @Test
    fun testToolNamePreservedInAssistantMessageAfterToolResult() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.send("Consulta mi saldo")
        testDispatcher.scheduler.advanceUntilIdle()

        mockClient.emit(GliaStreamEvent.ToolCall("check_balance", emptyMap()))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("check_balance", viewModel.uiState.value.currentTool)

        mockClient.emit(GliaStreamEvent.ToolResult("check_balance", kotlinx.serialization.json.JsonPrimitive("OK")))
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentTool)

        mockClient.emit(GliaStreamEvent.Done("Tu saldo es $100.000 CLP"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        val assistantMessage = viewModel.uiState.value.messages[1]
        assertEquals("check_balance", assistantMessage.toolName)
    }

    @Test
    fun testSendIgnoredWhileStreaming() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.send("Primer mensaje")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(1, mockClient.sentPrompts.size)

        // Intento de envío concurrente debe ser ignorado
        viewModel.send("Segundo mensaje concurrente")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, mockClient.sentPrompts.size)

        mockClient.emit(GliaStreamEvent.Done("Respuesta al primero"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)

        // Ahora sí debe permitir enviar
        viewModel.send("Tercer mensaje")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, mockClient.sentPrompts.size)
    }

    @Test
    fun testInitialMessagesAndCallback() = runBlocking {
        val initial = listOf(
            GliaChatMessage(role = GliaMessageRole.USER, content = "Msg 1"),
            GliaChatMessage(role = GliaMessageRole.ASSISTANT, content = "Msg 2")
        )

        var capturedUpdates = mutableListOf<List<GliaChatMessage>>()
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(
            client = mockClient,
            initialMessages = initial,
            onMessagesUpdated = { capturedUpdates.add(it) }
        )

        assertEquals(2, viewModel.uiState.value.messages.size)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.send("Msg 3")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, capturedUpdates.size)
        assertEquals(3, capturedUpdates.last().size)

        mockClient.emit(GliaStreamEvent.Done("Msg 4"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, capturedUpdates.size)
        assertEquals(4, capturedUpdates.last().size)
    }

    @Test
    fun testGliaChatMessageSerialization() {
        val json = Json { prettyPrint = true }
        val message = GliaChatMessage(
            role = GliaMessageRole.ASSISTANT,
            content = "Hola",
            thinking = "Razonamiento...",
            toolName = "search"
        )

        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<GliaChatMessage>(encoded)

        assertEquals(message.id, decoded.id)
        assertEquals(message.role, decoded.role)
        assertEquals(message.content, decoded.content)
        assertEquals(message.thinking, decoded.thinking)
        assertEquals(message.toolName, decoded.toolName)
    }
}
