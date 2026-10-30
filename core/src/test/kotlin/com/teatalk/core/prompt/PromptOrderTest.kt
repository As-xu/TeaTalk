package com.teatalk.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptOrderTest {

    @Test
    fun `assemble puts system first, examples before history, phi last`() {
        val examples = listOf(
            PromptOrder.ROLE_USER to "hi",
            PromptOrder.ROLE_ASSISTANT to "hello",
        )
        val history = listOf(PromptOrder.ROLE_USER to "how are you")
        val messages = PromptOrder.assemble(
            system = "SYS",
            examples = examples,
            history = history,
            postHistoryInstruction = "PHI",
        )
        assertEquals(
            listOf(
                PromptOrder.ROLE_SYSTEM to "SYS",
                PromptOrder.ROLE_USER to "hi",
                PromptOrder.ROLE_ASSISTANT to "hello",
                PromptOrder.ROLE_USER to "how are you",
                PromptOrder.ROLE_SYSTEM to "PHI",
            ),
            messages,
        )
    }

    @Test
    fun `assemble skips blank entries`() {
        val messages = PromptOrder.assemble(
            system = " ",
            examples = listOf(PromptOrder.ROLE_USER to ""),
            history = listOf(PromptOrder.ROLE_USER to "q"),
            postHistoryInstruction = null,
        )
        assertEquals(listOf(PromptOrder.ROLE_USER to "q"), messages)
        assertEquals(PromptOrder.ROLE_USER, messages.first().first)
    }

    @Test
    fun `parseExamples handles user and char prefixes with multiline bodies`() {
        val raw = """
            {{user}}: Hello there
            second line
            {{char}}: *smiles*
            Hi! How can I help?
        """.trimIndent()
        val turns = PromptOrder.parseExamples(raw)
        assertEquals(2, turns.size)
        assertEquals(PromptOrder.ROLE_USER, turns[0].first)
        assertTrue(turns[0].second.contains("second line"))
        assertEquals(PromptOrder.ROLE_ASSISTANT, turns[1].first)
        assertTrue(turns[1].second.contains("How can I help?"))
    }

    @Test
    fun `parseExamples supports START separated segments`() {
        val raw = """
            {{user}}: first question
            {{char}}: first answer
            <START>
            {{user}}: second question
            {{char}}: second answer
        """.trimIndent()
        val turns = PromptOrder.parseExamples(raw)
        assertEquals(4, turns.size)
        assertEquals("first question", turns[0].second)
        assertEquals(PromptOrder.ROLE_ASSISTANT, turns[3].first)
        assertEquals("second answer", turns[3].second)
    }

    @Test
    fun `parseExamples returns empty when no speaker marker`() {
        assertTrue(PromptOrder.parseExamples("plain prose without markers").isEmpty())
        assertTrue(PromptOrder.parseExamples("").isEmpty())
    }

    @Test
    fun `parseExamples accepts angle bracket and plain speakers`() {
        val raw = "USER: a\nASSISTANT: b\n<USER>: c"
        val turns = PromptOrder.parseExamples(raw)
        assertEquals(3, turns.size)
        assertEquals(listOf(PromptOrder.ROLE_USER, PromptOrder.ROLE_ASSISTANT, PromptOrder.ROLE_USER), turns.map { it.first })
    }
}
