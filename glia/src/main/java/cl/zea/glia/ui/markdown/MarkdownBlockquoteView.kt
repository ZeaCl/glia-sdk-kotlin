package cl.zea.glia.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cl.zea.glia.ui.GliaTheme

@Composable
fun MarkdownBlockquoteView(
    blockquote: MarkdownBlock.Blockquote,
    theme: GliaTheme,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp)
    ) {
        // Vertical accent bar
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(theme.blockquoteBorder)
        )
        Column(
            modifier = Modifier
                .padding(start = 10.dp)
                .fillMaxWidth()
        ) {
            blockquote.blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> {
                        MarkdownTextView(
                            content = block.content,
                            color = color.copy(alpha = 0.9f)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
