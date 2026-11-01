package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerGroupTest {

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

    private fun group(vararg members: PromptAssembler.GroupMember) = members.toList()

    @Test
    fun `header lists members and falls back to st default system line`() {
        val out = PromptAssembler.assembleGroup(
            group(
                PromptAssembler.GroupMember("Aria", card("Aria", description = "aria desc")),
                PromptAssembler.GroupMember("Bram", card("Bram", scenario = "bram scene")),
            ),
            speaker = "Aria",
        )
        assertEquals(PromptAssembler.ROLE_SYSTEM, out[0].first)
        assertEquals(
            "This group chat includes: Aria, Bram. Write the next reply in a fictional group chat " +
                "between Aria, Bram and User. This turn, reply only as Aria, in Aria's own voice. " +
                "Never put any character's name before the reply.",
            out[0].second,
        )
        assertTrue(out.any { it.second == "Write Aria's next reply in a fictional chat between Aria and User." })
        assertTrue(out.any { it.second == "Write Bram's next reply in a fictional chat between Bram and User." })
        assertTrue(out.any { it.second == "aria desc" })
        assertTrue(out.any { it.second == "bram scene" })
    }

    @Test
    fun `header without speaker keeps the neutral one-of-them line`() {
        val out = PromptAssembler.assembleGroup(
            group(PromptAssembler.GroupMember("Aria", card("Aria")), PromptAssembler.GroupMember("Bram", card("Bram"))),
        )
        assertEquals(
            "This group chat includes: Aria, Bram. Write the next reply in a fictional group chat " +
                "between Aria, Bram and User. Answer only as one of them, and never put a character " +
                "name before the reply.",
            out[0].second,
        )
    }

    @Test
    fun `world blocks and chat start marker order`() {
        val out = PromptAssembler.assembleGroup(
            group(
                PromptAssembler.GroupMember("Aria", card("Aria", description = "desc")),
                PromptAssembler.GroupMember("Bram", card("Bram", description = "desc2")),
            ),
            history = listOf(PromptAssembler.HistoryMessage(PromptAssembler.ROLE_USER, "hi {{user}}")),
            beforeWorld = listOf("before {{char}}"),
            afterWorld = listOf("after {{user}}"),
        )
        assertEquals("before Aria, Bram", out[1].second)
        val start = out.indexOfFirst { it.second == PromptAssembler.CHAT_START_MARKER }
        assertTrue("chat start after member blocks", start > 2)
        assertEquals("after User", out[start - 1].second)
        assertEquals("hi {{user}}", out[start + 1].second)
    }

    @Test
    fun `per member post history instructions come after history`() {
        val out = PromptAssembler.assembleGroup(
            group(
                PromptAssembler.GroupMember("Aria", card("Aria", phi = "aria phi {{user}}")),
                PromptAssembler.GroupMember("Bram", card("Bram", phi = "bram phi")),
            ),
            history = listOf(
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_USER, "hello"),
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_ASSISTANT, "Aria: hello there"),
            ),
        )
        val start = out.indexOfFirst { it.second == PromptAssembler.CHAT_START_MARKER }
        val aria = out.indexOfFirst { it.second == "aria phi User" }
        val bram = out.indexOfFirst { it.second == "bram phi" }
        assertTrue(aria > start + 2)
        assertTrue(bram > aria)
        assertEquals("Aria: hello there", out[start + 2].second)
    }

    @Test
    fun `group prompt does not include mes example`() {
        val out = PromptAssembler.assembleGroup(
            group(
                PromptAssembler.GroupMember(
                    "Aria",
                    card("Aria", mesExample = "<START>\n{{user}}: ping\n{{char}}: pong"),
                ),
                PromptAssembler.GroupMember("Bram", card("Bram")),
            ),
        )
        assertTrue(out.none { it.second == PromptAssembler.EXAMPLE_CHAT_MARKER })
        assertTrue(out.none { it.second.contains("ping") })
    }

    @Test
    fun `empty members returns empty prompt`() {
        assertEquals(emptyList<Pair<String, String>>(), PromptAssembler.assembleGroup(emptyList()))
    }
}
