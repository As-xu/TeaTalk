package com.teatalk.xs.data

/**
 * 把「更早的历史消息」压成一段确定性的摘要，注入到 prompt 里。
 *
 * 设计取舍：
 * - 不额外调模型（本地确定性、可测、零额外费用），只做「抽样 + 截断」；
 * - 目的是让模型知道 20 条窗口之外发生过什么，而不是精确复述；
 * - 纯函数，方便单测与 golden 断言。
 */
object MemoryDigest {

    /** 摘要最多取多少条抽样消息。 */
    const val MAX_LINES = 10

    /** 单条消息最多保留多少字符。 */
    const val MAX_LINE_CHARS = 140

    /** 整个摘要的字符上限（超过就丢尾部）。 */
    const val MAX_TOTAL_CHARS = 1200

    const val HEADER = "[Earlier conversation]"

    /**
     * @param older 更早的历史（顺序：旧 → 新），元素为 (OpenAI 角色, 正文)。
     * @return 摘要文本；没有可用内容时返回 null。
     */
    fun build(older: List<Pair<String, String>>): String? {
        val usable = older.filter { it.second.isNotBlank() }
        if (usable.isEmpty()) return null
        val step = ((usable.size + MAX_LINES - 1) / MAX_LINES).coerceAtLeast(1)
        val sampled = ArrayList<Pair<String, String>>(MAX_LINES)
        var index = usable.size - 1
        // 从最新的往回抽样，保证「越近的事越可能被保留」
        while (index >= 0 && sampled.size < MAX_LINES) {
            sampled += usable[index]
            index -= step
        }
        if (sampled.isEmpty()) return null
        sampled.reverse()

        val builder = StringBuilder(HEADER)
        for ((role, content) in sampled) {
            val line = "$HEADER_LINE_PREFIX${label(role)}: ${oneLine(content)}"
            if (builder.length + line.length > MAX_TOTAL_CHARS) break
            builder.append('\n').append(line)
        }
        return builder.toString()
    }

    private const val HEADER_LINE_PREFIX = "- "

    private fun label(role: String): String = when (role) {
        PromptAssembler.ROLE_USER -> "User"
        PromptAssembler.ROLE_ASSISTANT -> "Assistant"
        else -> "Note"
    }

    /** 摘要里每条消息压成一行，避免把整段换行带进 prompt。 */
    private fun oneLine(content: String): String {
        val flat = content.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= MAX_LINE_CHARS) flat else flat.take(MAX_LINE_CHARS) + "…"
    }
}
