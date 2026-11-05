package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.26.1：开场白候选（first_mes + alternate_greetings）。 */
class GreetingsTest {

    @Test
    fun `first message comes first then alternates in order`() {
        val options = Greetings.options("开场一", listOf("开场二", "开场三"))
        assertEquals(listOf("开场一", "开场二", "开场三"), options)
    }

    @Test
    fun `blank first message is dropped but alternates survive`() {
        val options = Greetings.options("   ", listOf("真正的开场"))
        assertEquals(listOf("真正的开场"), options)
    }

    @Test
    fun `blank and duplicate alternates are removed`() {
        val options = Greetings.options("A 开场", listOf(" A 开场 ", "", "B 开场", "B 开场", "\n"))
        assertEquals(listOf("A 开场", "B 开场"), options)
    }

    @Test
    fun `template placeholder is not offered when a real greeting exists`() {
        val shell = "<音乐>\n<开场白>"
        assertTrue(Greetings.isShell(shell))
        assertEquals(listOf("雨夜的客厅，暖气嗡嗡响着。"), Greetings.options(shell, listOf("雨夜的客厅，暖气嗡嗡响着。")))
        assertFalse(Greetings.needsChoice(shell, listOf("雨夜的客厅，暖气嗡嗡响着。")))
        assertEquals("雨夜的客厅，暖气嗡嗡响着。", Greetings.defaultOpening(shell, listOf("雨夜的客厅，暖气嗡嗡响着。")))
    }

    @Test
    fun `two real greetings need a choice`() {
        assertTrue(Greetings.needsChoice("<音乐>\n<开场白>", listOf("第一段正文。", "第二段正文。")))
        assertTrue(Greetings.needsChoice("开场一", listOf("开场二")))
        assertFalse(Greetings.needsChoice("只有这一段", emptyList()))
        assertFalse(Greetings.needsChoice("", emptyList()))
    }

    @Test
    fun `short chinese opener is kept`() {
        assertFalse(Greetings.isShell("你来了。"))
        assertEquals("你来了。", Greetings.options("你来了。", emptyList()).single())
    }

    @Test
    fun `preview picks the first prose line and flattens it`() {
        val text = "# 标题行\n\n====================\n更衣室沉重的门帘在身后合拢。\n第二行"
        assertEquals("更衣室沉重的门帘在身后合拢。", Greetings.preview(text))
    }

    @Test
    fun `preview truncates by code points and marks the cut`() {
        val long = "字".repeat(120)
        val preview = Greetings.preview(long, limit = 20)
        assertTrue(preview.endsWith("…"))
        assertEquals(21, preview.length)
    }

    @Test
    fun `default opening falls back to shell when nothing else exists`() {
        assertEquals("<音乐>", Greetings.defaultOpening("<音乐>", emptyList()))
        assertNull(Greetings.defaultOpening("   ", emptyList()))
    }
}
