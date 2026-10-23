package cl.zea.glia.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import cl.zea.glia.ui.GliaTheme
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/**
 * Traverses inline CommonMark nodes to construct an [AnnotatedString] styled with [GliaTheme].
 */
object MarkdownInlineAnnotator {

    fun annotate(
        rootNode: Node,
        theme: GliaTheme,
        defaultColor: Color
    ): AnnotatedString {
        return buildAnnotatedString {
            var child = rootNode.firstChild
            while (child != null) {
                appendNode(child, theme, defaultColor)
                child = child.next
            }
        }
    }

    private fun AnnotatedString.Builder.appendNode(
        node: Node,
        theme: GliaTheme,
        defaultColor: Color
    ) {
        when (node) {
            is Text -> {
                append(node.literal)
            }
            is StrongEmphasis -> {
                val start = length
                var child = node.firstChild
                while (child != null) {
                    appendNode(child, theme, defaultColor)
                    child = child.next
                }
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            is Emphasis -> {
                val start = length
                var child = node.firstChild
                while (child != null) {
                    appendNode(child, theme, defaultColor)
                    child = child.next
                }
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
            is Strikethrough -> {
                val start = length
                var child = node.firstChild
                while (child != null) {
                    appendNode(child, theme, defaultColor)
                    child = child.next
                }
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
            }
            is Code -> {
                val start = length
                append(node.literal)
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = theme.codeInlineBg,
                        color = theme.codeInlineText
                    ),
                    start,
                    length
                )
            }
            is Link -> {
                val start = length
                var child = node.firstChild
                while (child != null) {
                    appendNode(child, theme, defaultColor)
                    child = child.next
                }
                addStyle(
                    SpanStyle(
                        color = theme.linkText,
                        textDecoration = TextDecoration.Underline
                    ),
                    start,
                    length
                )
                addStringAnnotation(
                    tag = "URL",
                    annotation = node.destination,
                    start = start,
                    end = length
                )
            }
            is SoftLineBreak -> {
                append("\n")
            }
            is HardLineBreak -> {
                append("\n")
            }
            else -> {
                // If it's an unrecognized or generic inline node, visit its children
                var child = node.firstChild
                while (child != null) {
                    appendNode(child, theme, defaultColor)
                    child = child.next
                }
            }
        }
    }
}
