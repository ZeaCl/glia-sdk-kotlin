package cl.zea.glia

import cl.zea.glia.core.client.WebSocketConnectionFactory
import cl.zea.glia.core.models.GliaChatMessage
import cl.zea.glia.core.models.GliaHistoryResponse
import cl.zea.glia.core.models.GliaMessage
import cl.zea.glia.core.models.GliaMessageRole
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.providers.phoenix.ZeaPhoenixProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GliaHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testCanonicalHostAndRestUrlResolution() {
        // Canonical host wss://glia.zea.cl/socket
        val canonicalOptions = GliaOptions(
            gatewayUrl = "wss://glia.zea.cl/socket",
            appId = "demo_app",
            userId = "user_1"
        )
        assertEquals("wss://glia.zea.cl/socket/websocket?vsn=2.0.0", canonicalOptions.wsUrl)
        assertEquals("https://glia.zea.cl", canonicalOptions.httpUrl)
        assertEquals("session:demo_app:user_1", canonicalOptions.topic)
        assertEquals("demo_app:user_1", canonicalOptions.sessionId)

        // Custom sessionId
        val customSessionOptions = GliaOptions(
            gatewayUrl = "https://glia.zea.cl",
            appId = "demo_app",
            userId = "user_1",
            sessionId = "conv-uuid-123"
        )
        assertEquals("wss://glia.zea.cl/socket/websocket?vsn=2.0.0", customSessionOptions.wsUrl)
        assertEquals("https://glia.zea.cl", customSessionOptions.httpUrl)
        assertEquals("session:conv-uuid-123", customSessionOptions.topic)
        assertEquals("conv-uuid-123", customSessionOptions.sessionId)

        // Localhost HTTP/WS host
        val localOptions = GliaOptions(
            gatewayUrl = "http://localhost:4000/socket",
            appId = "app",
            userId = "u1"
        )
        assertEquals("ws://localhost:4000/socket/websocket?vsn=2.0.0", localOptions.wsUrl)
        assertEquals("http://localhost:4000", localOptions.httpUrl)
    }

    @Test
    fun testGliaMessageAndHistoryResponseSerialization() {
        val rawJson = """
            {
              "messages": [
                {
                  "id": "msg-1",
                  "role": "user",
                  "content": "¿Cómo estás?",
                  "sequence": 1,
                  "inserted_at": "2026-10-23T18:00:00Z"
                },
                {
                  "id": "msg-2",
                  "role": "assistant",
                  "content": "¡Hola! Todo excelente.",
                  "sequence": 2,
                  "inserted_at": "2026-10-23T18:00:05Z"
                }
              ],
              "has_more": false,
              "session_id": "session:app1:u1"
            }
        """.trimIndent()

        val response = json.decodeFromString<GliaHistoryResponse>(rawJson)
        assertEquals(2, response.messages.size)
        assertFalse(response.hasMore)
        assertEquals("session:app1:u1", response.sessionId)

        val first = response.messages[0]
        assertEquals("msg-1", first.id)
        assertEquals(GliaMessageRole.USER, first.role)
        assertEquals("¿Cómo estás?", first.content)
        assertEquals(1, first.sequence)
        assertEquals("2026-10-23T18:00:00Z", first.insertedAt)

        val second = response.messages[1]
        assertEquals("msg-2", second.id)
        assertEquals(GliaMessageRole.ASSISTANT, second.role)
        assertEquals(2, second.sequence)

        // Test typealias GliaMessage
        val gliaMsg: GliaMessage = first
        assertEquals("msg-1", gliaMsg.id)

        // Re-encoding should produce valid JSON
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<GliaHistoryResponse>(encoded)
        assertEquals(response.messages.size, decoded.messages.size)
        assertEquals(response.messages[0].id, decoded.messages[0].id)
    }

    @Test
    fun testRoleSerializerCaseTolerance() {
        val userUpperJson = """{"role":"USER","content":"Hi"}"""
        val msg1 = json.decodeFromString<GliaChatMessage>(userUpperJson)
        assertEquals(GliaMessageRole.USER, msg1.role)

        val assistantUpperJson = """{"role":"ASSISTANT","content":"Hello"}"""
        val msg2 = json.decodeFromString<GliaChatMessage>(assistantUpperJson)
        assertEquals(GliaMessageRole.ASSISTANT, msg2.role)

        val userLowerJson = """{"role":"user","content":"Hi"}"""
        val msg3 = json.decodeFromString<GliaChatMessage>(userLowerJson)
        assertEquals(GliaMessageRole.USER, msg3.role)
    }

    @Test
    fun testFetchHistoryViaWebSocket() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val factory: WebSocketConnectionFactory = { _, _ -> mockConn }

        val options = GliaOptions(
            gatewayUrl = "wss://glia.zea.cl/socket",
            appId = "app1",
            userId = "usr1",
            token = "test_token"
        )
        val provider = ZeaPhoenixProvider(options, connectionFactory = factory)

        // Connect first (mock join reply)
        val joinJob = launch {
            mockConn.pushIncoming("[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]")
        }
        provider.connect()
        joinJob.join()
        assertTrue(provider.isConnected.value)

        // Push get_history reply asynchronously
        launch {
            delay(50)
            val replyJson = """
                ["2","2","session:app1:usr1","phx_reply",{
                    "status":"ok",
                    "response":{
                        "messages":[
                            {
                                "id":"hist-1",
                                "role":"user",
                                "content":"Hola desde el pasado",
                                "sequence":10,
                                "inserted_at":"2026-10-23T18:30:00Z"
                            }
                        ],
                        "has_more":true
                    }
                }]
            """.trimIndent()
            mockConn.pushIncoming(replyJson)
        }

        val result = provider.fetchHistory(limit = 20, beforeSequence = null)
        assertTrue(result.isSuccess)

        val history = result.getOrThrow()
        assertEquals(1, history.messages.size)
        assertTrue(history.hasMore)
        assertEquals("hist-1", history.messages[0].id)
        assertEquals(10, history.messages[0].sequence)

        // Verify outgoing WebSocket frame contains get_history
        val historySentFrame = mockConn.sentMessages.find { it.contains("get_history") }
        assertNotNull(historySentFrame)
        assertTrue(historySentFrame!!.contains("\"limit\":20"))

        provider.disconnect()
    }

    @Test
    fun testFetchHistoryViaRest() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertEquals("/api/conversations/app1:usr1/messages", request.url.encodedPath)
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("Bearer rest_token", request.headers["Authorization"])

            respond(
                content = """
                    {
                      "messages": [
                        {
                          "id": "rest-1",
                          "role": "assistant",
                          "content": "Respuesta vía REST",
                          "sequence": 5,
                          "inserted_at": "2026-10-23T18:15:00Z"
                        }
                      ],
                      "has_more": false,
                      "session_id": "app1:usr1"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockHttpClient = HttpClient(mockEngine)

        val options = GliaOptions(
            gatewayUrl = "https://glia.zea.cl",
            appId = "app1",
            userId = "usr1",
            token = "rest_token"
        )
        val provider = ZeaPhoenixProvider(
            options = options,
            httpClient = mockHttpClient
        )

        // Without connecting WS, fetchHistory falls back directly to REST
        val result = provider.fetchHistory(limit = 20)
        assertTrue(result.isSuccess)

        val history = result.getOrThrow()
        assertEquals(1, history.messages.size)
        assertEquals("rest-1", history.messages[0].id)
        assertEquals("Respuesta vía REST", history.messages[0].content)
        assertEquals(5, history.messages[0].sequence)
        assertFalse(history.hasMore)
        assertEquals("app1:usr1", history.sessionId)
    }

    @Test
    fun testFetchHistoryFallsBackToRestWhenWebSocketFails() = runBlocking {
        val mockConn = MockWebSocketConnection()
        val factory: WebSocketConnectionFactory = { _, _ -> mockConn }

        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                      "messages": [
                        {
                          "id": "fallback-1",
                          "role": "assistant",
                          "content": "Recuperado por fallback REST",
                          "sequence": 1
                        }
                      ],
                      "has_more": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockHttpClient = HttpClient(mockEngine)

        val options = GliaOptions(
            gatewayUrl = "wss://glia.zea.cl/socket",
            appId = "app1",
            userId = "usr1",
            timeoutMs = 500L
        )
        val provider = ZeaPhoenixProvider(
            options = options,
            connectionFactory = factory,
            httpClient = mockHttpClient
        )

        // Connect WS
        launch {
            mockConn.pushIncoming("[\"1\",\"1\",\"session:app1:usr1\",\"phx_reply\",{\"status\":\"ok\",\"response\":{}}]")
        }
        provider.connect()

        // WS get_history will time out because no reply is pushed on WS, so it falls back to REST!
        val result = provider.fetchHistory(limit = 20)
        assertTrue(result.isSuccess)
        val history = result.getOrThrow()
        assertEquals(1, history.messages.size)
        assertEquals("fallback-1", history.messages[0].id)

        provider.disconnect()
    }
}
