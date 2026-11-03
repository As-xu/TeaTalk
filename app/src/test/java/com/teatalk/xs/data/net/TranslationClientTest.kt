package com.teatalk.xs.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译接口返回体的解析合同：不同供应商在 content 形状、代码块、包裹引号上差异很大。
 *
 * extractText 是 internal 的纯函数，直接构造 JSON 字符串来验证，不依赖网络。
 */
class TranslationClientTest {

    private val client = TranslationClient()

    @Test
    fun plainStringContent() {
        val body = """
            {"id":"x","choices":[{"message":{"role":"assistant","content":"你好，世界。"}}]}
        """.trimIndent()
        assertEquals("你好，世界。", client.extractText(body))
    }

    @Test
    fun arrayContentIsJoined() {
        val body = """
            {"choices":[{"message":{"content":[{"type":"text","text":"你好"},{"type":"text","text":"，世界"}]}}]}
        """.trimIndent()
        assertEquals("你好，世界", client.extractText(body))
    }

    @Test
    fun codeFenceIsStripped() {
        val body = """
            {"choices":[{"message":{"content":"```text\n你好\n```"}}]}
        """.trimIndent()
        assertEquals("你好", client.extractText(body))
    }

    @Test
    fun surroundingQuotesAndBracketsAreStripped() {
        assertEquals("你好", client.extractText(bodyWith("「你好」")))
        assertEquals("你好", client.extractText(bodyWith("\u201C你好\u201D")))
        assertEquals("你好", client.extractText(bodyWith("\"你好\"")))
    }

    @Test
    fun multilineContentKeepsLineBreaks() {
        val text = "第一段\n\n*她笑了笑*"
        assertEquals(text, client.extractText(bodyWith(text)))
    }

    @Test
    fun badJsonYieldsEmptyText() {
        // 解析失败不抛异常：调用方用 "empty translation" 统一归类为 EMPTY_RESULT
        assertEquals("", client.extractText("not json"))
    }

    @Test
    fun emptyChoicesYieldsEmptyText() {
        assertEquals("", client.extractText("""{"choices":[]}"""))
    }

    private fun bodyWith(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }
}
