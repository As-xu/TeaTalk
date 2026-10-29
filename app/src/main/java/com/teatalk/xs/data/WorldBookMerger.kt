package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry

/**
 * 一条带来源（角色卡 / 某本世界书）的条目。
 *
 * `uid` 只是「书内」命名空间：卡内嵌条目与每本库书都从 0/1 起，
 * 所以身份必须是 `(sourceId, uid, comment)`，不能只用 uid。
 */
data class SourcedWorldInfoEntry(
    val sourceId: Long,
    val entry: WorldInfoEntry,
)

/** 卡内嵌条目的来源 id；真实世界书用 `world_books.id`（自增，从 1 起）。 */
const val CARD_SOURCE_ID: Long = 0L

object WorldBookMerger {

    /**
     * 合并卡内嵌条目与已绑定世界书的条目。
     *
     * 只按 `(sourceId, uid, comment)` 去重：同一本书里重复的条目会被收敛，
     * 不同来源（卡 vs 书、书 vs 书）的条目即使 uid / 正文相同也都会保留。
     * 跨源同文去重曾在 0.8.1 里杀掉「唯一命中的那条」，这里明确不做。
     */
    fun merge(
        card: List<WorldInfoEntry>,
        library: List<SourcedWorldInfoEntry>,
    ): List<WorldInfoEntry> =
        (card.map { SourcedWorldInfoEntry(CARD_SOURCE_ID, it) } + library)
            .distinctBy { Triple(it.sourceId, it.entry.uid, it.entry.comment.trim()) }
            .map { it.entry }
}
