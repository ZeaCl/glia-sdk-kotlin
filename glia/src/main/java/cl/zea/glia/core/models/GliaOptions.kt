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
    val backendType: GliaBackendType = GliaBackendType.PHOENIX,
    val sendTokenInQuery: Boolean = false,
    val sessionId: String = "$appId:$userId"
) {
    val topic: String
        get() = "session:$sessionId"

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

            val baseAndQuery = clean.split("?", limit = 2)
            var path = baseAndQuery[0].trimEnd('/')
            val existingQuery = if (baseAndQuery.size > 1) baseAndQuery[1] else ""

            if (path.endsWith("/socket/websocket")) {
                // already has full websocket path
            } else if (path.endsWith("/socket")) {
                path = "$path/websocket"
            } else {
                path = "$path/socket/websocket"
            }

            val finalScheme = if (scheme.isNotEmpty()) scheme else "wss://"
            val vsnParam = if (existingQuery.contains("vsn=")) "" else "vsn=2.0.0"
            val tokenParam = if (sendTokenInQuery && !token.isNullOrBlank() && !existingQuery.contains("token=")) {
                val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
                "token=$encodedToken"
            } else ""

            val queryParts = listOf(vsnParam, tokenParam, existingQuery).filter { it.isNotBlank() }
            val queryString = if (queryParts.isNotEmpty()) "?" + queryParts.joinToString("&") else ""

            return "$finalScheme$path$queryString"
        }

    val httpUrl: String
        get() {
            var clean = gatewayUrl.trimEnd('/')
            val isHttp = clean.startsWith("http://") || clean.startsWith("ws://")
            val scheme = if (isHttp) "http://" else "https://"
            clean = clean.removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")

            val baseAndQuery = clean.split("?", limit = 2)
            var path = baseAndQuery[0].trimEnd('/')
            if (path.endsWith("/socket/websocket")) {
                path = path.removeSuffix("/socket/websocket")
            } else if (path.endsWith("/socket")) {
                path = path.removeSuffix("/socket")
            }
            return "$scheme$path".trimEnd('/')
        }
}
