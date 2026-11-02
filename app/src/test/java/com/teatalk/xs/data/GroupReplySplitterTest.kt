package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupReplySplitterTest {

    private val members = listOf("Aria", "Bram", "小茶")

    @Test
    fun splitsTwoSpeakersAndStripsPrefixes() {
        val reply = """
            Aria: 先把窗关上，风有点大。
            Bram: 好，我这就去。
        """.trimIndent()

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = "Aria")

        assertEquals(2, segments.size)
        assertEquals("Aria", segments[0].speakerName)
        assertEquals("先把窗关上，风有点大。", segments[0].text)
        assertEquals("Bram", segments[1].speakerName)
        assertEquals("好，我这就去。", segments[1].text)
    }

    @Test
    fun keepsContinuationLinesWithTheCurrentSpeaker() {
        val reply = """
            Aria: （抬头看了一眼）
            晚课都结束快一个小时了。
            Bram: 我在楼下等你。
            三分钟，迟了我就走。
        """.trimIndent()

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = "Aria")

        assertEquals(2, segments.size)
        assertEquals("（抬头看了一眼）\n晚课都结束快一个小时了。", segments[0].text)
        assertEquals("我在楼下等你。\n三分钟，迟了我就走。", segments[1].text)
    }

    @Test
    fun withoutNamePrefixEverythingBelongsToFallbackSpeaker() {
        val reply = "这里是图书馆的窗边，夕阳把湖面照得金灿灿的。"

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = "Bram")

        assertEquals(1, segments.size)
        assertEquals("Bram", segments.single().speakerName)
    }

    @Test
    fun doesNotSplitWhenNameOnlyAppearsInsideTheLine() {
        val reply = "Aria: 她转头说 Bram: 别磨蹭了，这里只是引述。"

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = "Aria")

        assertEquals(1, segments.size)
        // 行首是 Aria，正文里的 "Bram:" 属于引述，不拆
        assertEquals("她转头说 Bram: 别磨蹭了，这里只是引述。", segments.single().text)
    }

    @Test
    fun mergesAdjacentSegmentsByTheSameSpeaker() {
        val reply = """
            小茶：先坐会儿。
            小茶：茶还热着。
        """.trimIndent()

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = null)

        assertEquals(1, segments.size)
        assertEquals("小茶", segments.single().speakerName)
        assertEquals("先坐会儿。\n茶还热着。", segments.single().text)
    }

    @Test
    fun toleratesUnknownSpeakerPrefix() {
        val reply = """
            Aria: 我在。
            陌生人：打扰了。
        """.trimIndent()

        val segments = GroupReplySplitter.split(reply, members, fallbackSpeaker = "Aria")

        assertEquals(1, segments.size)
        assertEquals("我在。\n陌生人：打扰了。", segments.single().text)
    }

    @Test
    fun blankReplyProducesNoSegments() {
        assertEquals(0, GroupReplySplitter.split("   \n ", members, "Aria").size)
    }
}
