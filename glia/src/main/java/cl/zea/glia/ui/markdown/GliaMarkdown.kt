package cl.zea.glia.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.zea.glia.ui.GliaTheme

/**
 * High-performance, streaming-resilient Markdown renderer for GliaChat.
 */
@Composable
fun GliaMarkdown(
    content: String,
    theme: GliaTheme,
    isUser: Boolean = false,
    modifier: Modifier = Modifier
) {
    val defaultColor = if (isUser) theme.userBubbleText else theme.agentBubbleText

    val blocks = remember(content, theme, defaultColor) {
        MarkdownParser.parse(content, theme, defaultColor)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    MarkdownTextView(
                        content = block.content,
                        color = defaultColor
                    )
                }
                is MarkdownBlock.Heading -> {
                    val (fontSize, fontWeight) = when (block.level) {
                        1 -> 20.sp to FontWeight.Bold
                        2 -> 18.sp to FontWeight.Bold
                        3 -> 16.sp to FontWeight.SemiBold
                        else -> 15.sp to FontWeight.SemiBold
                    }
                    MarkdownTextView(
                        content = block.content,
                        color = defaultColor,
                        fontSize = fontSize,
                        style = LocalTextStyle.current.copy(fontWeight = fontWeight)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    CodeBlockView(
                        codeBlock = block,
                        theme = theme
                    )
                }
                is MarkdownBlock.ListItem -> {
                    MarkdownListItemView(
                        item = block,
                        color = defaultColor
                    )
                }
                is MarkdownBlock.Blockquote -> {
                    MarkdownBlockquoteView(
                        blockquote = block,
                        theme = theme,
                        color = defaultColor
                    )
                }
                is MarkdownBlock.Table -> {
                    MarkdownTableView(
                        table = block,
                        theme = theme,
                        color = defaultColor
                    )
                }
                is MarkdownBlock.ThematicBreak -> {
                    HorizontalDivider(
                        color = theme.codeBlockBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
