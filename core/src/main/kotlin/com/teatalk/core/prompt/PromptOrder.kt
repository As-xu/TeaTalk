package com.teatalk.core.prompt

/**
 * 提示词顺序编排：只做「顺序与角色」这一件事，不负责取数据、不负责格式化文字。
 *
 * SillyTavern 的请求顺序（简化后）：
 * system 设定 → 示例消息（mes_example） → 历史（含 at-depth 世界书） → 历史后指令（post_history_instructions）
 *
 * 这里把顺序收敛成纯函数，方便单测与后续 golden case 对拍。
 */
object PromptOrder {

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /** 示例对话里可能出现的说话人前缀（大小写不敏感）。 */
    private val speaker = Regex(
        """^\s*(\{\{user\}\}|\{\{char\}\}|<USER>|<BOT>|user|char|you|assistant)\s*:\s?(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val startMarker = Regex("""<START>""", RegexOption.IGNORE_CASE)

    /**
     * 把角色卡的 `mes_example` 解析成示例轮次。
     *
     * 支持两种常见写法：
     * - `{{user}}: ...` / `{{char}}: ...`（多行内容归属当前说话人，直到下一个前缀）
     * - `<START>` 分段：每段独立解析
     *
     * 无法识别任何说话人时返回空列表，调用方应回退到「作为 system 文本塞入」的兼容路径。
     */
    fun parseExamples(mesExample: String): List<Pair<String, String>> {
        if (mesExample.isBlank()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        startMarker.split(mesExample).forEach { segment ->
            val lines = segment.lines()
            var currentRole: String? = null
            val buffer = StringBuilder()
            fun flush() {
                val text = buffer.toString().trim()
                val role = currentRole
                if (role != null && text.isNotEmpty()) result += role to text
                buffer.setLength(0)
            }
            lines.forEach { line ->
                val match = speaker.find(line)
                if (match != null) {
                    flush()
                    currentRole = when (match.groupValues[1].lowercase()) {
                        "{{user}}", "<user>", "user", "you" -> ROLE_USER
                        else -> ROLE_ASSISTANT
                    }
                    buffer.append(match.groupValues[2])
                } else if (currentRole != null) {
                    buffer.append('\n').append(line)
                }
            }
            flush()
        }
        return result
    }

    /**
     * 组装最终请求消息列表：
     * `[system] + examples + history + [postHistoryInstruction as system]`
     *
     * 空内容会被跳过；`history` 里已经是 `role to content` 的成对数据（含 at-depth 注入后的结果）。
     */
    fun assemble(
        system: String,
        examples: List<Pair<String, String>>,
        history: List<Pair<String, String>>,
        postHistoryInstruction: String?,
    ): List<Pair<String, String>> = buildList {
        if (system.isNotBlank()) add(ROLE_SYSTEM to system)
        examples.forEach { (role, content) -> if (content.isNotBlank()) add(role to content) }
        history.forEach { (role, content) -> if (content.isNotBlank()) add(role to content) }
        // ST 语义：post_history_instructions 位于整段历史之后，作为最后一条 system 消息。
        val phi = postHistoryInstruction?.trim()
        if (!phi.isNullOrEmpty()) add(ROLE_SYSTEM to phi)
    }
}
