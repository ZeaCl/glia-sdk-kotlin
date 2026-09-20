package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaException
import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Implementación de GliaClientProtocol para conectarse a plataformas agénticas externas
 * basadas en HTTP Server-Sent Events (SSE / text/event-stream), tales como:
 * - Dify.ai (/v1/chat-messages)
 * - LangGraph / LangChain Cloud
 * - OpenAI Assistants / Chat Completions con streaming
 */
class SseAgentClient(
    val options: GliaOptions,
    private val httpClient: HttpClient = HttpClient(OkHttp)
) : GliaClientProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamingJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _events = MutableSharedFlow<GliaStreamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<GliaStreamEvent> = _events.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect() {
        if (!options.gatewayUrl.startsWith("http://") && !options.gatewayUrl.startsWith("https://")) {
            throw GliaException.InvalidURL(options.gatewayUrl)
        }
        _isConnected.value = true
        _events.emit(GliaStreamEvent.Status("ready"))
    }

    override suspend fun disconnect() {
        streamingJob?.cancel()
        streamingJob = null
        _isConnected.value = false
    }

    override suspend fun send(prompt: String, systemPrompt: String?, tools: List<GliaToolDefinition>) {
        if (!_isConnected.value) {
            throw GliaException.NotConnected("Cliente SSE desconectado")
        }

        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        streamingJob?.cancel()
        streamingJob = scope.launch {
            try {
                _events.emit(GliaStreamEvent.Status("streaming"))

                val requestPayload = buildJsonObject {
                    put("inputs", buildJsonObject {})
                    put("query", trimmed)
                    put("message", trimmed)
                    put("response_mode", "streaming")
                    put("stream", true)
                    put("user", options.userId)
                    val sp = systemPrompt ?: options.systemPrompt
                    if (!sp.isNullOrBlank()) {
                        put("system_prompt", sp)
                    }
                    if (tools.isNotEmpty()) {
                        put("tools", json.parseToJsonElement(json.encodeToString(tools)))
                    }
                }

                val response = httpClient.post {
                    url(options.gatewayUrl)
                    contentType(ContentType.Application.Json)
                    headers {
                        append("Accept", "text/event-stream")
                        if (!options.token.isNullOrBlank()) {
                            append("Authorization", "Bearer ${options.token}")
                        }
                        options.headers.forEach { (k, v) -> append(k, v) }
                    }
                    setBody(requestPayload.toString())
                }

                val channel = response.bodyAsChannel()
                var currentEventType = "message"
                var fullAccumulatedMessage = ""

                while (isActive && !channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmedLine = line.trim()

                    if (trimmedLine.isEmpty()) continue

                    if (trimmedLine.startsWith("event:")) {
                        currentEventType = trimmedLine.removePrefix("event:").trim()
                        continue
                    }

                    if (trimmedLine.startsWith("data:")) {
                        val rawData = trimmedLine.removePrefix("data:").trim()
                        if (rawData == "[DONE]") {
                            _events.emit(GliaStreamEvent.Done(fullAccumulatedMessage))
                            break
                        }

                        parseSseData(rawData, currentEventType) { chunk, isThinking ->
                            if (isThinking) {
                                _events.emit(GliaStreamEvent.ThinkingDelta(chunk))
                            } else {
                                fullAccumulatedMessage += chunk
                                _events.emit(GliaStreamEvent.MessageDelta(chunk))
                            }
                        }
                    }
                }

                if (isActive) {
                    _events.emit(GliaStreamEvent.Done(fullAccumulatedMessage))
                }
            } catch (e: Exception) {
                if (isActive) {
                    _events.emit(GliaStreamEvent.Error("Error en stream SSE: ${e.localizedMessage}"))
                }
            }
        }
    }

    private suspend fun parseSseData(
        data: String,
        eventType: String,
        onDelta: suspend (chunk: String, isThinking: Boolean) -> Unit
    ) {
        try {
            val element = json.parseToJsonElement(data) as? JsonObject ?: return

            // 1. Contrato Dify.ai
            when (element["event"]?.jsonPrimitive?.content ?: eventType) {
                "agent_thought" -> {
                    val thought = element["thought"]?.jsonPrimitive?.content ?: ""
                    if (thought.isNotEmpty()) onDelta(thought, true)
                    return
                }
                "message" -> {
                    val answer = element["answer"]?.jsonPrimitive?.content ?: ""
                    if (answer.isNotEmpty()) onDelta(answer, false)
                    return
                }
                "message_end" -> {
                    return
                }
            }

            // 2. Contrato OpenAI / LangGraph SSE
            val choices = element["choices"] as? JsonArray
            if (choices != null && choices.isNotEmpty()) {
                val delta = (choices[0] as? JsonObject)?.get("delta") as? JsonObject
                if (delta != null) {
                    val reasoning = delta["reasoning_content"]?.jsonPrimitive?.content
                    if (!reasoning.isNullOrEmpty()) {
                        onDelta(reasoning, true)
                        return
                    }

                    val content = delta["content"]?.jsonPrimitive?.content
                    if (!content.isNullOrEmpty()) {
                        onDelta(content, false)
                        return
                    }

                    val toolCalls = delta["tool_calls"] as? JsonArray
                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        val firstTool = toolCalls[0] as? JsonObject
                        val func = firstTool?.get("function") as? JsonObject
                        val name = func?.get("name")?.jsonPrimitive?.content ?: ""
                        _events.emit(GliaStreamEvent.ToolCall(name, emptyMap()))
                        return
                    }
                }
            }

            // 3. Fallback genérico
            val text = element["text"]?.jsonPrimitive?.content
                ?: element["content"]?.jsonPrimitive?.content
                ?: element["message"]?.jsonPrimitive?.content
            if (!text.isNullOrEmpty()) {
                onDelta(text, false)
            }
        } catch (e: Exception) {
            _events.emit(GliaStreamEvent.Error("Error al parsear evento SSE: ${e.localizedMessage ?: e.message}"))
        }
    }

    override fun close() {
        scope.cancel()
    }
}
