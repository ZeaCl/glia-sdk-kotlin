package cl.zea.glia.core.models

/**
 * Backend type for the agentic runtime.
 */
enum class GliaBackendType {
    PHOENIX, // Official Glia agentic runtime via Phoenix Channels v2 (default)
    SSE      // External agents based on Server-Sent Events (Dify.ai, LangGraph, OpenAI)
}

/**
 * Agnostic configuration options for GliaClient.
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

    val effectiveHeaders: Map<String, String>
        get() {
            val map = headers.toMutableMap()
            if (!token.isNullOrBlank() && !map.containsKey("Authorization")) {
                map["Authorization"] = "Bearer $token"
            }
            return map
        }

    val wsUrl: String
        get() {
            var clean = gatewayUrl.trimEnd('/')
            val scheme = if (clean.startsWith("https://") || clean.startsWith("wss://")) "wss://"
            else if (clean.startsWith("http://") || clean.startsWith("ws://")) "ws://"
            else ""
            clean = clean.removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")

            if (!clean.endsWith("/socket/websocket")) {
                clean = "$clean/socket/websocket"
            }

            val finalScheme = if (scheme.isNotEmpty()) scheme else "wss://"
            val urlWithScheme = "$finalScheme$clean"

            return "$urlWithScheme?vsn=2.0.0"
        }
}
