package cl.zea.glia

import cl.zea.glia.core.client.GliaClientProtocol
import cl.zea.glia.core.models.GliaHistoryResponse
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
    var shouldFailConnect: Boolean = false
    var historyResultProvider: ((limit: Int, beforeSequence: Int?) -> Result<GliaHistoryResponse>)? = null

    override suspend fun fetchHistory(limit: Int, beforeSequence: Int?): Result<GliaHistoryResponse> {
        val provider = historyResultProvider
        return if (provider != null) {
            provider(limit, beforeSequence)
        } else {
            super.fetchHistory(limit, beforeSequence)
        }
    }

    override suspend fun connect() {
        if (shouldFailConnect) {
            throw cl.zea.glia.core.models.GliaException.NotConnected("Simulated connection failure to Phoenix gateway")
        }
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

        viewModel.send("Hello")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals("Hello", viewModel.uiState.value.messages[0].content)
        assertTrue(viewModel.uiState.value.isStreaming)

        mockClient.emit(GliaStreamEvent.ThinkingDelta("Thinking..."))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Thinking...", viewModel.uiState.value.currentThinking)

        mockClient.emit(GliaStreamEvent.MessageDelta("Complete "))
        mockClient.emit(GliaStreamEvent.MessageDelta("response."))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Complete response.", viewModel.uiState.value.currentText)

        mockClient.emit(GliaStreamEvent.Done("Complete response."))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals(GliaMessageRole.ASSISTANT, viewModel.uiState.value.messages[1].role)
        assertEquals("Complete response.", viewModel.uiState.value.messages[1].content)
        assertEquals("Thinking...", viewModel.uiState.value.messages[1].thinking)
    }

    @Test
    fun testToolNamePreservedInAssistantMessageAfterToolResult() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.send("Check my balance")
        testDispatcher.scheduler.advanceUntilIdle()

        mockClient.emit(GliaStreamEvent.ToolCall("check_balance", emptyMap()))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("check_balance", viewModel.uiState.value.currentTool)

        mockClient.emit(GliaStreamEvent.ToolResult("check_balance", kotlinx.serialization.json.JsonPrimitive("OK")))
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.currentTool)

        mockClient.emit(GliaStreamEvent.Done("Your balance is $100 USD"))
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

        viewModel.send("First message")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(1, mockClient.sentPrompts.size)

        // Concurrent send attempt must be ignored
        viewModel.send("Second concurrent message")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, mockClient.sentPrompts.size)

        mockClient.emit(GliaStreamEvent.Done("Response to first"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)

        // Now sending should be allowed
        viewModel.send("Third message")
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
            content = "Hello",
            thinking = "Reasoning...",
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

    @Test
    fun testErrorSanitizationAndCallbacks() = runBlocking {
        val mockClient = MockGliaClient()
        mockClient.shouldFailConnect = true
        val viewModel = GliaChatViewModel(mockClient)

        var errorCallbackReceived = false
        viewModel.onError = {
            errorCallbackReceived = true
        }

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(errorCallbackReceived)
        assertEquals(GliaChatViewModel.DEFAULT_ERROR_MESSAGE, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.errorMessage?.contains("Glia", ignoreCase = true) == true)
        assertFalse(viewModel.uiState.value.errorMessage?.contains("Phoenix", ignoreCase = true) == true)
    }

    @Test
    fun testCustomErrorSanitizer() = runBlocking {
        val mockClient = MockGliaClient()
        mockClient.shouldFailConnect = true
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.errorSanitizer = { "Mensaje de error personalizado" }
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Mensaje de error personalizado", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testStreamErrorInvokesOnErrorAndSanitizer() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        var capturedError: Throwable? = null
        viewModel.onError = { err -> capturedError = err }
        viewModel.errorSanitizer = { err -> "Sanitized: ${err.message}" }

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        mockClient.emit(GliaStreamEvent.Error("bad_gateway"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(capturedError)
        assertEquals("Sanitized: Server error: bad_gateway", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testAutoReconnectOnSend() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        assertFalse(viewModel.uiState.value.isConnected)

        viewModel.send("Auto-reconnect prompt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals(listOf("Auto-reconnect prompt"), mockClient.sentPrompts)
    }

    @Test
    fun testRetryLastSendDoesNotDuplicateUserMessage() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.send("Initial prompt")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, mockClient.sentPrompts.size)
        assertEquals(1, viewModel.uiState.value.messages.size)

        mockClient.emit(GliaStreamEvent.Done("First answer"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(2, viewModel.uiState.value.messages.size)

        // Retry should re-send the prompt without duplicating the user message in `messages`
        viewModel.retryLastSend()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, mockClient.sentPrompts.size)
        // `messages` should STILL only have 2 messages (1 user, 1 assistant), NOT 3!
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals(listOf("Initial prompt", "Initial prompt"), mockClient.sentPrompts)
    }

    @Test
    fun testAutoLoadHistoryOnConnect() = runBlocking {
        val mockClient = MockGliaClient()
        mockClient.historyResultProvider = { limit, beforeSequence ->
            assertEquals(20, limit)
            assertNull(beforeSequence)
            Result.success(
                GliaHistoryResponse(
                    messages = listOf(
                        GliaChatMessage(id = "h1", role = GliaMessageRole.USER, content = "Msg 1", sequence = 1),
                        GliaChatMessage(id = "h2", role = GliaMessageRole.ASSISTANT, content = "Msg 2", sequence = 2)
                    ),
                    hasMore = true,
                    sessionId = "app:user"
                )
            )
        }

        var updateCallbackMessages: List<GliaChatMessage>? = null
        val viewModel = GliaChatViewModel(
            client = mockClient,
            onMessagesUpdated = { updateCallbackMessages = it }
        )

        assertEquals(0, viewModel.uiState.value.messages.size)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertFalse(viewModel.uiState.value.isLoadingHistory)
        assertTrue(viewModel.uiState.value.hasMoreHistory)
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("h1", viewModel.uiState.value.messages[0].id)
        assertEquals("h2", viewModel.uiState.value.messages[1].id)
        assertNotNull(updateCallbackMessages)
        assertEquals(2, updateCallbackMessages!!.size)
    }

    @Test
    fun testLoadMoreHistoryPagination() = runBlocking {
        val mockClient = MockGliaClient()
        var fetchCallCount = 0

        mockClient.historyResultProvider = { limit, beforeSequence ->
            fetchCallCount++
            if (fetchCallCount == 1) {
                // Initial history load
                Result.success(
                    GliaHistoryResponse(
                        messages = listOf(
                            GliaChatMessage(id = "h3", role = GliaMessageRole.USER, content = "Recent 3", sequence = 3),
                            GliaChatMessage(id = "h4", role = GliaMessageRole.ASSISTANT, content = "Recent 4", sequence = 4)
                        ),
                        hasMore = true
                    )
                )
            } else {
                // Pagination call: should request beforeSequence = 3
                assertEquals(3, beforeSequence)
                Result.success(
                    GliaHistoryResponse(
                        messages = listOf(
                            GliaChatMessage(id = "h1", role = GliaMessageRole.USER, content = "Old 1", sequence = 1),
                            GliaChatMessage(id = "h2", role = GliaMessageRole.ASSISTANT, content = "Old 2", sequence = 2)
                        ),
                        hasMore = false
                    )
                )
            }
        }

        val viewModel = GliaChatViewModel(client = mockClient)
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertTrue(viewModel.uiState.value.hasMoreHistory)

        // Trigger pagination
        viewModel.loadMoreHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        // After loading more history, older messages (h1, h2) must be prepended before recent ones (h3, h4)
        val messages = viewModel.uiState.value.messages
        assertEquals(4, messages.size)
        assertEquals("h1", messages[0].id)
        assertEquals("h2", messages[1].id)
        assertEquals("h3", messages[2].id)
        assertEquals("h4", messages[3].id)
        assertFalse(viewModel.uiState.value.hasMoreHistory)
    }

    @Test
    fun testClearMessagesResetsHasMoreHistory() = runBlocking {
        val mockClient = MockGliaClient()
        mockClient.historyResultProvider = { _, _ ->
            Result.success(
                GliaHistoryResponse(
                    messages = listOf(GliaChatMessage(role = GliaMessageRole.USER, content = "Test", sequence = 1)),
                    hasMore = true
                )
            )
        }

        val viewModel = GliaChatViewModel(mockClient)
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasMoreHistory)
        assertEquals(1, viewModel.uiState.value.messages.size)

        viewModel.clearMessages()
        assertEquals(0, viewModel.uiState.value.messages.size)
        assertFalse(viewModel.uiState.value.hasMoreHistory)
    }

    @Test
    fun testNoDuplicateInitialHistoryLoadOnReconnection() = runBlocking {
        val mockClient = MockGliaClient()
        var fetchCallCount = 0
        mockClient.historyResultProvider = { _, _ ->
            fetchCallCount++
            Result.success(
                GliaHistoryResponse(
                    messages = listOf(GliaChatMessage(id = "msg1", role = GliaMessageRole.USER, content = "Hola")),
                    hasMore = false
                )
            )
        }

        val viewModel = GliaChatViewModel(mockClient)
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fetchCallCount)
        assertEquals(1, viewModel.uiState.value.messages.size)

        // Simulate disconnect and reconnect cycle
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isConnected)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnected)

        // Must still be 1 (NOT re-fetched on reconnect)
        assertEquals(1, fetchCallCount)
        assertEquals(1, viewModel.uiState.value.messages.size)
    }

    @Test
    fun testFormatErrorSanitizesRestAndServerError() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        // Trigger error handling with REST error
        mockClient.shouldFailConnect = true
        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        // Test with custom exception mentioning REST
        val restException = RuntimeException("REST error 502: Bad Gateway details with SQL trace")
        val errorMethod = viewModel.javaClass.getDeclaredMethod("formatError", Throwable::class.java)
        errorMethod.isAccessible = true
        val sanitizedRest = errorMethod.invoke(viewModel, restException) as String
        assertEquals(GliaChatViewModel.DEFAULT_ERROR_MESSAGE, sanitizedRest)

        val serverErrorException = RuntimeException("server error: postgres connection pool exhausted")
        val sanitizedServerErr = errorMethod.invoke(viewModel, serverErrorException) as String
        assertEquals(GliaChatViewModel.DEFAULT_ERROR_MESSAGE, sanitizedServerErr)
    }

    @Test
    fun testDisconnectionDuringStreamingResetsIsStreamingAndSetsError() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnected)

        // Start sending a message
        viewModel.send("Hola, ¿cómo estás?")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        // Simulate disconnection while receiving streaming
        mockClient.emit(GliaStreamEvent.Status("disconnected"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Must release the streaming state so the UI is not blocked
        assertFalse(viewModel.uiState.value.isStreaming)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testIdleDisconnectionDoesNotTriggerErrorMessageAndReconnectClearsIt() = runBlocking {
        val mockClient = MockGliaClient()
        val viewModel = GliaChatViewModel(mockClient)

        viewModel.errorSanitizer = {
            "Estamos con problemas y en estos momentos no podemos atender su solicitud."
        }

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnected)

        // Simulate idle disconnection (e.g. phone went to sleep, socket closed)
        mockClient.emit(GliaStreamEvent.Status("disconnected"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Must NOT show error banner when idle disconnection occurs
        assertFalse(viewModel.uiState.value.isConnected)
        assertNull(viewModel.uiState.value.errorMessage)

        // Simulate successful reconnection (e.g. returning to foreground)
        mockClient.emit(GliaStreamEvent.Status("connected"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
