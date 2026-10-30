package com.teatalk.xs.data

/**
 * 把角色卡 + 会话历史组装成 OpenAI 兼容的 messages。
 *
 * 结构以真实 SillyTavern 请求体为准（见 docs/11-golden-cases.md）：
 * 1. 卡 `system_prompt`；为空时用 ST 的默认句「Write {char}'s next reply in a fictional chat between ...」
 * 2. 世界书 before_char 块（若启用）
 * 3. description / personality / scenario 各成一条 system
 * 4. `mes_example` 解析成 `[Example Chat]` + 每行一条 system
 * 5. `[Start a new Chat]` 起点标记
 * 6. 历史（开场白在 assistant、用户消息在 user）
 * 7. `post_history_instructions` 作为最后一条 system
 *
 * 纯函数、不碰数据库与线程，便于用 golden case 做等值断言。
 */
object PromptAssembler {

    const val ROLE_SYSTEM = "system"
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    const val EXAMPLE_CHAT_MARKER = "[Example Chat]"
    const val CHAT_START_MARKER = "[Start a new Chat]"

    /** 历史消息（已归一化为 OpenAI 角色）。 */
    data class HistoryMessage(val role: String, val content: String)

    fun assemble(
        card: CardView,
        characterName: String,
        userName: String = "User",
        history: List<HistoryMessage>,
        beforeWorld: List<String> = emptyList(),
        afterWorld: List<String> = emptyList(),
    ): List<Pair<String, String>> {
        val name = card.name.ifBlank { characterName }
        val out = ArrayList<Pair<String, String>>(history.size + 8)

        // 1. system prompt（卡内为空时回退到 ST 默认句）
        val system = card.systemPrompt.trim().ifBlank {
            "Write $name's next reply in a fictional chat between $name and $userName."
        }
        out += ROLE_SYSTEM to substitute(system, name, userName)

        // 2. 世界书 before_char 块（ST 把它作为独立 system 消息放在角色定义之前）
        if (beforeWorld.isNotEmpty()) {
            out += ROLE_SYSTEM to beforeWorld.joinToString("\n") { substitute(it, name, userName) }
        }

        // 3. 角色定义三段
        if (card.description.isNotBlank()) {
            out += ROLE_SYSTEM to substitute(card.description.trim(), name, userName)
        }
        if (card.personality.isNotBlank()) {
            out += ROLE_SYSTEM to substitute(card.personality.trim(), name, userName)
        }
        if (card.scenario.isNotBlank()) {
            out += ROLE_SYSTEM to substitute(card.scenario.trim(), name, userName)
        }

        // 4. 示例对话：每个 <START> 块一个标记 + 每行一条 system
        parseExampleBlocks(card.mesExample).forEach { block ->
            if (block.isNotEmpty()) {
                out += ROLE_SYSTEM to EXAMPLE_CHAT_MARKER
                block.forEach { line ->
                    // ST 在自定义 chat-completion 来源下把示例行压成 system 内容：
                    // 会去掉 "{{user}}: " / "{{char}}: " 这类说话人前缀，仅保留正文。
                    out += ROLE_SYSTEM to substitute(stripExampleSpeaker(line), name, userName)
                }
            }
        }

        // 5. 起点标记
        out += ROLE_SYSTEM to CHAT_START_MARKER

        // 6. 历史
        history.forEach { message ->
            val role = when (message.role) {
                ROLE_USER -> ROLE_USER
                ROLE_SYSTEM -> ROLE_SYSTEM
                else -> ROLE_ASSISTANT
            }
            out += role to message.content
        }

        // 7. post_history_instructions 放在最后
        if (card.postHistoryInstructions.isNotBlank()) {
            out += ROLE_SYSTEM to substitute(card.postHistoryInstructions.trim(), name, userName)
        }

        // 8. 世界书 after_char 块（ST 放在角色定义之后；这里紧跟角色段，位于示例之前）
        if (afterWorld.isNotEmpty()) {
            val index = out.indexOfFirst { it.first == ROLE_SYSTEM && it.second == CHAT_START_MARKER }
            val insertAt = if (index >= 0) index else out.size
            val block = ROLE_SYSTEM to afterWorld.joinToString("\n") { substitute(it, name, userName) }
            out.add(insertAt, block)
        }
        return out
    }

    /**
     * 把 mes_example 拆成若干「对话块」：按 `<START>` 分割，块内去掉空行。
     * 返回结构只保留非空块；空字符串返回空列表。
     */
    fun parseExampleBlocks(mesExample: String): List<List<String>> {
        if (mesExample.isBlank()) return emptyList()
        return mesExample.split(Regex("<START>", RegexOption.IGNORE_CASE))
            .map { block ->
                block.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            .filter { it.isNotEmpty() }
    }

    private val SPEAKER_PREFIX = Regex(
        "^(?:\\{\\{user\\}\\}|\\{\\{char\\}\\}|<USER>|<CHAR>|user|char)\\s*:\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** 去掉示例行开头的说话人标记（`{{user}}:` / `{{char}}:` / `User:` / `Char:`）。 */
    fun stripExampleSpeaker(line: String): String = line.replaceFirst(SPEAKER_PREFIX, "")

    /** ST 的宏替换：只处理比赛用的两种写法，避免过度替换文本里的普通花括号。 */
    fun substitute(text: String, characterName: String, userName: String): String =
        text.replace("{{char}}", characterName)
            .replace("<CHAR>", characterName)
            .replace("{{user}}", userName)
            .replace("<USER>", userName)
}
