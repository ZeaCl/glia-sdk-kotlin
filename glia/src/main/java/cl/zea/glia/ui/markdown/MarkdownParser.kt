package cl.zea.glia.ui.markdown

import androidx.compose.ui.graphics.Color
import cl.zea.glia.ui.GliaTheme
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Headless, high-performance Markdown parser wrapping CommonMark with GFM extensions.
 */
object MarkdownParser {

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughExtension.create()
            )
        )
        .build()

    fun parse(
        content: String,
        theme: GliaTheme,
        defaultColor: Color
    ): List<MarkdownBlock> {
        if (content.isBlank()) return emptyList()

        val document = parser.parse(content)
        return parseNodeList(document.firstChild, theme, defaultColor)
    }

    private fun parseNodeList(
        startNode: Node?,
        theme: GliaTheme,
        defaultColor: Color
    ): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var current: Node? = startNode

        while (current != null) {
            when (current) {
                is Paragraph -> {
                    val annotated = MarkdownInlineAnnotator.annotate(current, theme, defaultColor)
                    if (annotated.isNotEmpty()) {
                        blocks.add(MarkdownBlock.Paragraph(annotated))
                    }
                }
                is Heading -> {
                    val annotated = MarkdownInlineAnnotator.annotate(current, theme, defaultColor)
                    blocks.add(MarkdownBlock.Heading(level = current.level, content = annotated))
                }
                is FencedCodeBlock -> {
                    blocks.add(
                        MarkdownBlock.CodeBlock(
                            language = current.info?.trim()?.takeIf { it.isNotBlank() },
                            code = current.literal.trimEnd('\r', '\n')
                        )
                    )
                }
                is IndentedCodeBlock -> {
                    blocks.add(
                        MarkdownBlock.CodeBlock(
                            language = null,
                            code = current.literal.trimEnd('\r', '\n')
                        )
                    )
                }
                is BulletList -> {
                    var itemNode = current.firstChild
                    while (itemNode != null) {
                        if (itemNode is ListItem) {
                            val content = extractListItemContent(itemNode, theme, defaultColor)
                            blocks.add(
                                MarkdownBlock.ListItem(
                                    isOrdered = false,
                                    number = 0,
                                    content = content
                                )
                            )
                        }
                        itemNode = itemNode.next
                    }
                }
                is OrderedList -> {
                    var itemNode = current.firstChild
                    var idx = current.markerStartNumber ?: 1
                    while (itemNode != null) {
                        if (itemNode is ListItem) {
                            val content = extractListItemContent(itemNode, theme, defaultColor)
                            blocks.add(
                                MarkdownBlock.ListItem(
                                    isOrdered = true,
                                    number = idx,
                                    content = content
                                )
                            )
                            idx++
                        }
                        itemNode = itemNode.next
                    }
                }
                is BlockQuote -> {
                    val quoteBlocks = parseNodeList(current.firstChild, theme, defaultColor)
                    blocks.add(MarkdownBlock.Blockquote(quoteBlocks))
                }
                is TableBlock -> {
                    val table = parseTable(current, theme, defaultColor)
                    if (table != null) {
                        blocks.add(table)
                    }
                }
                is ThematicBreak -> {
                    blocks.add(MarkdownBlock.ThematicBreak)
                }
                else -> {
                    // Fallback: if it has inline children, treat as paragraph
                    val inlineContent = MarkdownInlineAnnotator.annotate(current, theme, defaultColor)
                    if (inlineContent.isNotEmpty()) {
                        blocks.add(MarkdownBlock.Paragraph(inlineContent))
                    }
                }
            }
            current = current.next
        }

        return blocks
    }

    private fun extractListItemContent(
        listItem: ListItem,
        theme: GliaTheme,
        defaultColor: Color
    ): androidx.compose.ui.text.AnnotatedString {
        // If the list item has child paragraphs, annotate their contents; otherwise annotate the item directly
        val firstChild = listItem.firstChild
        return if (firstChild is Paragraph) {
            MarkdownInlineAnnotator.annotate(firstChild, theme, defaultColor)
        } else {
            MarkdownInlineAnnotator.annotate(listItem, theme, defaultColor)
        }
    }

    private fun parseTable(
        tableBlock: TableBlock,
        theme: GliaTheme,
        defaultColor: Color
    ): MarkdownBlock.Table? {
        val headers = mutableListOf<androidx.compose.ui.text.AnnotatedString>()
        val rows = mutableListOf<List<androidx.compose.ui.text.AnnotatedString>>()

        var child = tableBlock.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    var rowChild = child.firstChild
                    while (rowChild != null) {
                        if (rowChild is TableRow) {
                            var cellChild = rowChild.firstChild
                            while (cellChild != null) {
                                if (cellChild is TableCell) {
                                    headers.add(MarkdownInlineAnnotator.annotate(cellChild, theme, defaultColor))
                                }
                                cellChild = cellChild.next
                            }
                        }
                        rowChild = rowChild.next
                    }
                }
                is TableBody -> {
                    var rowChild = child.firstChild
                    while (rowChild != null) {
                        if (rowChild is TableRow) {
                            val rowCells = mutableListOf<androidx.compose.ui.text.AnnotatedString>()
                            var cellChild = rowChild.firstChild
                            while (cellChild != null) {
                                if (cellChild is TableCell) {
                                    rowCells.add(MarkdownInlineAnnotator.annotate(cellChild, theme, defaultColor))
                                }
                                cellChild = cellChild.next
                            }
                            if (rowCells.isNotEmpty()) {
                                rows.add(rowCells)
                            }
                        }
                        rowChild = rowChild.next
                    }
                }
            }
            child = child.next
        }

        return if (headers.isNotEmpty() || rows.isNotEmpty()) {
            MarkdownBlock.Table(headers = headers, rows = rows)
        } else {
            null
        }
    }
}
