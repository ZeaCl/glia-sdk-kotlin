package cl.zea.glia.ui

import androidx.compose.ui.graphics.Color

/**
 * Completely customizable and agnostic color theme palette for GliaChat.
 */
data class GliaTheme(
    val bg: Color = Color(0xFF121212),
    val surface: Color = Color(0xFF1E1E1E),
    val surfaceContainerHigh: Color = Color(0xFF2A2A2A),
    val primary: Color = Color(0xFF3B82F6),
    val text: Color = Color.White,
    val textMuted: Color = Color(0xFFA1A1AA),
    val userBubble: Color = Color(0xFF2563EB),
    val userBubbleText: Color = Color.White,
    val agentBubble: Color = Color(0xFF27272A),
    val agentBubbleText: Color = Color.White,
    val thinkingBg: Color = Color(0xFF3B82F6).copy(alpha = 0.12f),
    val thinkingText: Color = Color(0xFF60A5FA),
    val thinkingBorder: Color = Color(0xFF3B82F6).copy(alpha = 0.40f),
    val toolIconTint: Color = Color(0xFFEAB308),
    val error: Color = Color(0xFFEF4444),
    val codeBlockBg: Color = Color(0xFF18181B),
    val codeBlockText: Color = Color(0xFFE4E4E7),
    val codeBlockBorder: Color = Color(0xFF3F3F46).copy(alpha = 0.5f),
    val codeInlineBg: Color = Color(0xFF3F3F46).copy(alpha = 0.45f),
    val codeInlineText: Color = Color(0xFFF43F5E),
    val linkText: Color = Color(0xFF60A5FA),
    val blockquoteBorder: Color = Color(0xFF3B82F6),
    val tableBorder: Color = Color(0xFF3F3F46).copy(alpha = 0.5f)
)
