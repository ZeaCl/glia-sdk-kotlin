package cl.zea.glia.core.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.io.Closeable

/**
 * Abstracción del transporte WebSocket para desacoplar GliaClient de Ktor
 * y permitir pruebas unitarias deterministas en memoria (DIP / Clean Architecture).
 */
interface WebSocketConnection : Closeable {
    suspend fun send(text: String)
    suspend fun receive(): String
    suspend fun close(reason: String = "Normal Closure")
}

typealias WebSocketConnectionFactory = suspend (url: String, headers: Map<String, String>) -> WebSocketConnection

/**
 * Implementación de producción respaldada por Ktor Client WebSocketSession.
 */
class KtorWebSocketConnection(
    private val session: DefaultClientWebSocketSession
) : WebSocketConnection {

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun receive(): String {
        while (true) {
            val frame = session.incoming.receive()
            if (frame is Frame.Text) {
                return frame.readText()
            }
        }
    }

    override suspend fun close(reason: String) {
        try {
            session.close()
        } catch (_: Exception) {}
    }

    override fun close() {
        try {
            session.outgoing.close()
        } catch (_: Exception) {}
    }
}

val defaultKtorWebSocketClient: HttpClient by lazy {
    HttpClient(OkHttp) {
        install(WebSockets)
    }
}

val defaultWebSocketConnectionFactory: WebSocketConnectionFactory = { url, headers ->
    val session = defaultKtorWebSocketClient.webSocketSession {
        url(url)
        headers {
            headers.forEach { (k, v) -> append(k, v) }
        }
    }
    KtorWebSocketConnection(session)
}
