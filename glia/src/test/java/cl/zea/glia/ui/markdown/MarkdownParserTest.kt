package cl.zea.glia.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import cl.zea.glia.ui.GliaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private val theme = GliaTheme()
    private val defaultColor = Color.White

    @Test
    fun `test inline bold and italic parsing`() {
        val markdown = "Hello **bold** and *italic* world"
        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("Hello bold and italic world", paragraph.content.text)

        // Verify bold span
        val boldSpans = paragraph.content.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(6, boldSpans[0].start)
        assertEquals(10, boldSpans[0].end)

        // Verify italic span
        val italicSpans = paragraph.content.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italicSpans.size)
        assertEquals(15, italicSpans[0].start)
        assertEquals(21, italicSpans[0].end)
    }

    @Test
    fun `test inline code and link annotations`() {
        val markdown = "Run `npm install` or visit [Zea](https://zea.cl) today"
        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("Run npm install or visit Zea today", paragraph.content.text)

        // Inline code span
        val codeSpans = paragraph.content.spanStyles.filter { it.item.background == theme.codeInlineBg }
        assertEquals(1, codeSpans.size)
        assertEquals(4, codeSpans[0].start)
        assertEquals(15, codeSpans[0].end)

        // Link annotation
        val urlAnnotations = paragraph.content.getStringAnnotations(tag = "URL", start = 0, end = paragraph.content.text.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("https://zea.cl", urlAnnotations[0].item)
        assertEquals(25, urlAnnotations[0].start)
        assertEquals(28, urlAnnotations[0].end)
    }

    @Test
    fun `test fenced code block parsing`() {
        val markdown = """
            Here is some code:
            ```kotlin
            val greeting = "Hello, Glia!"
            println(greeting)
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.CodeBlock)

        val codeBlock = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("val greeting = \"Hello, Glia!\"\nprintln(greeting)", codeBlock.code)
    }

    @Test
    fun `test bullet and ordered lists`() {
        val markdown = """
            - Apple
            - Banana
            - Cherry

            1. First step
            2. Second step
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(5, blocks.size)

        // Unordered list items
        val item1 = blocks[0] as MarkdownBlock.ListItem
        assertEquals(false, item1.isOrdered)
        assertEquals("Apple", item1.content.text)

        val item2 = blocks[1] as MarkdownBlock.ListItem
        assertEquals(false, item2.isOrdered)
        assertEquals("Banana", item2.content.text)

        val item3 = blocks[2] as MarkdownBlock.ListItem
        assertEquals(false, item3.isOrdered)
        assertEquals("Cherry", item3.content.text)

        // Ordered list items
        val item4 = blocks[3] as MarkdownBlock.ListItem
        assertEquals(true, item4.isOrdered)
        assertEquals(1, item4.number)
        assertEquals("First step", item4.content.text)

        val item5 = blocks[4] as MarkdownBlock.ListItem
        assertEquals(true, item5.isOrdered)
        assertEquals(2, item5.number)
        assertEquals("Second step", item5.content.text)
    }

    @Test
    fun `test heading levels`() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(3, blocks.size)
        val h1 = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Heading 1", h1.content.text)

        val h2 = blocks[1] as MarkdownBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("Heading 2", h2.content.text)

        val h3 = blocks[2] as MarkdownBlock.Heading
        assertEquals(3, h3.level)
        assertEquals("Heading 3", h3.content.text)
    }

    @Test
    fun `test blockquote and gfm table`() {
        val markdown = """
            > This is a quote

            | Col A | Col B |
            |-------|-------|
            | Val 1 | Val 2 |
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown, theme, defaultColor)

        assertEquals(2, blocks.size)
        val quote = blocks[0] as MarkdownBlock.Blockquote
        assertEquals(1, quote.blocks.size)
        assertEquals("This is a quote", (quote.blocks[0] as MarkdownBlock.Paragraph).content.text)

        val table = blocks[1] as MarkdownBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals("Col A", table.headers[0].text)
        assertEquals("Col B", table.headers[1].text)
        assertEquals(1, table.rows.size)
        assertEquals("Val 1", table.rows[0][0].text)
        assertEquals("Val 2", table.rows[0][1].text)
    }

    @Test
    fun `test streaming resilience with unclosed tokens`() {
        // Partial tokens arriving mid-stream from LLM
        val unclosedBold = "Thinking about **incomplete bold text"
        val unclosedCode = "Here is code:\n```kotlin\nval x = 1"
        val unclosedLink = "See [partial link"

        val blocksBold = MarkdownParser.parse(unclosedBold, theme, defaultColor)
        assertTrue(blocksBold.isNotEmpty())

        val blocksCode = MarkdownParser.parse(unclosedCode, theme, defaultColor)
        assertTrue(blocksCode.isNotEmpty())

        val blocksLink = MarkdownParser.parse(unclosedLink, theme, defaultColor)
        assertTrue(blocksLink.isNotEmpty())
    }
}
