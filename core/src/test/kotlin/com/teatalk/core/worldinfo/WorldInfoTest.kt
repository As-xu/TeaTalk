package com.teatalk.core.worldinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldInfoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    @Test
    fun `V2 character_book 数组形态：keys secondary enabled insertion_order position`() {
        val book = obj(
            """
            {
              "name": "测试世界",
              "entries": [
                {
                  "id": 1,
                  "keys": ["码头", "港口"],
                  "secondary_keys": ["夜晚"],
                  "content": "码头常年停着一条旧船。",
                  "enabled": true,
                  "insertion_order": 20,
                  "position": "after_char",
                  "case_sensitive": true
                },
                {
                  "id": 2,
                  "keys": ["废墟"],
                  "content": "废墟被藤蔓覆盖。",
                  "enabled": false
                }
              ]
            }
            """.trimIndent(),
        )
        val entries = WorldInfoParser.parseCharacterBook(book)
        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals(1, first.uid)
        assertEquals(listOf("码头", "港口"), first.keys)
        assertEquals(listOf("夜晚"), first.secondaryKeys)
        assertEquals(20, first.order)
        assertEquals(1, first.position)
        assertTrue(first.caseSensitive)
        assertTrue(first.enabled)
        assertFalse(entries[1].enabled)
        assertEquals(0, entries[1].position)
    }

    @Test
    fun `ST 世界书文件对象形态：key 与 disable 兼容`() {
        val file = obj(
            """
            {
              "entries": {
                "0": {
                  "uid": 0,
                  "key": ["龙", "龙族"],
                  "keysecondary": [],
                  "comment": "龙的设定",
                  "content": "龙族在上古时代统治天空。",
                  "constant": false,
                  "selective": true,
                  "order": 100,
                  "position": 0,
                  "disable": true,
                  "depth": 4,
                  "role": 1
                }
              }
            }
            """.trimIndent(),
        )
        val entries = WorldInfoParser.parseWorldInfoFile(file)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals(listOf("龙", "龙族"), e.keys)
        assertFalse(e.enabled)
        assertEquals(1, e.role)
        assertEquals(100, e.order)
    }

    @Test
    fun `fromCard 从 data_character_book 抽世界书`() {
        val card = obj(
            """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Seraphina",
                "description": "…",
                "character_book": {
                  "entries": [
                    { "keys": ["王城"], "content": "王城建立于三百年前。", "insertion_order": 5 }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val entries = WorldInfoParser.fromCard(card.toString())
        assertEquals(1, entries.size)
        assertEquals("王城", entries.single().keys.single())
        assertEquals(5, entries.single().order)
    }

    @Test
    fun `常量条目无需关键词即可激活`() {
        val engine = WorldInfoEngine(
            listOf(
                WorldInfoEntry(uid = 1, constant = true, content = "世界处于长夜。"),
                WorldInfoEntry(uid = 2, keys = listOf("码头"), content = "码头的水是咸的。"),
            ),
        )
        val hit = engine.activated("今天去哪里")
        assertEquals(listOf(1), hit.map { it.uid })
        assertEquals(listOf(1, 2), engine.activated("我们走到码头").map { it.uid })
    }

    @Test
    fun `关键词默认大小写不敏感，caseSensitive 时敏感`() {
        val insensitive = WorldInfoEngine(listOf(WorldInfoEntry(uid = 1, keys = listOf("Apple"), content = "x")))
        assertEquals(1, insensitive.activated("i like apple").size)

        val sensitive = WorldInfoEngine(
            listOf(WorldInfoEntry(uid = 1, keys = listOf("Apple"), content = "x", caseSensitive = true)),
        )
        assertEquals(0, sensitive.activated("i like apple").size)
        assertEquals(1, sensitive.activated("i like Apple").size)
    }

    @Test
    fun `selective 条目需要次要关键词同时命中`() {
        val entry = WorldInfoEntry(
            uid = 1,
            keys = listOf("码头"),
            secondaryKeys = listOf("夜晚"),
            selective = true,
            content = "夜里的码头有雾。",
        )
        val engine = WorldInfoEngine(listOf(entry))
        assertEquals(0, engine.activated("白天的码头").size)
        assertEquals(1, engine.activated("夜晚的码头").size)
    }

    @Test
    fun `按 order 升序拼接，且尊重字符预算`() {
        val entries = listOf(
            WorldInfoEntry(uid = 1, constant = true, order = 50, content = "A".repeat(10)),
            WorldInfoEntry(uid = 2, constant = true, order = 10, content = "B".repeat(10)),
            WorldInfoEntry(uid = 3, constant = true, order = 30, content = "C".repeat(10)),
        )
        val engine = WorldInfoEngine(entries, charBudget = 34)
        assertEquals(listOf(2, 3), engine.activated("").map { it.uid })

        val noBudget = WorldInfoEngine(entries, charBudget = 1_000)
        assertEquals(listOf(2, 3, 1), noBudget.activated("").map { it.uid })
    }

    @Test
    fun `空内容与禁用条目不参与扫描`() {
        val engine = WorldInfoEngine(
            listOf(
                WorldInfoEntry(uid = 1, keys = listOf("a"), content = "   "),
                WorldInfoEntry(uid = 2, keys = listOf("a"), content = "有内容", enabled = false),
                WorldInfoEntry(uid = 3, keys = listOf("a"), content = "可用"),
            ),
        )
        assertEquals(listOf(3), engine.activated("a").map { it.uid })
    }
}
