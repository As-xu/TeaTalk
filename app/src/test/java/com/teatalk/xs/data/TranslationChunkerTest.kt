package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationChunkerTest {

    @Test
    fun blankInputYieldsNoChunks() {
        assertTrue(TranslationChunker.split("").isEmpty())
        assertTrue(TranslationChunker.split("   \n\n  ").isEmpty())
    }

    @Test
    fun shortTextStaysWhole() {
        val text = "A short line about tea."
        assertEquals(listOf(text), TranslationChunker.split(text))
    }

    @Test
    fun longTextSplitsOnBlankLinesAndKeepsOrder() {
        val paragraph = "字".repeat(800)
        val text = "$paragraph\n\n$paragraph\n\n$paragraph"
        val chunks = TranslationChunker.split(text, limit = 1000)
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(paragraph, it) }
        assertEquals(text.replace("\n\n", ""), chunks.joinToString(""))
    }

    @Test
    fun singleHugeParagraphIsHardCut() {
        val text = "x".repeat(2500)
        val chunks = TranslationChunker.split(text, limit = 1000)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= 1000 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun respectsDefaultLimit() {
        val text = ("y".repeat(400) + "\n\n").repeat(10)
        val chunks = TranslationChunker.split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= TranslationChunker.MAX_CHARS })
    }
}
