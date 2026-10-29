package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldBookMergerTest {

    private fun entry(uid: Int, comment: String = "", content: String = "内容:$uid") = WorldInfoEntry(
        uid = uid,
        comment = comment,
        content = content,
    )

    @Test
    fun `不同世界书的同 uid 条目都保留`() {
        val merged = WorldBookMerger.merge(
            card = emptyList(),
            library = listOf(
                SourcedWorldInfoEntry(1L, entry(uid = 0, content = "苹果设定")),
                SourcedWorldInfoEntry(2L, entry(uid = 0, content = "香蕉设定")),
            ),
        )
        assertEquals(listOf("苹果设定", "香蕉设定"), merged.map { it.content })
    }

    @Test
    fun `同一本书内同 uid 同 comment 的条目收敛为一条`() {
        val merged = WorldBookMerger.merge(
            card = emptyList(),
            library = listOf(
                SourcedWorldInfoEntry(1L, entry(uid = 0, comment = "甲")),
                SourcedWorldInfoEntry(1L, entry(uid = 0, comment = "甲", content = "重复")),
            ),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `卡内嵌与库书同 uid 都保留`() {
        val merged = WorldBookMerger.merge(
            card = listOf(entry(uid = 0, content = "卡内设定")),
            library = listOf(SourcedWorldInfoEntry(1L, entry(uid = 0, content = "库书设定"))),
        )
        assertEquals(listOf("卡内设定", "库书设定"), merged.map { it.content })
    }

    @Test
    fun `跨源正文相同也各自保留（不再按 content 去重）`() {
        val merged = WorldBookMerger.merge(
            card = listOf(entry(uid = 5, content = "同一段文本")),
            library = listOf(SourcedWorldInfoEntry(9L, entry(uid = 7, content = "同一段文本"))),
        )
        assertEquals(2, merged.size)
    }
}
