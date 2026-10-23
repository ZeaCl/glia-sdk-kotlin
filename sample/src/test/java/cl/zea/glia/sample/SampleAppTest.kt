package cl.zea.glia.sample

import cl.zea.glia.core.client.AgentProvider
import cl.zea.glia.core.client.GliaClient
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.providers.phoenix.ZeaPhoenixProvider
import cl.zea.glia.core.providers.sse.SseAgentProvider
import cl.zea.glia.ui.GliaChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SampleAppTest {

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
    fun testMockEchoAgentProviderLifecycleAndEvents() = runTest(testDispatcher) {
        val provider = MockEchoAgentProvider(testDispatcher)
        assertTrue(provider.isConnected.value)

        val receivedEvents = mutableListOf<GliaStreamEvent>()
        val collectorJob = launch {
            provider.events.collect { event ->
                receivedEvents.add(event)
            }
        }

        provider.send("What is Glia?")
        advanceUntilIdle()

        collectorJob.cancel()

        assertTrue(receivedEvents.any { it is GliaStreamEvent.Status })
        assertTrue(receivedEvents.any { it is GliaStreamEvent.ThinkingDelta })
        assertTrue(receivedEvents.any { it is GliaStreamEvent.ToolCall && it.name == "knowledge_lookup" })
        assertTrue(receivedEvents.any { it is GliaStreamEvent.ToolResult && it.name == "knowledge_lookup" })
        assertTrue(receivedEvents.any { it is GliaStreamEvent.MessageDelta })
        assertTrue(receivedEvents.any { it is GliaStreamEvent.Done })
    }

    @Test
    fun testSampleConsumesGliaClientAndViewModel() = runTest(testDispatcher) {
        val provider = MockEchoAgentProvider(testDispatcher)
        val client = GliaClient.create(provider)
        val viewModel = GliaChatViewModel(client)

        client.connect()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isConnected)

        viewModel.send("Testing GliaChat integration")
        advanceUntilIdle()

        // User message and Assistant response should both be processed
        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("Testing GliaChat integration", viewModel.uiState.value.messages.first().content)

        val assistantMessage = viewModel.uiState.value.messages[1]
        assertTrue(assistantMessage.content.contains("Glia AI Assistant"))
        assertNotNull(assistantMessage.thinking)
    }

    @Test
    fun testProviderTypesCanBeInstantiated() {
        assertEquals(3, ProviderType.values().size)

        // Mock
        val mockProvider: AgentProvider = MockEchoAgentProvider()
        assertNotNull(mockProvider)

        // ZEA Phoenix
        val phoenixProvider: AgentProvider = ZeaPhoenixProvider(
            gatewayUrl = "wss://api.zea.cl",
            appId = "demo-app",
            userId = "user-123",
            token = "token-xyz"
        )
        assertNotNull(phoenixProvider)

        // SSE
        val sseProvider: AgentProvider = SseAgentProvider(
            endpointUrl = "https://agent.example.com/v1/chat-messages",
            token = "secret"
        )
        assertNotNull(sseProvider)
    }
}
