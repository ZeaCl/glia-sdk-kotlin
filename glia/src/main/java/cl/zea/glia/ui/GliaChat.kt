package cl.zea.glia.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.zea.glia.core.models.GliaToolDefinition

@Composable
fun GliaChat(
    viewModel: GliaChatViewModel,
    title: String? = "AI Assistant",
    welcomeMessage: String = "Hello! How can I help you today?",
    placeholder: String = "Type a message...",
    suggestedPrompts: List<String> = emptyList(),
    theme: GliaTheme = GliaTheme(),
    systemPrompt: String? = null,
    tools: List<GliaToolDefinition> = emptyList(),
    disconnectOnDispose: Boolean = false,
    pendingPrompt: String? = null,
    onPromptConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(viewModel) {
        viewModel.connect()
        onDispose {
            if (disconnectOnDispose) {
                viewModel.disconnect()
            }
        }
    }

    LaunchedEffect(pendingPrompt) {
        val prompt = pendingPrompt
        if (!prompt.isNullOrBlank()) {
            viewModel.send(prompt, systemPrompt, tools)
            onPromptConsumed()
        }
    }

    // Optimized auto-scroll: Animated on new message addition
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Direct auto-scroll (without heavy animation) during rapid token streaming
    LaunchedEffect(uiState.currentText) {
        if (uiState.isStreaming) {
            val totalItems = uiState.messages.size + 1
            listState.scrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(theme.bg)
    ) {
        if (title != null) {
            HeaderBar(title = title, isConnected = uiState.isConnected, theme = theme)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                WelcomeView(
                    welcomeMessage = welcomeMessage,
                    suggestedPrompts = suggestedPrompts,
                    theme = theme,
                    onPromptClick = { prompt ->
                        viewModel.send(prompt, systemPrompt, tools)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg, theme = theme)
                    }

                    if (uiState.isStreaming) {
                        item(key = "live_stream_block") {
                            LiveStreamingBlock(
                                thinking = uiState.currentThinking,
                                toolName = uiState.currentTool,
                                streamingText = uiState.currentText,
                                theme = theme
                            )
                        }
                    }
                }
            }
        }

        uiState.errorMessage?.let { err ->
            Surface(
                color = theme.error.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = err,
                    color = theme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        InputArea(
            text = inputText,
            onTextChange = { inputText = it },
            placeholder = placeholder,
            isStreaming = uiState.isStreaming,
            theme = theme,
            onSend = {
                val toSend = inputText
                inputText = ""
                viewModel.send(toSend, systemPrompt, tools)
            }
        )
    }
}

@Composable
private fun HeaderBar(title: String, isConnected: Boolean, theme: GliaTheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("header_bar")
            .background(theme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF22C55E) else Color(0xFFF97316))
                .testTag("status_indicator")
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.textMuted
        )
    }
}

@Composable
private fun WelcomeView(
    welcomeMessage: String,
    suggestedPrompts: List<String>,
    theme: GliaTheme,
    onPromptClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_view")
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = welcomeMessage,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = theme.text,
            modifier = Modifier
                .background(theme.surface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        )

        if (suggestedPrompts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestedPrompts.forEach { prompt ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, theme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                            .background(theme.surface)
                            .clickable { onPromptClick(prompt) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = prompt,
                            fontSize = 14.sp,
                            color = theme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: GliaChatMessage, theme: GliaTheme) {
    val isUser = message.role == GliaMessageRole.USER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isUser) "user_message" else "assistant_message"),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (isUser) theme.userBubble else theme.agentBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!message.thinking.isNullOrBlank()) {
                CollapsibleThinkingBlock(thinking = message.thinking, theme = theme)
                Spacer(modifier = Modifier.height(6.dp))
            }
            if (!message.toolName.isNullOrBlank()) {
                ToolChip(name = message.toolName, theme = theme)
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(
                text = message.content,
                color = if (isUser) theme.userBubbleText else theme.agentBubbleText,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun CollapsibleThinkingBlock(thinking: String, theme: GliaTheme) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("thinking_block")
            .clip(RoundedCornerShape(8.dp))
            .background(theme.thinkingBg)
            .border(1.dp, theme.thinkingBorder, RoundedCornerShape(8.dp))
            .clickable { isExpanded = !isExpanded }
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = theme.thinkingText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Reasoning Process",
                color = theme.thinkingText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = theme.thinkingText,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = thinking,
                color = theme.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun LiveStreamingBlock(
    thinking: String,
    toolName: String?,
    streamingText: String,
    theme: GliaTheme
) {
    Column(
        modifier = Modifier.testTag("live_streaming_block"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (thinking.isNotBlank()) {
            Row(
                modifier = Modifier
                    .testTag("live_thinking_block")
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.thinkingBg)
                    .border(1.dp, theme.thinkingBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = theme.thinkingText,
                    strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = thinking,
                    color = theme.thinkingText,
                    fontSize = 12.sp
                )
            }
        }
        if (!toolName.isNullOrBlank()) {
            ToolChip(name = toolName, theme = theme)
        }
        if (streamingText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .testTag("live_message_block")
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.agentBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = streamingText,
                    color = theme.agentBubbleText,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun ToolChip(name: String, theme: GliaTheme) {
    Row(
        modifier = Modifier
            .testTag("tool_badge")
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            tint = theme.toolIconTint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Tool: $name",
            color = theme.textMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun InputArea(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    isStreaming: Boolean,
    theme: GliaTheme,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(placeholder, color = theme.textMuted) },
            modifier = Modifier
                .weight(1f)
                .testTag("chat_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.text,
                focusedBorderColor = theme.primary,
                unfocusedBorderColor = theme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(20.dp),
            singleLine = true
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && !isStreaming,
            modifier = Modifier.testTag("send_button")
        ) {
            if (isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = theme.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) theme.primary else theme.textMuted
                )
            }
        }
    }
}
