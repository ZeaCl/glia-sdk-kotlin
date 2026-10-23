package cl.zea.glia.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.zea.glia.core.client.GliaClientProtocol
import cl.zea.glia.core.models.GliaStreamEvent
import cl.zea.glia.core.models.GliaToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class GliaMessageRole {
    USER, ASSISTANT
}

@Serializable
data class GliaChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: GliaMessageRole,
    val content: String,
    val thinking: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class GliaUiState(
    val messages: List<GliaChatMessage> = emptyList(),
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val currentThinking: String = "",
    val currentText: String = "",
    val currentTool: String? = null,
    val errorMessage: String? = null
)

class GliaChatViewModel(
    private val client: GliaClientProtocol,
    initialMessages: List<GliaChatMessage> = emptyList(),
    var onMessagesUpdated: ((List<GliaChatMessage>) -> Unit)? = null,
    var errorSanitizer: ((Throwable) -> String)? = null,
    var onError: ((Throwable) -> Unit)? = null
) : ViewModel() {

    companion object {
        const val DEFAULT_ERROR_MESSAGE: String =
            "Estamos con problemas y en estos momentos no podemos atender su solicitud. Por favor, intenta nuevamente en unos instantes."
    }

    private val _uiState = MutableStateFlow(GliaUiState(messages = initialMessages))
    val uiState: StateFlow<GliaUiState> = _uiState.asStateFlow()

    private var lastSentPrompt: String? = null
    private var lastSentSystemPrompt: String? = null
    private var lastSentTools: List<GliaToolDefinition> = emptyList()
    private var lastExecutedTool: String? = null

    init {
        viewModelScope.launch {
            client.isConnected.collect { connected ->
                _uiState.update { it.copy(isConnected = connected) }
            }
        }

        viewModelScope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    fun loadMessages(newMessages: List<GliaChatMessage>) {
        _uiState.update { it.copy(messages = newMessages) }
        onMessagesUpdated?.invoke(newMessages)
    }

    fun clearMessages() {
        _uiState.update { it.copy(messages = emptyList()) }
        onMessagesUpdated?.invoke(emptyList())
    }

    fun connect() {
        viewModelScope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = formatError(e)) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            client.disconnect()
        }
    }

    fun retryLastSend() {
        val prompt = lastSentPrompt
        if (prompt != null) {
            sendInternal(prompt = prompt, systemPrompt = lastSentSystemPrompt, tools = lastSentTools, isRetry = true)
        } else {
            connect()
        }
    }

    fun send(prompt: String, systemPrompt: String? = null, tools: List<GliaToolDefinition> = emptyList()) {
        sendInternal(prompt = prompt, systemPrompt = systemPrompt, tools = tools, isRetry = false)
    }

    private fun sendInternal(
        prompt: String,
        systemPrompt: String?,
        tools: List<GliaToolDefinition>,
        isRetry: Boolean = false
    ) {
        val trimmed = prompt.trim()
        // Guard against concurrent sends while streaming is active
        if (trimmed.isEmpty() || _uiState.value.isStreaming) return

        this.lastSentPrompt = trimmed
        this.lastSentSystemPrompt = systemPrompt
        this.lastSentTools = tools

        val updatedMessages = if (!isRetry) {
            val userMsg = GliaChatMessage(role = GliaMessageRole.USER, content = trimmed)
            _uiState.value.messages + userMsg
        } else {
            _uiState.value.messages
        }

        lastExecutedTool = null
        _uiState.update { state ->
            state.copy(
                messages = updatedMessages,
                isStreaming = true,
                currentThinking = "",
                currentText = "",
                currentTool = null,
                errorMessage = null
            )
        }
        if (!isRetry) {
            onMessagesUpdated?.invoke(updatedMessages)
        }

        viewModelScope.launch {
            try {
                if (!client.isConnected.value) {
                    client.connect()
                }
                client.send(prompt = trimmed, systemPrompt = systemPrompt, tools = tools)
            } catch (e: Exception) {
                _uiState.update { it.copy(isStreaming = false, errorMessage = formatError(e)) }
            }
        }
    }

    private fun formatError(throwable: Throwable): String {
        onError?.invoke(throwable)
        errorSanitizer?.invoke(throwable)?.let { return it }

        val desc = throwable.localizedMessage ?: throwable.message ?: ""
        val lower = desc.lowercase()
        if (lower.contains("glia") || lower.contains("gateway") || lower.contains("phoenix") || lower.contains("websocket")) {
            return DEFAULT_ERROR_MESSAGE
        }
        return if (desc.isNotBlank()) desc else DEFAULT_ERROR_MESSAGE
    }

    private fun handleEvent(event: GliaStreamEvent) {
        when (event) {
            is GliaStreamEvent.Status -> {
                if (event.status == "idle" && !_uiState.value.isStreaming) {
                    _uiState.update { it.copy(currentTool = null) }
                }
            }
            is GliaStreamEvent.ThinkingDelta -> {
                _uiState.update { it.copy(currentThinking = it.currentThinking + event.content) }
            }
            is GliaStreamEvent.MessageDelta -> {
                _uiState.update { it.copy(currentText = it.currentText + event.content) }
            }
            is GliaStreamEvent.ToolCall -> {
                lastExecutedTool = event.name
                _uiState.update { it.copy(currentTool = event.name) }
            }
            is GliaStreamEvent.ToolResult -> {
                if (_uiState.value.currentTool == event.name) {
                    _uiState.update { it.copy(currentTool = null) }
                }
            }
            is GliaStreamEvent.Done -> {
                val state = _uiState.value
                val fullText = event.fullMessage?.takeIf { it.isNotEmpty() } ?: state.currentText
                val newMessages = if (fullText.isNotEmpty()) {
                    state.messages + GliaChatMessage(
                        role = GliaMessageRole.ASSISTANT,
                        content = fullText,
                        thinking = state.currentThinking.takeIf { it.isNotEmpty() },
                        toolName = lastExecutedTool ?: state.currentTool
                    )
                } else {
                    state.messages
                }

                _uiState.update {
                    it.copy(
                        messages = newMessages,
                        isStreaming = false,
                        currentThinking = "",
                        currentText = "",
                        currentTool = null
                    )
                }
                lastExecutedTool = null
                onMessagesUpdated?.invoke(newMessages)
            }
            is GliaStreamEvent.Error -> {
                val serverErr = cl.zea.glia.core.models.GliaException.ServerError(event.message)
                _uiState.update { it.copy(isStreaming = false, errorMessage = formatError(serverErr)) }
            }
            is GliaStreamEvent.Reconnecting -> {
                // Optional reconnection notification
            }
        }
    }
}

