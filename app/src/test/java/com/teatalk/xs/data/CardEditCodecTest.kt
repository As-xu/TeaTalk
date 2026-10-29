package com.teatalk.xs.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 评审 P0-1 的回归网：编辑结果必须真的写回 cardJson。 */
class CardEditCodecTest {

    private fun v2Card(): String =
        """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"A","description":"d","tags":["t"]}}"""

    @Test
    fun writesAdvancedFieldsIntoV2Data() {
        val edit = CardEdit(
            name = "A",
            tags = listOf("t", "u"),
            description = "d2",
            personality = "p",
            scenario = "s",
            firstMes = "f",
            creatorNotes = "c",
            systemPrompt = "SP",
            postHistoryInstructions = "PHI",
            mesExample = "EX",
            alternateGreetings = listOf("hi", "hello"),
        )
        val out = applyCardEdit(v2Card(), edit)!!
        val parsed = CardViewParser.parse(out)
        assertEquals("SP", parsed.systemPrompt)
        assertEquals("PHI", parsed.postHistoryInstructions)
        assertEquals("EX", parsed.mesExample)
        assertEquals(listOf("hi", "hello"), parsed.alternateGreetings)
        assertEquals(listOf("t", "u"), parsed.tags)
    }

    @Test
    fun emptyAdvancedFieldsRemoveKeys() {
        val withAdvanced = applyCardEdit(
            v2Card(),
            CardEdit("A", listOf("t"), "d", "", "", "", "", systemPrompt = "SP", alternateGreetings = listOf("x")),
        )!!
        val cleared = applyCardEdit(
            withAdvanced,
            CardEdit("A", listOf("t"), "d", "", "", "", ""),
        )!!
        val data = Json.parseToJsonElement(cleared).jsonObject["data"]!!.jsonObject
        assertTrue("system_prompt" !in data)
        assertTrue("alternate_greetings" !in data)
    }

    @Test
    fun legacyFlatCardWritesRoot() {
        val flat = """{"name":"B","description":"x"}"""
        val out = applyCardEdit(flat, CardEdit("B", emptyList(), "x", "", "", "", "", systemPrompt = "S"))!!
        assertEquals("S", CardViewParser.parse(out).systemPrompt)
    }

    @Test
    fun brokenJsonReturnsNull() {
        assertNull(applyCardEdit("not json", CardEdit("A", emptyList(), "", "", "", "", "")))
    }
}
