package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4-2：条目编辑只改被编辑字段，不动原始 JSON 里的其它字段。 */
class WorldBookEntryPatchTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val stBook = """
        {"entries":{
          "0":{"uid":0,"key":"tea, cup","keysecondary":"hot","comment":"Tea house","content":"A tea house.",
               "order":100,"position":0,"depth":4,"role":0,"disable":false,"constant":false,
               "selective":false,"selectiveLogic":0,"probability":100,"useProbability":true,
               "group":"","caseSensitive":false,"matchWholeWords":false,"automation_id":"keep-me"},
          "1":{"uid":1,"key":"glade","comment":"Glade","content":"A glade.","order":50}
        }}
    """.trimIndent()

    private val card = """
        {"spec":"chara_card_v3","data":{"character_book":{"entries":[
          {"uid":0,"keys":["tea"],"secondary_keys":[],"comment":"Tea","content":"Tea content",
           "enabled":true,"insertion_order":100,"extensions":{"position":1,"depth":4,"role":0,"probability":100,"group_weight":"keep"}}
        ]}}}
    """.trimIndent()

    @Test
    fun stBook_patchesTargetEntryAndKeepsUnknownFields() {
        val entry = WorldInfoEntry(
            uid = 0,
            comment = "Tea house (edited)",
            keys = listOf("tea", "cup", "kettle"),
            secondaryKeys = listOf("hot"),
            content = "Edited content.",
            enabled = true,
            order = 120,
            position = 2,
            depth = 6,
            role = 1,
            constant = true,
            selective = true,
        )
        val patched = WorldBookEntryPatch.patch(stBook, entry)
        assertNotNull(patched)
        val root = json.parseToJsonElement(patched!!).jsonObject
        val entries = root["entries"]!!.jsonObject
        val first = entries["0"]!!.jsonObject
        assertEquals("Tea house (edited)", first["comment"]!!.jsonPrimitive.content)
        assertEquals("tea, cup, kettle", first["key"]!!.jsonPrimitive.content)
        assertEquals(120, first["order"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, first["position"]!!.jsonPrimitive.content.toInt())
        assertEquals(6, first["depth"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, first["role"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", first["constant"]!!.jsonPrimitive.content)
        assertEquals("keep-me", first["automation_id"]!!.jsonPrimitive.content)
        // 未编辑的另一条原样保留
        val second = entries["1"]!!.jsonObject
        assertEquals("Glade", second["comment"]!!.jsonPrimitive.content)

        val reparsed = WorldInfoParser.fromCard(patched)
        assertEquals(2, reparsed.size)
        val patchedEntry = reparsed.first { it.uid == 0 }
        assertEquals("Edited content.", patchedEntry.content)
        assertEquals(listOf("tea", "cup", "kettle"), patchedEntry.keys)
        assertEquals(2, patchedEntry.position)
        assertEquals(1, patchedEntry.role)
        assertTrue(patchedEntry.constant)
    }

    @Test
    fun cardBook_patchesKeysArrayAndExtensions() {
        val entry = WorldInfoEntry(
            uid = 0,
            comment = "Tea edited",
            keys = listOf("tea", "leaf"),
            secondaryKeys = listOf("morning"),
            content = "New content",
            enabled = false,
            order = 90,
            position = 4,
            depth = 2,
            role = 2,
        )
        val patched = WorldBookEntryPatch.patch(card, entry)
        assertNotNull(patched)
        val obj = json.parseToJsonElement(patched!!).jsonObject
        val book = obj["data"]!!.jsonObject["character_book"]!!.jsonObject
        val first = book["entries"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("tea", "leaf"), first["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, first["enabled"]!!.jsonPrimitive.content.toBoolean())
        val ext = first["extensions"]!!.jsonObject
        assertEquals(4, ext["position"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, ext["depth"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, ext["role"]!!.jsonPrimitive.content.toInt())
        assertEquals("keep", ext["group_weight"]!!.jsonPrimitive.content)

        val reparsed = WorldInfoParser.fromCard(patched)
        assertEquals(1, reparsed.size)
        assertEquals(4, reparsed[0].position)
        assertEquals(2, reparsed[0].depth)
        assertEquals(false, reparsed[0].enabled)
    }

    @Test
    fun unknownShape_returnsNull() {
        assertNull(WorldBookEntryPatch.patch("[]", WorldInfoEntry(uid = 99)))
        assertNull(WorldBookEntryPatch.patch("{\"entries\":{}}", WorldInfoEntry(uid = 99)))
        assertNull(WorldBookEntryPatch.patch("not json", WorldInfoEntry(uid = 0)))
    }
}
