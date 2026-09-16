package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

interface GliaClientProtocol : Closeable {
    val isConnected: StateFlow<Boolean>
    val events: SharedFlow<GliaStreamEvent>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(prompt: String, systemPrompt: String? = null, tools: List<GliaToolDefinition> = emptyList())
}

class GliaClient(
    val options: GliaOptions,
    private val httpClient: HttpClient = HttpClient(OkHttp) { install(WebSockets) }
) : GliaClientProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var webSocketSession: DefaultClientWebSocketSession? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    private val messageRef = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect() {
        if (_isConnected.value) return

        connectionJob?.cancel()
        connectionJob = scope.launch {
            var attempt = 0
            var delayMs = options.reconnectBaseDelayMs

            while (isActive) {
                try {
                    openSession()
                    attempt = 0
                    delayMs = options.reconnectBaseDelayMs
                } catch (e: Exception) {
                    _isConnected.value = false
                    if (!options.autoReconnect || (options.maxReconnectAttempts > 0 && attempt >= options.maxReconnectAttempts)) {
                        _events.emit(GliaStreamEvent.Error("Conexión fallida: ${e.localizedMessage}"))
                        break
                    }

                    attempt++
                    _events.emit(GliaStreamEvent.Reconnecting(attempt, delayMs))
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(options.reconnectMaxDelayMs)
                }
            }
        }
    }

    private suspend fun openSession() {
        try {
            httpClient.webSocket(urlString = options.wsUrl) {
                webSocketSession = this
                _isConnected.value = true

                // Phoenix Channel join frame: [joinRef, ref, topic, event, payload]
                val ref = messageRef.getAndIncrement().toString()
                val joinFrame = buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(ref))
                    add(kotlinx.serialization.json.JsonPrimitive(ref))
                    add(kotlinx.serialization.json.JsonPrimitive(options.topic))
                    add(kotlinx.serialization.json.JsonPrimitive("phx_join"))
                    add(buildJsonObject {})
                }.toString()

                send(Frame.Text(joinFrame))
                startHeartbeat()

                // Incoming loop
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
            }
        } finally {
            _isConnected.value = false
            heartbeatJob?.cancel()
            webSocketSession = null
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(30_000)
                val ref = messageRef.getAndIncrement().toString()
                val heartbeat = buildJsonArray {
                    add(kotlinx.serialization.json.JsonNull)
                    add(kotlinx.serialization.json.JsonPrimitive(ref))
                    add(kotlinx.serialization.json.JsonPrimitive("phoenix"))
                    add(kotlinx.serialization.json.JsonPrimitive("heartbeat"))
                    add(buildJsonObject {})
                }.toString()

                try {
                    webSocketSession?.send(Frame.Text(heartbeat))
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val root = json.parseToJsonElement(text) as? JsonArray ?: return
            if (root.size < 5) return

            val event = root[3].jsonPrimitive.content
            val payload = root[4] as? JsonObject ?: return

            when (event) {
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
                    val fullMsg = payload["full_message"]?.jsonPrimitive?.content
                    _events.emit(GliaStreamEvent.Done(fullMsg))
                }
                "error" -> {
                    val msg = payload["message"]?.jsonPrimitive?.content ?: "Error desconocido"
                    _events.emit(GliaStreamEvent.Error(msg))
                }
            }
        } catch (_: Exception) {}
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
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

        webSocketSession?.send(Frame.Text(frame))
    }

    override suspend fun disconnect() {
        connectionJob?.cancel()
        heartbeatJob?.cancel()
        connectionJob = null
        heartbeatJob = null
        try {
            webSocketSession?.close()
        } catch (_: Exception) {}
        webSocketSession = null
        _isConnected.value = false
    }

    override fun close() {
        scope.cancel()
    }
}
