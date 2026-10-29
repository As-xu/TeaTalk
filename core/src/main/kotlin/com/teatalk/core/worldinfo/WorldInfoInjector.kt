package com.teatalk.core.worldinfo

/**
 * 世界书注入（M3 步骤 1）。
 *
 * ST 的注入位置语义（`worldinfo` 条目 `position`）：
 * - 0 = 角色定义之前（before char）
 * - 1 = 角色定义之后（after char）
 * - 2 = Author's Note 顶部
 * - 3 = Author's Note 底部
 * - 4 = 按 depth 插进聊天历史（role 决定以谁的身份插入）
 *
 * TeaTalk 的落地映射：
 * - 0/1 → 拼进 system prompt（前/后两段，顺序沿用引擎的 order 降序）
 * - 2 → 历史最前面插一条 system
 * - 3 → 历史最后（本次用户消息之前）插一条 system
 * - 4 → 从倒数第 `depth` 条之前插入，role 0/1/2 → system/user/assistant
 *
 * 明确不做（后续）：递归扫描、sticky/cooldown、向量召回、token 级预算（现在是字符预算）。
 */
object WorldInfoInjector {

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /** 默认世界书字符预算（与旧 `WorldInfoEngine` 默认一致）。 */
    const val DEFAULT_CHAR_BUDGET = 8000

    /** 一条待插入历史的消息。 */
    data class Insertion(
        val role: String,
        val content: String,
        /** 从历史末尾数起的位置：0 表示插在最后一条之前（即本次用户消息前）。 */
        val depthFromEnd: Int,
        /** 引擎排序值（越大越靠前），用于同深度时的稳定排序。 */
        val order: Int,
    )

    /** 用极简引擎跑一遍关键词/常量匹配，返回按 order 降序的命中条目（已按预算截断）。 */
    fun select(
        entries: List<WorldInfoEntry>,
        scanText: String,
        charBudget: Int = DEFAULT_CHAR_BUDGET,
    ): List<WorldInfoEntry> = WorldInfoEngine(entries, charBudget).activated(scanText)

    /** 条目渲染成注入文本；保留条目注释便于模型与调试面板辨认来源。 */
    fun render(entry: WorldInfoEntry): String {
        val title = entry.comment.trim()
        return if (title.isEmpty()) entry.content.trim() else "【$title】\n${entry.content.trim()}"
    }

    /** 把所有条目渲染为一个块；空列表返回空串。 */
    fun block(entries: List<WorldInfoEntry>): String =
        entries.map { render(it) }.filter { it.isNotBlank() }.joinToString("\n\n")

    /** position 0：角色定义之前的条目（保持传入顺序）。 */
    fun beforeCharacter(entries: List<WorldInfoEntry>): List<WorldInfoEntry> =
        entries.filter { it.position == 0 }

    /** position 1：角色定义之后的条目。 */
    fun afterCharacter(entries: List<WorldInfoEntry>): List<WorldInfoEntry> =
        entries.filter { it.position == 1 }

    /** position 2/3/4：需要插进聊天历史的条目。 */
    fun historyInsertions(entries: List<WorldInfoEntry>): List<Insertion> =
        entries.mapNotNull { entry ->
            val role = when (entry.role) {
                ROLE_USER_INDEX -> ROLE_USER
                ROLE_ASSISTANT_INDEX -> ROLE_ASSISTANT
                else -> ROLE_SYSTEM
            }
            when (entry.position) {
                // AN 顶部：历史最前面（depth 用一个足够大的值表示“开头”）。
                2 -> Insertion(ROLE_SYSTEM, render(entry), Int.MAX_VALUE / 2, entry.order)
                // AN 底部：紧贴本次用户消息之前。
                3 -> Insertion(ROLE_SYSTEM, render(entry), 0, entry.order)
                // 按深度插入。
                4 -> Insertion(role, render(entry), entry.depth.coerceAtLeast(0), entry.order)
                else -> null
            }
        }

    /**
     * 把插入项塞进历史。
     *
     * 约定：`history` 已含本次用户消息（最后一条）。`depthFromEnd = 0` 表示插在它之前。
     * 同深度的多条按 order 降序、再按原下标稳定排列。
     */
    fun injectHistory(
        history: List<Pair<String, String>>,
        insertions: List<Insertion>,
    ): List<Pair<String, String>> {
        if (insertions.isEmpty() || history.isEmpty()) return history
        val grouped = insertions.groupBy { it.depthFromEnd }
        val out = ArrayList<Pair<String, String>>(history.size + insertions.size)
        // 超出历史长度的深度（如 AN 顶部）统一放到最前面。
        grouped.filterKeys { it >= history.size }
            .values.flatten()
            .sortedByDescending { it.order }
            .forEach { out += it.role to it.content }
        for ((index, message) in history.withIndex()) {
            // depthFromEnd 语义：0 = 紧贴最后一条之前，1 = 倒数第二条之前……
            val fromEnd = history.size - 1 - index
            grouped[fromEnd]?.sortedByDescending { it.order }?.forEach { out += it.role to it.content }
            out += message
        }
        return out
    }

    /** 估算条目成本（字符），供调试面板展示。 */
    fun cost(entries: List<WorldInfoEntry>): Int = entries.sumOf { render(it).length }

    private const val ROLE_USER_INDEX = 1
    private const val ROLE_ASSISTANT_INDEX = 2
}
