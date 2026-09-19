package cl.zea.glia.core.models

/**
 * Jerarquía de excepciones tipadas emitidas por el Glia SDK.
 */
sealed class GliaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidURL(val url: String) : GliaException("URL inválida del gateway de Glia: $url")
    class JoinFailed(val reason: String) : GliaException("No se pudo unir al canal de Phoenix: $reason")
    class ConnectionTimeout(message: String = "Tiempo de espera agotado al conectar o unir canal") : GliaException(message)
    class NotConnected(message: String = "No hay una conexión activa con el gateway de Glia") : GliaException(message)
    class ConnectionClosed(val reason: String) : GliaException("Conexión cerrada: $reason")
    class ServerError(val msg: String) : GliaException("Error del servidor: $msg")
}
