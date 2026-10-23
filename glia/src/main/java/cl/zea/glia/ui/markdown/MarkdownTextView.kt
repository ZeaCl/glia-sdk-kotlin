package cl.zea.glia.ui.markdown

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownTextView(
    content: AnnotatedString,
    color: Color,
    fontSize: TextUnit = 15.sp,
    lineHeight: TextUnit = 22.sp,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current
) {
    val uriHandler = LocalUriHandler.current

    ClickableText(
        text = content,
        modifier = modifier,
        style = style.copy(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight
        ),
        onClick = { offset ->
            val annotations = content.getStringAnnotations(tag = "URL", start = offset, end = offset)
            annotations.firstOrNull()?.let { annotation ->
                try {
                    uriHandler.openUri(annotation.item)
                } catch (_: Exception) {
                    // Safe catch if invalid URI scheme or no browser available
                }
            }
        }
    )
}
