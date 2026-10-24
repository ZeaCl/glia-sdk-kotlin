package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaHistoryResponse
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * Universal agent provider contract for any agentic wire protocol or cloud runtime.
 * Implementations translate underlying transports (Phoenix Channels, SSE, WebSocket, REST)
 * into canonical [GliaStreamEvent] streams.
 */
interface AgentProvider : Closeable {
    /**
     * Real-time observable connection state.
     */
    val isConnected: StateFlow<Boolean>

    /**
     * Observable stream of real-time events emitted by the agent runtime
     * (e.g. thinkingDelta, messageDelta, toolCall, toolResult, done, error).
     */
    val events: SharedFlow<GliaStreamEvent>

    /**
     * Establishes connection / session with the agent runtime.
     */
    suspend fun connect()

    /**
     * Gracefully disconnects the current session.
     */
    suspend fun disconnect()

    /**
     * Sends a user prompt along with optional system instructions and dynamic tool definitions.
     */
    suspend fun send(
        prompt: String,
        systemPrompt: String? = null,
        tools: List<GliaToolDefinition> = emptyList()
    )

    /**
     * Fetches historical conversation messages.
     *
     * @param limit Maximum number of messages to return (default: 20).
     * @param beforeSequence Optional sequence number cursor for backward pagination.
     * @return Result containing [GliaHistoryResponse] or failure.
     */
    suspend fun fetchHistory(
        limit: Int = 20,
        beforeSequence: Int? = null
    ): Result<GliaHistoryResponse> = Result.failure(
        UnsupportedOperationException("fetchHistory is not supported by this provider")
    )
}

/**
 * Backward-compatible alias for [AgentProvider].
 */
typealias GliaClientProtocol = AgentProvider
