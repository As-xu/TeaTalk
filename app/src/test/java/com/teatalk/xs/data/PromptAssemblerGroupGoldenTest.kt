package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群聊 prompt 的「全量等值」golden：把整条消息列表逐条钉死。
 *
 * 与 [PromptAssemblerGroupTest] 的结构断言互补：结构测试跟随实现演进，golden 只在
 * 有意改变群聊拼装顺序/文案时才更新，避免顺序或分隔符悄悄漂移。
 * 期望值按 SillyTavern 的 group 行为手写（群说明 → before 世界书 → 每成员块 →
 * after 世界书 → [Start a new Chat] → 历史 → 每成员 PHI）。
 */
class PromptAssemblerGroupGoldenTest {

    private fun card(
        name: String,
        system: String = "",
        description: String = "",
        personality: String = "",
        scenario: String = "",
        phi: String = "",
        mesExample: String = "",
    ) = CardView(
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        mesExample = mesExample,
        systemPrompt = system,
        postHistoryInstructions = phi,
    )

    @Test
    fun `golden case A - neutral header with world info and one phi`() {
        val out = PromptAssembler.assembleGroup(
            members = listOf(
                PromptAssembler.GroupMember(
                    "Aria",
                    card(
                        name = "Aria",
                        system = "SYS-A",
                        description = "desc-a {{char}}/{{user}}",
                        personality = "pers-a",
                        scenario = "scene-a",
                        mesExample = "{{user}}: ping\n{{char}}: pong",
                    ),
                ),
                PromptAssembler.GroupMember(
                    "Bram",
                    card(name = "Bram", personality = "pers-b", phi = "phi-b {{char}}"),
                ),
            ),
            userName = "User",
            history = listOf(
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_USER, "hello {{user}}"),
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_ASSISTANT, "Aria: hi"),
            ),
            beforeWorld = listOf("before {{char}}"),
            afterWorld = listOf("after {{char}}"),
        )

        val expected = listOf(
            PromptAssembler.ROLE_SYSTEM to
                "This group chat includes: Aria, Bram. Write the next reply in a fictional group chat " +
                "between Aria, Bram and User. Answer only as one of them, and never put a character " +
                "name before the reply. Do not speak or act for the other members.",
            PromptAssembler.ROLE_SYSTEM to "before Aria, Bram",
            PromptAssembler.ROLE_SYSTEM to "SYS-A",
            PromptAssembler.ROLE_SYSTEM to "desc-a Aria/User",
            PromptAssembler.ROLE_SYSTEM to "pers-a",
            PromptAssembler.ROLE_SYSTEM to "scene-a",
            PromptAssembler.ROLE_SYSTEM to "Write Bram's next reply in a fictional chat between Bram and User.",
            PromptAssembler.ROLE_SYSTEM to "pers-b",
            PromptAssembler.ROLE_SYSTEM to "after Aria, Bram",
            PromptAssembler.ROLE_SYSTEM to PromptAssembler.CHAT_START_MARKER,
            PromptAssembler.ROLE_USER to "hello {{user}}",
            PromptAssembler.ROLE_ASSISTANT to "Aria: hi",
            PromptAssembler.ROLE_SYSTEM to "phi-b Bram",
        )

        assertEquals(expected, out)
    }

    @Test
    fun `golden case B - speaker header keeps body identical`() {
        val base = listOf(
            PromptAssembler.GroupMember("Aria", card(name = "Aria", description = "aria desc")),
            PromptAssembler.GroupMember("Bram", card(name = "Bram", description = "bram desc")),
        )
        val neutral = PromptAssembler.assembleGroup(base, speaker = null)
        val spoken = PromptAssembler.assembleGroup(base, speaker = "Bram")

        assertEquals(
            "This group chat includes: Aria, Bram. Write the next reply in a fictional group chat " +
                "between Aria, Bram and User. This turn, reply only as Bram, in Bram's own voice. " +
                "Never put any character's name before the reply. Do not speak or act for the other " +
                "members; only Bram reacts.",
            spoken.first().second,
        )
        // 只有群说明随 speaker 变化，其余逐条一致。
        assertEquals(neutral.drop(1), spoken.drop(1))
    }
}
