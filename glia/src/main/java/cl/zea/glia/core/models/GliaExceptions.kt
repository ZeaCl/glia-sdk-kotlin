package cl.zea.glia.core.models

/**
 * Hierarchy of typed exceptions emitted by the Glia SDK.
 */
sealed class GliaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidURL(val url: String) : GliaException("Invalid Glia gateway URL: $url")
    class JoinFailed(val reason: String) : GliaException("Failed to join Phoenix channel: $reason")
    class ConnectionTimeout(message: String = "Connection or channel join timed out") : GliaException(message)
    class NotConnected(message: String = "No active connection to the Glia gateway") : GliaException(message)
    class ConnectionClosed(val reason: String) : GliaException("Connection closed: $reason")
    class ServerError(val msg: String) : GliaException("Server error: $msg")
}
