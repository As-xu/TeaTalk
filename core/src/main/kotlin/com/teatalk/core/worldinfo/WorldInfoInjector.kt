package com.teatalk.core.worldinfo

/**
 * 世界书注入（M3 步骤 1）。
 *
 * ST 的注入位置语义（world info 条目 `position`，见 `docs/research/02-data-compat.md`）：
 * - 0 = 角色定义之前（before char）
 * - 1 = 角色定义之后（after char）
 * - 2 = Author's Note 顶部
 * - 3 = Author's Note 底部
 * - 4 = 按 depth 插进聊天历史（role 决定以谁的身份插入）
 *
 * 本实现的映射（2026-10-29 对齐 ST 修正）：
 * - 0/1 → 拼进 system prompt 的前/后两段
 * - 2/3 → 插在 Author's Note 位置。TeaTalk 没有 AN 段，收敛为「倒数第 [AN_DEPTH] 条消息之内」：
 *   同一点上先 AN 顶、再 at-depth、最后 AN 底（由 [Insertion.slot] 决定）
 * - 4 → 按 ST depth 语义插入：`0` = 最后一条消息**之后**，`d` = 倒数第 d 条消息**之前**
 * - 5/6/7（示例消息/作者注释等）本版不注入，由 [select] 过滤，调用方记日志说明丢弃数
 *
 * 明确不做（后续）：递归扫描、sticky/cooldown、向量召回、token 级预算（现在是字符预算）。
 */
object WorldInfoInjector {

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /** 本版支持注入的 position；其余（5/6/7）只统计不注入。 */
    val SUPPORTED_POSITIONS = 0..4

    /** Author's Note 的默认深度：锚在倒数第 4 条消息（ST 默认 4）。 */
    const val AN_DEPTH = 4

    /** 默认世界书字符预算（与旧 `WorldInfoEngine` 默认一致）。 */
    const val DEFAULT_CHAR_BUDGET = 8000

    /**
     * 一条待插入历史的消息。
     *
     * [depthFromEnd] 与 ST `depth` 同义：`0` = 紧贴最后一条消息之后；`d` = 插在倒数第 `d` 条消息之前。
     * [slot] 只用于同一点内部的先后：0 = AN 顶，1 = at-depth，2 = AN 底。
     */
    data class Insertion(
        val role: String,
        val content: String,
        val depthFromEnd: Int,
        val order: Int,
        val slot: Int = SLOT_AT_DEPTH,
    )

    /** 用极简引擎跑一遍关键词/常量匹配，返回按 order 降序的命中条目（已按预算截断）。 */
    fun select(
        entries: List<WorldInfoEntry>,
        scanText: String,
        charBudget: Int = DEFAULT_CHAR_BUDGET,
    ): List<WorldInfoEntry> = WorldInfoEngine(
        entries.filter { it.position in SUPPORTED_POSITIONS },
        charBudget,
    ).activated(scanText)

    /** 本条书里有多少条目因 position 不受支持被跳过（供调用方记日志）。 */
    fun unsupportedCount(entries: List<WorldInfoEntry>): Int =
        entries.count { it.position !in SUPPORTED_POSITIONS }

    /** 条目渲染成注入文本；保留条目注释便于模型与调试面板辨认来源。 */
    fun render(entry: WorldInfoEntry): String {
        val title = entry.comment.trim()
        return if (title.isEmpty()) entry.content.trim() else "【$title】\n${entry.content.trim()}"
    }

    /**
     * 把所有条目渲染为一个块；按 ST 语义升序拼接（ST 以 `order 999→1` 循环 + `unshift`，
     * 最终文本里 order 小的在前）。
     */
    fun block(entries: List<WorldInfoEntry>): String = entries
        .sortedWith(compareBy({ it.order }, { it.uid }))
        .map { render(it) }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

    /** position 0：角色定义之前的条目（按 ST 顺序升序）。 */
    fun beforeCharacter(entries: List<WorldInfoEntry>): List<WorldInfoEntry> =
        entries.filter { it.position == 0 }.sortedWith(compareBy({ it.order }, { it.uid }))

    /** position 1：角色定义之后的条目（按 ST 顺序升序）。 */
    fun afterCharacter(entries: List<WorldInfoEntry>): List<WorldInfoEntry> =
        entries.filter { it.position == 1 }.sortedWith(compareBy({ it.order }, { it.uid }))

    /** position 2/3/4：需要插进聊天历史的条目。 */
    fun historyInsertions(entries: List<WorldInfoEntry>): List<Insertion> =
        entries.mapNotNull { entry ->
            val role = when (entry.role) {
                ROLE_USER_INDEX -> ROLE_USER
                ROLE_ASSISTANT_INDEX -> ROLE_ASSISTANT
                else -> ROLE_SYSTEM
            }
            when (entry.position) {
                // AN 顶部 / 底部：锚在 AN_DEPTH 处，槽位保证 顶 → at-depth → 底。
                2 -> Insertion(ROLE_SYSTEM, render(entry), AN_DEPTH, entry.order, SLOT_AN_TOP)
                3 -> Insertion(ROLE_SYSTEM, render(entry), AN_DEPTH, entry.order, SLOT_AN_BOTTOM)
                4 -> Insertion(role, render(entry), entry.depth.coerceAtLeast(0), entry.order, SLOT_AT_DEPTH)
                else -> null
            }
        }

    /**
     * 把插入项塞进历史。
     *
     * 约定：`history` 已含本次用户消息（最后一条）。
     * 插入点 = `size - depthFromEnd`，夹取到 `0..size`；`depth = 0` 即插在最后一条之后。
     * 同一点内按 slot（AN 顶 → at-depth → AN 底）再按 order 升序排列。
     */
    fun injectHistory(
        history: List<Pair<String, String>>,
        insertions: List<Insertion>,
    ): List<Pair<String, String>> {
        if (insertions.isEmpty()) return history
        val size = history.size
        // 同一插入点的条目：slot 优先，order 次之（ST unshift 后文本升序）。
        val grouped = insertions.groupBy { (size - it.depthFromEnd).coerceIn(0, size) }
        val ordered = grouped.mapValues { (_, items) -> items.sortedWith(compareBy({ it.slot }, { it.order })) }

        val out = ArrayList<Pair<String, String>>(size + insertions.size)
        for (index in 0..size) {
            ordered[index]?.forEach { out += it.role to it.content }
            if (index < size) out += history[index]
        }
        return out
    }

    /** 估算条目成本（字符），供调试面板展示。 */
    fun cost(entries: List<WorldInfoEntry>): Int = entries.sumOf { render(it).length }

    const val SLOT_AN_TOP = 0
    const val SLOT_AT_DEPTH = 1
    const val SLOT_AN_BOTTOM = 2

    private const val ROLE_USER_INDEX = 1
    private const val ROLE_ASSISTANT_INDEX = 2
}
