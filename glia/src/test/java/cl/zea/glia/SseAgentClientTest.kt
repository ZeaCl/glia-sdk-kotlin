package cl.zea.glia

import cl.zea.glia.core.client.SseAgentClient
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseAgentClientTest {

    @Test
    fun testSseDifyStreamParsing() = runBlocking {
        val ssePayload = """
            event: message
            data: {"event": "message", "answer": "Hola "}

            event: agent_thought
            data: {"event": "agent_thought", "thought": "Pensando..."}

            event: message
            data: {"event": "message", "answer": "mundo"}

            data: [DONE]
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = ssePayload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }

        val client = SseAgentClient(
            options = GliaOptions(
                gatewayUrl = "https://api.dify.ai/v1/chat-messages",
                appId = "app1",
                userId = "usr1",
                token = "app-token"
            ),
            httpClient = HttpClient(mockEngine)
        )

        client.connect()
        assertTrue(client.isConnected.value)

        val events = mutableListOf<GliaStreamEvent>()
        val job = launch {
            client.events.collect {
                events.add(it)
            }
        }

        client.send("Hola")
        delay(150)
        job.cancel()

        val deltas = events.filterIsInstance<GliaStreamEvent.MessageDelta>()
        val thoughts = events.filterIsInstance<GliaStreamEvent.ThinkingDelta>()
        val dones = events.filterIsInstance<GliaStreamEvent.Done>()

        assertEquals(2, deltas.size)
        assertEquals("Hola ", deltas[0].content)
        assertEquals("mundo", deltas[1].content)

        assertEquals(1, thoughts.size)
        assertEquals("Pensando...", thoughts[0].content)

        assertTrue(dones.isNotEmpty())
        assertEquals("Hola mundo", dones[0].fullMessage)

        client.disconnect()
    }
}
