package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerMemoryTest {

    private fun card(
        name: String = "Aria",
        description: String = "desc",
        personality: String = "",
        scenario: String = "",
    ) = CardView(
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
    )

    @Test
    fun memorySitsBetweenWorldInfoAndCharacterDefinition() {
        val messages = PromptAssembler.assemble(
            card = card(),
            characterName = "Aria",
            history = listOf(PromptAssembler.HistoryMessage("user", "hi")),
            beforeWorld = listOf("world before"),
            memory = "[Earlier conversation]\n- User: hello",
        )
        val bodies = messages.map { it.second }
        val worldIndex = bodies.indexOf("world before")
        val memoryIndex = bodies.indexOf("[Earlier conversation]\n- User: hello")
        val descIndex = bodies.indexOf("desc")
        assertTrue("world=$worldIndex memory=$memoryIndex desc=$descIndex", worldIndex in 0 until memoryIndex)
        assertTrue(memoryIndex < descIndex)
        assertEquals(PromptAssembler.ROLE_SYSTEM, messages[memoryIndex].first)
    }

    @Test
    fun memoryIsMacroSubstituted() {
        val messages = PromptAssembler.assemble(
            card = card(),
            characterName = "Aria",
            history = emptyList(),
            memory = "[Earlier conversation]\n- User: met {{char}}",
        )
        val body = messages.first { it.second.startsWith("[Earlier conversation]") }.second
        assertEquals("[Earlier conversation]\n- User: met Aria", body)
    }

    @Test
    fun noMemoryKeepsPromptUnchanged() {
        val base = PromptAssembler.assemble(
            card = card(),
            characterName = "Aria",
            history = listOf(PromptAssembler.HistoryMessage("user", "hi")),
        )
        val withNull = PromptAssembler.assemble(
            card = card(),
            characterName = "Aria",
            history = listOf(PromptAssembler.HistoryMessage("user", "hi")),
            memory = null,
        )
        assertEquals(base, withNull)
    }

    @Test
    fun groupMemoryUsesAllMemberNames() {
        val members = listOf(
            PromptAssembler.GroupMember("Aria", card("Aria")),
            PromptAssembler.GroupMember("Bram", card("Bram")),
        )
        val messages = PromptAssembler.assembleGroup(
            members = members,
            history = emptyList(),
            memory = "[Earlier conversation]\n- Assistant: {{char}} talked",
        )
        val body = messages.first { it.second.startsWith("[Earlier conversation]") }.second
        assertTrue(body.contains("Aria, Bram talked"))
    }
}
