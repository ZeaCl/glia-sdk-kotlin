package cl.zea.glia.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

/**
 * Domain model representing a block-level Markdown element.
 */
@Immutable
sealed interface MarkdownBlock {
    data class Paragraph(val content: AnnotatedString) : MarkdownBlock

    data class Heading(
        val level: Int,
        val content: AnnotatedString
    ) : MarkdownBlock

    data class CodeBlock(
        val language: String?,
        val code: String
    ) : MarkdownBlock

    data class ListItem(
        val isOrdered: Boolean,
        val number: Int,
        val content: AnnotatedString
    ) : MarkdownBlock

    data class Blockquote(
        val blocks: List<MarkdownBlock>
    ) : MarkdownBlock

    data class Table(
        val headers: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>
    ) : MarkdownBlock

    data object ThematicBreak : MarkdownBlock
}
