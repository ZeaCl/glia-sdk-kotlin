package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaBackendType
import cl.zea.glia.core.models.GliaException
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contrato universal agnóstico para cualquier cliente de agente en Glia.
 */
interface GliaClientProtocol : Closeable {
    val isConnected: StateFlow<Boolean>
    val events: SharedFlow<GliaStreamEvent>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(prompt: String, systemPrompt: String? = null, tools: List<GliaToolDefinition> = emptyList())
}

/**
 * Implementación oficial para el runtime de Glia basado en Phoenix Channels v2 (Elixir).
 */
open class PhoenixAgentClient(
    val options: GliaOptions,
    private val connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
) : GliaClientProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: WebSocketConnection? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var isVoluntaryDisconnect = false
    private var reconnectAttempts = 0

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    private val messageRef = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingReplies = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val connectMutex = Mutex()

    override suspend fun connect() {
        connectMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            isVoluntaryDisconnect = false

            if (_isConnected.value && connection != null) return

            // Limpiar conexión previa si existía
            cancelInternalConnection()

            try {
                val conn = connectionFactory(options.wsUrl, options.effectiveHeaders)
                this.connection = conn

                startReceiveLoop(conn)
                joinChannel(conn)

                reconnectAttempts = 0
                startHeartbeat(conn)
            } catch (e: Exception) {
                cancelInternalConnection()
                throw e
            }
        }
    }

    private suspend fun joinChannel(conn: WebSocketConnection) {
        val ref = messageRef.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pendingReplies[ref] = deferred

        val joinFrame = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(ref))
            add(kotlinx.serialization.json.JsonPrimitive(ref))
            add(kotlinx.serialization.json.JsonPrimitive(options.topic))
            add(kotlinx.serialization.json.JsonPrimitive("phx_join"))
            add(buildJsonObject {})
        }.toString()

        conn.send(joinFrame)

        try {
            withTimeout(options.timeoutMs) {
                deferred.await()
            }
            _isConnected.value = true
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingReplies.remove(ref)
            throw GliaException.ConnectionTimeout()
        } catch (e: Exception) {
            pendingReplies.remove(ref)
            throw e
        }
    }

    private fun startHeartbeat(conn: WebSocketConnection) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(30_000L)
                val ref = messageRef.getAndIncrement().toString()
                val heartbeat = buildJsonArray {
                    add(kotlinx.serialization.json.JsonNull)
                    add(kotlinx.serialization.json.JsonPrimitive(ref))
                    add(kotlinx.serialization.json.JsonPrimitive("phoenix"))
                    add(kotlinx.serialization.json.JsonPrimitive("heartbeat"))
                    add(buildJsonObject {})
                }.toString()

                try {
                    conn.send(heartbeat)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun startReceiveLoop(conn: WebSocketConnection) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            while (isActive) {
                try {
                    val text = conn.receive()
                    handleIncomingMessage(text)
                } catch (e: Exception) {
                    if (isVoluntaryDisconnect || !isActive) {
                        break
                    }
                    handleUnexpectedDisconnection(e)
                    break
                }
            }
        }
    }

    private fun handleUnexpectedDisconnection(error: Exception) {
        cancelInternalConnection()
        scope.launch {
            _events.emit(GliaStreamEvent.Error("Error de WebSocket: ${error.localizedMessage ?: "desconexión"}"))
        }

        // Falla continuations pendientes
        for ((_, deferred) in pendingReplies) {
            deferred.completeExceptionally(GliaException.ConnectionClosed(error.localizedMessage ?: "desconexión"))
        }
        pendingReplies.clear()

        // Reconexión automática con backoff exponencial
        if (options.autoReconnect && reconnectAttempts < options.maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= options.maxReconnectAttempts) return
        reconnectAttempts++
        val attempt = reconnectAttempts
        val delayFactor = 1L shl (attempt - 1).coerceAtMost(30)
        val delayMs = (options.reconnectBaseDelayMs * delayFactor).coerceAtMost(options.reconnectMaxDelayMs)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _events.emit(GliaStreamEvent.Reconnecting(attempt, delayMs))
            delay(delayMs)
            if (!isActive) return@launch
            try {
                connect()
            } catch (_: Exception) {
                if (isActive && !isVoluntaryDisconnect && reconnectAttempts < options.maxReconnectAttempts) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun cancelInternalConnection() {
        receiveJob?.cancel()
        receiveJob = null

        heartbeatJob?.cancel()
        heartbeatJob = null

        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null

        _isConnected.value = false
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
        val conn = connection
        if (conn == null || !_isConnected.value) {
            throw GliaException.NotConnected()
        }

        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        val ref = messageRef.getAndIncrement().toString()
        val payload = buildJsonObject {
            put("message", trimmed)
            val sp = systemPrompt ?: options.systemPrompt
            if (!sp.isNullOrBlank()) {
                put("system_prompt", sp)
            }
            if (tools.isNotEmpty()) {
                put("tools", json.parseToJsonElement(json.encodeToString(tools)))
            }
        }

        val frame = buildJsonArray {
            add(kotlinx.serialization.json.JsonNull)
            add(kotlinx.serialization.json.JsonPrimitive(ref))
            add(kotlinx.serialization.json.JsonPrimitive(options.topic))
            add(kotlinx.serialization.json.JsonPrimitive("run"))
            add(payload)
        }.toString()

        conn.send(frame)
    }

    private suspend fun handleIncomingMessage(text: String) {
        try {
            val element = json.parseToJsonElement(text)
            val root = element as? JsonArray
            if (root == null || root.size < 5) {
                _events.emit(GliaStreamEvent.Error("Formato de mensaje inválido del servidor: se esperaba un array de 5 elementos"))
                return
            }

            val ref = root[1].jsonPrimitive.content.takeIf { it != "null" }
            val event = root[3].jsonPrimitive.content
            val payload = root[4] as? JsonObject
            if (payload == null) {
                _events.emit(GliaStreamEvent.Error("Payload de mensaje inválido del servidor"))
                return
            }

            when (event) {
                "phx_reply" -> {
                    if (ref != null) {
                        val deferred = pendingReplies.remove(ref)
                        if (deferred != null) {
                            val status = payload["status"]?.jsonPrimitive?.content ?: ""
                            if (status == "ok") {
                                val response = payload["response"] as? JsonObject ?: buildJsonObject {}
                                deferred.complete(response)
                            } else {
                                val reason = (payload["response"] as? JsonObject)?.get("reason")?.jsonPrimitive?.content
                                    ?: (payload["response"] as? JsonObject)?.get("message")?.jsonPrimitive?.content
                                    ?: status
                                deferred.completeExceptionally(GliaException.JoinFailed(reason))
                            }
                        }
                    }
                }
                "status" -> {
                    val status = payload["status"]?.jsonPrimitive?.content ?: ""
                    _events.emit(GliaStreamEvent.Status(status))
                }
                "thinking_delta" -> {
                    val content = payload["content"]?.jsonPrimitive?.content ?: ""
                    _events.emit(GliaStreamEvent.ThinkingDelta(content))
                }
                "message_delta" -> {
                    val content = payload["content"]?.jsonPrimitive?.content ?: ""
                    _events.emit(GliaStreamEvent.MessageDelta(content))
                }
                "tool_call" -> {
                    val name = payload["name"]?.jsonPrimitive?.content ?: ""
                    val args = payload["args"]?.jsonObject ?: emptyMap()
                    _events.emit(GliaStreamEvent.ToolCall(name, args))
                }
                "tool_result" -> {
                    val name = payload["name"]?.jsonPrimitive?.content ?: ""
                    val result = payload["result"] ?: kotlinx.serialization.json.JsonNull
                    _events.emit(GliaStreamEvent.ToolResult(name, result))
                }
                "done" -> {
                    // Paridad de contrato: leer payload["text"] (backend Elixir actual) y payload["full_message"] (legado)
                    val fullMsg = payload["text"]?.jsonPrimitive?.content
                        ?: payload["full_message"]?.jsonPrimitive?.content
                    _events.emit(GliaStreamEvent.Done(fullMsg))
                }
                "error" -> {
                    val msg = payload["message"]?.jsonPrimitive?.content ?: "Error desconocido"
                    _events.emit(GliaStreamEvent.Error(msg))
                }
            }
        } catch (e: Exception) {
            _events.emit(GliaStreamEvent.Error("Error al procesar mensaje del servidor: ${e.localizedMessage ?: e.message}"))
        }
    }

    override suspend fun disconnect() {
        isVoluntaryDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null

        cancelInternalConnection()

        // Cancelar continuations pendientes sin emitir error
        for ((_, deferred) in pendingReplies) {
            deferred.completeExceptionally(GliaException.ConnectionClosed("Desconexión voluntaria"))
        }
        pendingReplies.clear()
    }

    override fun close() {
        scope.cancel()
    }
}

/**
 * Cliente principal de Glia. Implementa composición sobre herencia delegando
 * en la implementación adecuada según [GliaOptions.backendType].
 */
class GliaClient private constructor(
    private val delegate: GliaClientProtocol
) : GliaClientProtocol by delegate {

    companion object {
        operator fun invoke(
            options: GliaOptions,
            connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
        ): GliaClient {
            val delegate = when (options.backendType) {
                GliaBackendType.PHOENIX -> PhoenixAgentClient(options, connectionFactory)
                GliaBackendType.SSE -> SseAgentClient(options)
            }
            return GliaClient(delegate)
        }

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

        fun create(
            options: GliaOptions,
            connectionFactory: WebSocketConnectionFactory = defaultWebSocketConnectionFactory
        ): GliaClientProtocol = invoke(options, connectionFactory)
    }
}
