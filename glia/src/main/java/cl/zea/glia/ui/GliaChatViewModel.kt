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
import java.util.UUID

enum class GliaMessageRole {
    USER, ASSISTANT
}

data class GliaChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: GliaMessageRole,
    val content: String,
    val thinking: String? = null,
    val toolName: String? = null
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
    private val client: GliaClientProtocol
) : ViewModel() {

    private val _uiState = MutableStateFlow(GliaUiState())
    val uiState: StateFlow<GliaUiState> = _uiState.asStateFlow()

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

    fun connect() {
        viewModelScope.launch {
            client.connect()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            client.disconnect()
        }
    }

    fun send(prompt: String, systemPrompt: String? = null, tools: List<GliaToolDefinition> = emptyList()) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return

        val userMsg = GliaChatMessage(role = GliaMessageRole.USER, content = trimmed)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMsg,
                isStreaming = true,
                currentThinking = "",
                currentText = "",
                currentTool = null,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                client.send(prompt = trimmed, systemPrompt = systemPrompt, tools = tools)
            } catch (e: Exception) {
                _uiState.update { it.copy(isStreaming = false, errorMessage = e.localizedMessage) }
            }
        }
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
                        toolName = state.currentTool
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
            }
            is GliaStreamEvent.Error -> {
                _uiState.update { it.copy(isStreaming = false, errorMessage = event.message) }
            }
            is GliaStreamEvent.Reconnecting -> {
                // Notifica de reconexión opcional
            }
        }
    }
}
