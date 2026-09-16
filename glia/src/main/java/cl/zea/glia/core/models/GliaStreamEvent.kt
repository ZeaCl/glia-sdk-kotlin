package cl.zea.glia.core.models

import kotlinx.serialization.json.JsonElement

/**
 * Eventos emitidos en tiempo real por el runtime de Glia a través de WebSocket.
 */
sealed class GliaStreamEvent {
    data class Status(val status: String) : GliaStreamEvent()
    data class ThinkingDelta(val content: String) : GliaStreamEvent()
    data class MessageDelta(val content: String) : GliaStreamEvent()
    data class ToolCall(val name: String, val args: Map<String, JsonElement>) : GliaStreamEvent()
    data class ToolResult(val name: String, val result: JsonElement) : GliaStreamEvent()
    data class Done(val fullMessage: String? = null) : GliaStreamEvent()
    data class Error(val message: String) : GliaStreamEvent()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : GliaStreamEvent()
}
