package cl.zea.glia.core.models

/**
 * Tipo de backend para el runtime agéntico.
 */
enum class GliaBackendType {
    PHOENIX, // Glia Elixir runtime vía Phoenix Channels v2 (por defecto)
    SSE      // Agentes basados en Server-Sent Events (Soma Agent Hub, Dify.ai, LangGraph, OpenAI)
}

/**
 * Opciones de configuración agnósticas para GliaClient.
 */
data class GliaOptions(
    val gatewayUrl: String,
    val appId: String,
    val userId: String,
    val token: String? = null,
    val systemPrompt: String? = null,
    val timeoutMs: Long = 60_000L,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 30_000L,
    val headers: Map<String, String> = emptyMap(),
    val backendType: GliaBackendType = GliaBackendType.PHOENIX
) {
    val topic: String
        get() = "session:$appId:$userId"

    val wsUrl: String
        get() {
            var clean = gatewayUrl.trimEnd('/')
            val scheme = if (clean.startsWith("https://")) "wss://" else if (clean.startsWith("http://")) "ws://" else ""
            clean = clean.removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")

            if (!clean.endsWith("/socket/websocket")) {
                clean = "$clean/socket/websocket"
            }

            val finalScheme = if (scheme.isNotEmpty()) scheme else "ws://"
            val urlWithScheme = "$finalScheme$clean"

            val queryParams = mutableListOf("vsn=2.0.0")
            if (!token.isNullOrBlank()) {
                queryParams.add("token=$token")
            }

            return "$urlWithScheme?${queryParams.joinToString("&")}"
        }
}
