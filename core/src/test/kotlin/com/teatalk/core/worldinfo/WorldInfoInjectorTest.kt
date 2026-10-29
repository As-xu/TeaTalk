package com.teatalk.core.worldinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 世界书注入的纯逻辑测试（不依赖 Android）。 */
class WorldInfoInjectorTest {

    private fun entry(
        comment: String,
        content: String = "内容:$comment",
        position: Int = 0,
        depth: Int = 4,
        role: Int = 0,
        order: Int = 100,
        keys: List<String> = listOf(comment),
    ) = WorldInfoEntry(
        uid = comment.hashCode(),
        comment = comment,
        keys = keys,
        content = content,
        position = position,
        depth = depth,
        role = role,
        order = order,
    )

    @Test
    fun render_keeps_comment_title() {
        assertEquals("【茶馆】\n内容:茶馆", WorldInfoInjector.render(entry("茶馆")))
    }

    @Test
    fun positions_split_before_after_and_history() {
        val before = entry("前", position = 0)
        val after = entry("后", position = 1)
        val anTop = entry("AN顶", position = 2)
        val anBottom = entry("AN底", position = 3)
        val deep = entry("深度", position = 4, depth = 2, role = 1)

        assertEquals(listOf(before), WorldInfoInjector.beforeCharacter(listOf(before, after, anTop, anBottom, deep)))
        assertEquals(listOf(after), WorldInfoInjector.afterCharacter(listOf(before, after, anTop, anBottom, deep)))
        val insertions = WorldInfoInjector.historyInsertions(listOf(before, after, anTop, anBottom, deep))
        assertEquals(3, insertions.size)
        // AN 顶部/底部都锚在 AN_DEPTH，靠 slot 决定先后（顶 → at-depth → 底）。
        val top = insertions.first { it.content.contains("AN顶") }
        val bottom = insertions.first { it.content.contains("AN底") }
        assertEquals(WorldInfoInjector.AN_DEPTH, top.depthFromEnd)
        assertEquals(WorldInfoInjector.SLOT_AN_TOP, top.slot)
        assertEquals(WorldInfoInjector.AN_DEPTH, bottom.depthFromEnd)
        assertEquals(WorldInfoInjector.SLOT_AN_BOTTOM, bottom.slot)
        // at-depth 用条目自己的 depth。
        assertEquals(2, insertions.first { it.content.contains("深度") }.depthFromEnd)
        assertEquals(WorldInfoInjector.SLOT_AT_DEPTH, insertions.first { it.content.contains("深度") }.slot)
    }

    @Test
    fun history_role_mapping() {
        val asUser = entry("u", position = 4, role = 1)
        val asAssistant = entry("a", position = 4, role = 2)
        val asSystem = entry("s", position = 4, role = 0)
        val insertions = WorldInfoInjector.historyInsertions(listOf(asUser, asAssistant, asSystem))
        assertEquals("user", insertions.first { it.content.contains("u") }.role)
        assertEquals("assistant", insertions.first { it.content.contains("a") }.role)
        assertEquals("system", insertions.first { it.content.contains("s") }.role)
    }

    @Test
    fun inject_history_inserts_at_depth_before_message() {
        val history = listOf(
            "user" to "第一句",
            "assistant" to "第二句",
            "user" to "第三句",
        )
        // ST 语义：depth=0 表示插在最后一条消息【之后】。
        val injected = WorldInfoInjector.injectHistory(
            history,
            listOf(WorldInfoInjector.Insertion("system", "贴底", 0, 100)),
        )
        assertEquals(4, injected.size)
        assertEquals("user" to "第三句", injected[2])
        assertEquals("system" to "贴底", injected[3])

        // depth=1 表示插在倒数第 1 条（第三句）之前。
        val injected2 = WorldInfoInjector.injectHistory(
            history,
            listOf(WorldInfoInjector.Insertion("system", "倒数第二", 1, 100)),
        )
        assertEquals("assistant" to "第二句", injected2[1])
        assertEquals("system" to "倒数第二", injected2[2])
    }

    @Test
    fun inject_history_orders_same_depth_by_engine_order_asc() {
        val history = listOf("user" to "hi")
        val injected = WorldInfoInjector.injectHistory(
            history,
            listOf(
                WorldInfoInjector.Insertion("system", "低", 0, 10),
                WorldInfoInjector.Insertion("system", "高", 0, 99),
            ),
        )
        // ST 以 order 999→1 循环 + unshift，最终文本里 order 小的在前。
        assertEquals(listOf("user" to "hi", "system" to "低", "system" to "高"), injected)
    }

    @Test
    fun inject_history_clamps_out_of_range_depth() {
        val history = listOf("user" to "a", "assistant" to "b")
        // depth 超大 → 夹到最前；负 depth（防御）→ 夹到最后。
        val injected = WorldInfoInjector.injectHistory(
            history,
            listOf(
                WorldInfoInjector.Insertion("system", "最前", 99, 1),
                WorldInfoInjector.Insertion("system", "最后", -3, 1),
            ),
        )
        assertEquals("system" to "最前", injected[0])
        assertEquals("system" to "最后", injected.last())
    }

    @Test
    fun inject_history_works_on_empty_history() {
        val injected = WorldInfoInjector.injectHistory(
            emptyList(),
            listOf(WorldInfoInjector.Insertion("system", "只有设定", 0, 1)),
        )
        assertEquals(listOf("system" to "只有设定"), injected)
    }

    @Test
    fun block_orders_entries_ascending_like_st() {
        val low = entry("低", order = 5)
        val high = entry("高", order = 500)
        val text = WorldInfoInjector.block(listOf(high, low))
        assertTrue(text.indexOf("【低】") < text.indexOf("【高】"))
    }

    @Test
    fun select_drops_unsupported_positions() {
        val supported = entry("支持", position = 4, depth = 0)
        val unsupported = entry("示例", position = 5)
        val selected = WorldInfoInjector.select(listOf(supported, unsupported), "支持")
        assertEquals(listOf(supported), selected)
        assertEquals(1, WorldInfoInjector.unsupportedCount(listOf(supported, unsupported)))
    }

    @Test
    fun block_joins_rendered_entries() {
        val text = WorldInfoInjector.block(listOf(entry("一"), entry("二")))
        assertTrue(text.contains("【一】"))
        assertTrue(text.contains("【二】"))
        assertTrue(text.indexOf("【一】") < text.indexOf("【二】"))
    }

    @Test
    fun cost_sums_rendered_length() {
        val entries = listOf(entry("a"), entry("b"))
        assertEquals(
            WorldInfoInjector.render(entries[0]).length + WorldInfoInjector.render(entries[1]).length,
            WorldInfoInjector.cost(entries),
        )
    }

    @Test
    fun select_respects_keywords_and_budget() {
        val hit = entry("茶", keys = listOf("茶"))
        val miss = entry("雨", keys = listOf("雨"))
        val selected = WorldInfoInjector.select(listOf(hit, miss), "来一杯茶吧")
        assertEquals(listOf(hit), selected)
    }
}
