package com.teatalk.xs.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `headings become heading blocks`() {
        val blocks = Markdown.parse("# Title\n## Section\n### Small")
        assertEquals(3, blocks.size)
        assertEquals(Markdown.Block.Heading(1, "Title"), blocks[0])
        assertEquals(Markdown.Block.Heading(2, "Section"), blocks[1])
        assertEquals(Markdown.Block.Heading(3, "Small"), blocks[2])
    }

    @Test
    fun `paragraph lines merge until blank line`() {
        val blocks = Markdown.parse("first line\nsecond line\n\nnext para")
        assertEquals(2, blocks.size)
        assertEquals(Markdown.Block.Paragraph("first line\nsecond line"), blocks[0])
        assertEquals(Markdown.Block.Paragraph("next para"), blocks[1])
    }

    @Test
    fun `bullets and ordered lists`() {
        val blocks = Markdown.parse("- one\n- two\n\n3. three\n4. four")
        assertEquals(Markdown.Block.Bullets(listOf("one", "two")), blocks[0])
        assertEquals(Markdown.Block.Ordered(listOf("three", "four"), start = 3), blocks[1])
    }

    @Test
    fun `fenced code keeps blank lines and language`() {
        val blocks = Markdown.parse("```json\n{\"a\": 1}\n\n{\"b\": 2}\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as Markdown.Block.Code
        assertEquals("json", code.language)
        assertEquals("{\"a\": 1}\n\n{\"b\": 2}", code.code)
    }

    @Test
    fun `unclosed fence while streaming stays code block`() {
        val blocks = Markdown.parse("text\n```kotlin\nval x = 1")
        assertTrue(blocks.any { it is Markdown.Block.Code })
    }

    @Test
    fun `quote and divider`() {
        val blocks = Markdown.parse("> quoted\n> line two\n---")
        assertEquals(Markdown.Block.Quote("quoted\nline two"), blocks[0])
        assertEquals(Markdown.Block.Divider, blocks[1])
    }

    @Test
    fun `inline bold italic code strike and link`() {
        val (plain, spans) = Markdown.inline("a **b** c *d* e `f` g ~~h~~ [i](http://x)")
        assertEquals("a b c d e f g h i", plain)
        assertEquals(
            listOf(
                Markdown.Style.BOLD,
                Markdown.Style.ITALIC,
                Markdown.Style.CODE,
                Markdown.Style.STRIKE,
                Markdown.Style.LINK,
            ),
            spans.map { it.style },
        )
        assertEquals("http://x", spans.last().target)
        // 每个 span 的区间要落在纯文本上
        assertEquals("b", plain.substring(spans[0].start, spans[0].end))
        assertEquals("i", plain.substring(spans[4].start, spans[4].end))
    }

    @Test
    fun `unclosed inline markers stay literal`() {
        val (plain, spans) = Markdown.inline("still **typing")
        assertEquals("still **typing", plain)
        assertTrue(spans.isEmpty())
        val (plain2, spans2) = Markdown.inline("code `open")
        assertEquals("code `open", plain2)
        assertTrue(spans2.isEmpty())
    }

    @Test
    fun `escaped markers are literal`() {
        val (plain, spans) = Markdown.inline("\\*not italic\\*")
        assertEquals("*not italic*", plain)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `adjacent same style spans merge`() {
        val (plain, spans) = Markdown.inline("**a****b**")
        assertEquals("ab", plain)
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(2, spans[0].end)
    }

    @Test
    fun `roleplay narration in asterisks renders as italic`() {
        val text = "*She smiles.* \"Hello.\""
        val (plain, spans) = Markdown.inline(text)
        assertEquals("She smiles. \"Hello.\"", plain)
        assertEquals(listOf(Markdown.Style.ITALIC), spans.map { it.style })
        assertEquals("She smiles.", plain.substring(spans[0].start, spans[0].end))
    }
}
