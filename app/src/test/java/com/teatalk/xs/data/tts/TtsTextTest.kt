package com.teatalk.xs.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {

    @Test
    fun cleanStripsMarkdownAndCode() {
        val raw = "**她抬眼**看了一眼`手机`，\n\n```kotlin\nval x = 1\n```\n[链接](https://example.com) 然后笑了。"
        val clean = TtsText.clean(raw)
        assertFalse(clean.contains("**"))
        assertFalse(clean.contains("`"))
        assertFalse(clean.contains("```"))
        assertFalse(clean.contains("https://"))
        assertTrue(clean.contains("她抬眼"))
        assertTrue(clean.contains("链接"))
    }

    @Test
    fun segmentsSplitsAtSentenceEnd() {
        // 每句 31 字、上限 40：两句放不下，必须逐句成段。
        val sentence = "啊".repeat(30) + "。"
        val segments = TtsText.segments(sentence + sentence + sentence, max = 40)
        assertEquals(3, segments.size)
        assertEquals(sentence, segments[0])
        assertTrue(segments.all { it.length <= 40 })
    }

    @Test
    fun segmentsDoNotDropContent() {
        val raw = "第一句。第二句！第三句？"
        val joined = TtsText.segments(raw, max = 20).joinToString("").replace(" ", "")
        assertEquals("第一句。第二句！第三句？", joined)
    }

    @Test
    fun segmentsMergeShortSentencesUpToLimit() {
        val segments = TtsText.segments("好。走吧。", max = 200)
        assertEquals(1, segments.size)
    }

    @Test
    fun segmentsHardSplitLongSentence() {
        val long = "啊".repeat(450)
        val segments = TtsText.segments(long, max = 200)
        assertEquals(3, segments.size)
        assertTrue(segments.all { it.length <= 200 })
    }

    @Test
    fun hardSplitNeverCutsSurrogatePair() {
        val emoji = String(Character.toChars(0x1F600))
        val segments = TtsText.segments(emoji.repeat(120), max = 50)
        segments.forEach { piece ->
            assertFalse(piece.first().isLowSurrogate())
            assertFalse(piece.last().isHighSurrogate())
        }
        assertEquals(emoji.repeat(120), segments.joinToString(""))
    }

    @Test
    fun segmentsKeepLineBreaksAsBoundaries() {
        val segments = TtsText.segments("第一行\n第二行", max = 200)
        assertEquals(1, segments.size)
        assertTrue(segments[0].contains("第一行"))
        assertTrue(segments[0].contains("第二行"))
    }

    @Test
    fun segmentsIgnoreBlankInput() {
        assertTrue(TtsText.segments("   \n\t ").isEmpty())
    }

    @Test
    fun segmentsRespectCustomSmallLimit() {
        val segments = TtsText.segments("一二三四五六七八九十", max = 20)
        assertEquals(1, segments.size)
    }
}
