package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoInjector
import com.teatalk.core.worldinfo.WorldInfoParser
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

    @Test
    fun worldBookBeforeBlockMatchesSillyTavernWorldCase() {
        val card = CardViewParser.parse(resource("golden/golden-sera-card.json"))
        val entries = WorldInfoParser.fromStandalone(resource("golden/golden-eldoria.json"))
        // ST 这次激活了 eldoria/shadowfang/power 三条（glade 未命中关键词），且全部 position=0。
        val activated = entries.filter { it.uid in setOf(0, 1, 3) }
        val before = WorldInfoInjector.beforeCharacter(activated).map { it.content }
        val expected = captureMessages("golden/capture-sera-wi.json")
        val actual = PromptAssembler.assemble(
            card = card,
            characterName = "Seraphina",
            userName = "User",
            history = listOf(
                PromptAssembler.HistoryMessage(PromptAssembler.ROLE_ASSISTANT, card.firstMes),
                PromptAssembler.HistoryMessage(
                    PromptAssembler.ROLE_USER,
                    "Tell me about tea in Eldoria.",
                ),
            ),
            beforeWorld = before,
        )
        assertEquals("message count", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("role #$index", expected[index].first, actual[index].first)
            assertEquals("content #$index", expected[index].second, actual[index].second)
        }
    }

    @Test
    fun beforeCharacterRendersInSillyTavernOrder() {
        val entries = WorldInfoParser.fromStandalone(resource("golden/golden-eldoria.json"))
        val activated = entries.filter { it.uid in setOf(0, 1, 3) }
        assertEquals(
            listOf(3, 1, 0),
            WorldInfoInjector.beforeCharacter(activated).map { it.uid },
        )
    }
}
