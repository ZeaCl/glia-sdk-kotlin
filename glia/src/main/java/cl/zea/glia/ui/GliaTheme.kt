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
    val error: Color = Color(0xFFEF4444)
)
