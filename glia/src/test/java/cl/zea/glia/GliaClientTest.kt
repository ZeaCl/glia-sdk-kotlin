package cl.zea.glia

import cl.zea.glia.core.client.GliaClient
import cl.zea.glia.core.models.GliaException
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GliaClientTest {

    @Test
    fun testGliaOptionsTopicAndUrl() {
        val options = GliaOptions(
            gatewayUrl = "http://localhost:4003",
            appId = "nutrisnaps",
            userId = "user_123",
            token = "jwt_token"
        )
        assertEquals("session:nutrisnaps:user_123", options.topic)
        assertTrue(options.wsUrl.startsWith("ws://localhost:4003/socket/websocket"))
        assertTrue(options.wsUrl.contains("vsn=2.0.0"))
        assertFalse(options.wsUrl.contains("token="))
        assertEquals("Bearer jwt_token", options.effectiveHeaders["Authorization"])

        val secureOptions = GliaOptions(
            gatewayUrl = "wss://glia.nutrisnaps.cl",
            appId = "nutrisnaps",
            userId = "user_123"
        )
        assertTrue(secureOptions.wsUrl.startsWith("wss://glia.nutrisnaps.cl/socket/websocket"))
    }

    @Test
    fun testToolDefinitionSerialization() {
        val params = buildJsonObject {
            put("type", "object")
        }
        val tool = GliaToolDefinition(
            name = "calculate_macro",
            description = "Calcula macros de una comida",
            parameters = params,
            webhookUrl = "https://api.example.com/macros"
        )

        assertEquals("calculate_macro", tool.name)
        assertEquals("https://api.example.com/macros", tool.webhookUrl)
    }

    @Test
    fun testSuccessfulConnectWithJoinReply() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val options = GliaOptions(
            gatewayUrl = "ws://localhost:4003",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 2_000L
        )

        val client = GliaClient(
            options = options,
            connectionFactory = { _, _ -> mockConn }
        )

        // Simular respuesta del servidor phx_reply cuando llegue el join frame
        val replyJob = launch(Dispatchers.IO) {
            delay(50)
            val replyJson = "[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]"
            mockConn.pushIncoming(replyJson)
        }

        client.connect()

        assertTrue(client.isConnected.value)
        assertFalse(mockConn.sentMessages.isEmpty())
        replyJob.join()

        client.disconnect()
    }

    @Test
    fun testConnectFailureWithJoinError() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val options = GliaOptions(
            gatewayUrl = "ws://localhost:4003",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 2_000L
        )

        val client = GliaClient(
            options = options,
            connectionFactory = { _, _ -> mockConn }
        )

        val replyJob = launch(Dispatchers.IO) {
            delay(50)
            val replyJson = "[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"error\",\"response\":{\"reason\":\"unauthorized\"}}]"
            mockConn.pushIncoming(replyJson)
        }

        try {
            client.connect()
            fail("Debió fallar con GliaException.JoinFailed")
        } catch (e: GliaException.JoinFailed) {
            assertEquals("unauthorized", e.reason)
        }

        assertFalse(client.isConnected.value)
        replyJob.join()
    }

    @Test
    fun testVoluntaryDisconnectDoesNotEmitError() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val options = GliaOptions(
            gatewayUrl = "ws://localhost:4003",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 2_000L
        )

        val client = GliaClient(
            options = options,
            connectionFactory = { _, _ -> mockConn }
        )

        launch(Dispatchers.IO) {
            delay(30)
            val replyJson = "[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]"
            mockConn.pushIncoming(replyJson)
        }

        client.connect()
        assertTrue(client.isConnected.value)

        var hasError = false
        val eventJob = launch(Dispatchers.IO) {
            client.events.collect {
                if (it is GliaStreamEvent.Error) {
                    hasError = true
                }
            }
        }

        client.disconnect()
        delay(50)
        eventJob.cancel()

        assertFalse(hasError)
        assertFalse(client.isConnected.value)
    }

    @Test
    fun testStreamingEventsParsing() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val options = GliaOptions(
            gatewayUrl = "ws://localhost:4003",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 2_000L
        )

        val client = GliaClient(
            options = options,
            connectionFactory = { _, _ -> mockConn }
        )

        launch(Dispatchers.IO) {
            delay(30)
            mockConn.pushIncoming("[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]")
        }

        client.connect()

        val received = mutableListOf<GliaStreamEvent>()
        val collectorJob = launch(Dispatchers.IO) {
            client.events.collect { event ->
                received.add(event)
            }
        }

        delay(30)
        mockConn.pushIncoming("[null,\"2\",\"session:app1:usr1\",\"thinking_delta\",{\"content\":\"Analizando...\"}]")
        mockConn.pushIncoming("[null,\"3\",\"session:app1:usr1\",\"message_delta\",{\"content\":\"Hola \"}]")
        mockConn.pushIncoming("[null,\"4\",\"session:app1:usr1\",\"message_delta\",{\"content\":\"mundo\"}]")
        mockConn.pushIncoming("[null,\"5\",\"session:app1:usr1\",\"tool_call\",{\"name\":\"search\",\"args\":{}}]")
        mockConn.pushIncoming("[null,\"6\",\"session:app1:usr1\",\"tool_result\",{\"name\":\"search\",\"result\":\"ok\"}]")
        mockConn.pushIncoming("[null,\"7\",\"session:app1:usr1\",\"done\",{\"text\":\"Hola mundo\"}]")

        delay(100)
        collectorJob.cancel()

        assertEquals(6, received.size)
        assertTrue(received[0] is GliaStreamEvent.ThinkingDelta)
        assertEquals("Analizando...", (received[0] as GliaStreamEvent.ThinkingDelta).content)
        assertTrue(received[1] is GliaStreamEvent.MessageDelta)
        assertEquals("Hola ", (received[1] as GliaStreamEvent.MessageDelta).content)
        assertTrue(received[2] is GliaStreamEvent.MessageDelta)
        assertEquals("mundo", (received[2] as GliaStreamEvent.MessageDelta).content)
        assertTrue(received[3] is GliaStreamEvent.ToolCall)
        assertEquals("search", (received[3] as GliaStreamEvent.ToolCall).name)
        assertTrue(received[4] is GliaStreamEvent.ToolResult)
        assertEquals("search", (received[4] as GliaStreamEvent.ToolResult).name)
        assertTrue(received[5] is GliaStreamEvent.Done)
        assertEquals("Hola mundo", (received[5] as GliaStreamEvent.Done).fullMessage)

        client.disconnect()
    }

    @Test
    fun testMalformedJsonEmitsErrorEvent() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val options = GliaOptions(
            gatewayUrl = "ws://localhost:4003",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 2_000L
        )

        val client = GliaClient(
            options = options,
            connectionFactory = { _, _ -> mockConn }
        )

        launch(Dispatchers.IO) {
            delay(30)
            mockConn.pushIncoming("[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]")
        }

        client.connect()

        val errors = mutableListOf<GliaStreamEvent.Error>()
        val collectorJob = launch(Dispatchers.IO) {
            client.events.collect { event ->
                if (event is GliaStreamEvent.Error) {
                    errors.add(event)
                }
            }
        }

        delay(30)
        // Enviar JSON inválido / malformado
        mockConn.pushIncoming("ESTO_NO_ES_UN_JSON_VALIDO")

        delay(100)
        collectorJob.cancel()

        assertTrue(errors.isNotEmpty())
        assertTrue(errors[0].message.contains("inválido") || errors[0].message.contains("Error"))

        client.disconnect()
    }

    @Test
    fun testDelegationToSseBackend() = runBlocking {
        val options = GliaOptions(
            gatewayUrl = "https://soma.zea.cl",
            appId = "nutrisnaps",
            userId = "usr1",
            backendType = cl.zea.glia.core.models.GliaBackendType.SSE
        )

        val client = GliaClient(options)
        client.connect()
        assertTrue(client.isConnected.value)
        client.disconnect()
        assertFalse(client.isConnected.value)
    }
}
