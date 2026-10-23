package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaBackendType
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.providers.phoenix.ZeaPhoenixProvider
import cl.zea.glia.core.providers.sse.SseAgentProvider

/**
 * Universal Glia AI Agent Client.
 *
 * Implements composition over inheritance, delegating all session, streaming,
 * and transport operations to a pluggable [AgentProvider] (e.g. [ZeaPhoenixProvider],
 * [SseAgentProvider], or custom multi-cloud agent adapters).
 */
class GliaClient private constructor(
    private val delegate: AgentProvider
) : AgentProvider by delegate {

    companion object {
        /**
         * Primary factory creating a [GliaClient] directly from any [AgentProvider].
         *
         * Use this method to connect to custom backends, third-party agents,
         * or alternate transport layers.
         *
         * Example:
         * ```kotlin
         * val client = GliaClient.create(
         *     ZeaPhoenixProvider("wss://api.zea.cl", "agent-1", "user-1", token = "...")
         * )
         * ```
         */
        fun create(provider: AgentProvider): GliaClient = GliaClient(provider)

        /**
         * Backward-compatible factory creating a [GliaClient] from [GliaOptions].
         * Automatically selects between [ZeaPhoenixProvider] and [SseAgentProvider]
         * based on [GliaOptions.backendType].
         */
        operator fun invoke(
            options: GliaOptions,
            connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
        ): GliaClient {
            val provider: AgentProvider = when (options.backendType) {
                GliaBackendType.PHOENIX -> ZeaPhoenixProvider(options, connectionFactory)
                GliaBackendType.SSE -> SseAgentProvider(options)
            }
            return GliaClient(provider)
        }

        /**
         * Convenience constructor taking individual parameters.
         */
        operator fun invoke(
            gatewayUrl: String,
            appId: String,
            userId: String,
            token: String? = null,
            systemPrompt: String? = null,
            timeoutMs: Long = 60_000L,
            autoReconnect: Boolean = true,
            backendType: GliaBackendType = GliaBackendType.PHOENIX,
            connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
        ): GliaClient = invoke(
            GliaOptions(
                gatewayUrl = gatewayUrl,
                appId = appId,
                userId = userId,
                token = token,
                systemPrompt = systemPrompt,
                timeoutMs = timeoutMs,
                autoReconnect = autoReconnect,
                backendType = backendType
            ),
            connectionFactory
        )
    }
}

/**
 * Backward-compatible Phoenix Channels v2 agent client pointing to [ZeaPhoenixProvider].
 */
open class PhoenixAgentClient(
    options: GliaOptions,
    connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
) : ZeaPhoenixProvider(options, connectionFactory)
