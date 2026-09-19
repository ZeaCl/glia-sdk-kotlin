package cl.zea.glia

import cl.zea.glia.core.client.WebSocketConnection
import kotlinx.coroutines.channels.Channel

class MockWebSocketConnection : WebSocketConnection {
    val sentMessages = mutableListOf<String>()
    val incomingChannel = Channel<String>(capacity = Channel.UNLIMITED)
    var isClosed = false
        private set

    override suspend fun send(text: String) {
        sentMessages.add(text)
    }

    override suspend fun receive(): String {
        return incomingChannel.receive()
    }

    override suspend fun close(reason: String) {
        isClosed = true
        incomingChannel.close()
    }

    override fun close() {
        isClosed = true
        incomingChannel.close()
    }

    fun pushIncoming(text: String) {
        incomingChannel.trySend(text)
    }
}
