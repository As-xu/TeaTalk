package com.teatalk.xs.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden case：用真实 SillyTavern 抓下来的请求体（tools/golden/capture-aster.json）做等值断言。
 *
 * 这套断言是 M3 的「语义底座」：以后改 prompt 组装，先看它是否仍然和 ST 一致。
 */
class GoldenPromptTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun captureMessages(name: String): List<Pair<String, String>> {
        val root = Json.parseToJsonElement(resource(name)).jsonObject
        val messages = root["messages"]!!.jsonArray
        return messages.map { element ->
            val message = element.jsonObject
            message["role"]!!.jsonPrimitive.content to message["content"]!!.jsonPrimitive.content
        }
    }

    @Test
    fun promptMatchesSillyTavernCardFieldsCase() {
        val card = CardViewParser.parse(resource("golden/golden-card-aster.json"))
        val expected = captureMessages("golden/capture-aster.json")

        val greeting = card.firstMes
        val userMessage = "Tell me about the dragon."
        val actual = PromptAssembler.assemble(
            card = card,
            characterName = "Aster",
            userName = "User",
            history = listOf(
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_ASSISTANT, greeting),
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_USER, userMessage),
            ),
        )

        assertEquals("message count", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("role #$index", expected[index].first, actual[index].first)
            assertEquals("content #$index", expected[index].second, actual[index].second)
        }
    }

    @Test
    fun exampleBlocksKeepMarkerAndLines() {
        val blocks = PromptAssembler.parseExampleBlocks(
            "<START>\n{{user}}: hi\n{{char}}: hello\n<START>\n{{user}}: again",
        )
        assertEquals(2, blocks.size)
        assertEquals(listOf("{{user}}: hi", "{{char}}: hello"), blocks[0])
        assertEquals(listOf("{{user}}: again"), blocks[1])
    }

    @Test
    fun defaultSystemPromptUsedWhenCardHasNone() {
        val card = CardViewParser.parse(resource("golden/golden-card-aster.json"))
            .copy(systemPrompt = "")
        val actual = PromptAssembler.assemble(
            card = card,
            characterName = "Aster",
            userName = "User",
            history = emptyList(),
        )
        assertTrue(actual.first().second.startsWith("Write Aster's next reply in a fictional chat"))
    }
}
