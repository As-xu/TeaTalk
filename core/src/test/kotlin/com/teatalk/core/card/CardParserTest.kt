package com.teatalk.core.card

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardParserTest {

    // ---------- 样本 ----------

    private val v2Json = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "雨宫 葵",
            "description": "雨天便利店的常客。",
            "personality": "慢热、细腻",
            "scenario": "梅雨季的便利店",
            "first_mes": "……又是你啊。",
            "mes_example": "",
            "creator_notes": "测试卡",
            "system_prompt": "",
            "post_history_instructions": "",
            "alternate_greetings": ["今天雨很大。", "你带伞了吗？"],
            "tags": ["日常", "治愈"],
            "creator": "tester",
            "character_version": "1.0",
            "extensions": {},
            "x_future_field": {"keep": true}
          }
        }
    """.trimIndent()

    private val v3Json = """
        {
          "spec": "chara_card_v3",
          "spec_version": "3.0",
          "data": {
            "name": "雨宫 葵 V3",
            "first_mes": "V3 的问候。",
            "group_only_greetings": ["大家好。"],
            "assets": [{"type": "icon", "uri": "ccdefault:", "name": "main", "ext": "png"}]
          }
        }
    """.trimIndent()

    private val legacyJson = """
        {
          "name": "老卡",
          "description": "V1 平铺字段",
          "personality": "安静",
          "scenario": "旧时代",
          "first_mes": "你好。",
          "mes_example": "<START>"
        }
    """.trimIndent()

    // ---------- PNG ----------

    @Test
    fun pngV2_parsed() {
        val png = png(chara = v2Json)
        val imported = CardParser.parse(png)
        assertEquals(CardSource.PNG_V2, imported.source)
        assertEquals("雨宫 葵", imported.card.data.name)
        assertEquals(2, imported.card.data.alternateGreetings.size)
        assertEquals("2.0", imported.card.specVersion)
        // 未知字段保留在 raw 里（导出不丢数据）
        val rawData = imported.card.raw["data"] as JsonObject
        assertTrue(rawData.containsKey("x_future_field"))
    }

    @Test
    fun pngV3_chunkWinsOverChara() {
        val png = png(chara = v2Json, ccv3 = v3Json)
        val imported = CardParser.parse(png)
        assertEquals(CardSource.PNG_V3, imported.source)
        assertEquals("雨宫 葵 V3", imported.card.data.name)
        assertEquals("3.0", imported.card.specVersion)
        assertEquals(1, imported.card.data.groupOnlyGreetings.size)
    }

    @Test
    fun pngWithoutMetadata_isRejected() {
        val png = png() // 只有签名 + IHDR + IEND
        assertFailsWith<CardImportException> { CardParser.parse(png) }
    }

    @Test
    fun pngWithBrokenBase64_isRejected() {
        // 直接写原始（非 base64）文本，模拟被截断/损坏的卡
        val png = pngRaw(("chara" to "这不是base64"))
        val error = assertFailsWith<CardImportException> { CardParser.parse(png) }
        assertTrue(error.message!!.contains("base64"), "实际消息：${error.message}")
    }

    // ---------- JSON ----------

    @Test
    fun jsonV2_parsedFromFileBytes() {
        val imported = CardParser.parse(v2Json.toByteArray())
        assertEquals(CardSource.JSON_V2, imported.source)
        assertEquals("雨宫 葵", imported.card.data.name)
        assertTrue(imported.warnings.any { it.contains("V3") } || imported.warnings.isEmpty())
    }

    @Test
    fun jsonDirtyCard_getsDefaultsAndWarnings() {
        val dirty = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"只有名字"}}"""
        val imported = CardParser.parse(dirty.toByteArray())
        assertEquals("只有名字", imported.card.data.name)
        assertTrue(imported.card.data.alternateGreetings.isEmpty())
        assertTrue(imported.warnings.any { it.contains("data.mes_example") })
        assertTrue(imported.warnings.any { it.contains("data.tags") })
    }

    @Test
    fun jsonLegacyFlatCard_isUpgradedToV2() {
        val imported = CardParser.parse(legacyJson.toByteArray())
        assertEquals(CardSource.LEGACY_JSON, imported.source)
        assertEquals("老卡", imported.card.data.name)
        assertEquals(StCharacterCard.SPEC_V2, imported.card.spec)
        assertTrue(imported.warnings.any { it.contains("V1") })
    }

    @Test
    fun jsonWithBom_isParsed() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + v2Json.toByteArray()
        assertEquals("雨宫 葵", CardParser.parse(bytes).card.data.name)
    }

    @Test
    fun brokenJson_isRejected() {
        assertFailsWith<CardImportException> { CardParser.parse("{ not json ".toByteArray()) }
    }

    @Test
    fun tagsAsSingleString_isLenientlyConverted() {
        val json = """{"spec":"chara_card_v2","data":{"name":"x","tags":"单标签"}}"""
        val imported = CardParser.parse(json.toByteArray())
        assertEquals(listOf("单标签"), imported.card.data.tags)
        assertTrue(imported.warnings.any { it.contains("tags") })
    }

    // ---------- CHARX ----------

    @Test
    fun charx_cardJson_parsed() {
        val charx = zipOf("card.json" to v3Json)
        val imported = CardParser.parse(charx)
        assertEquals(CardSource.CHARX, imported.source)
        assertEquals("雨宫 葵 V3", imported.card.data.name)
    }

    @Test
    fun zipWithoutCardJson_isRejected() {
        val charx = zipOf("readme.txt" to "not a card")
        assertFailsWith<CardImportException> { CardParser.parse(charx) }
    }

    // ---------- 真实卡（SillyTavern 自带 default_Seraphina.png） ----------

    @Test
    fun realStDefaultCard_parsed() {
        val bytes = javaClass.getResourceAsStream("/cards/default_Seraphina.png")!!.readBytes()
        val imported = CardParser.parse(bytes)
        assertEquals(CardSource.PNG_V3, imported.source) // 同时带 chara + ccv3，ccv3 优先
        assertEquals(StCharacterCard.SPEC_V3, imported.card.spec)
        assertEquals("3.0", imported.card.specVersion)
        assertEquals("Seraphina", imported.card.data.name)
        assertTrue(imported.card.data.description.isNotBlank())
        assertTrue(imported.card.data.firstMes.isNotBlank())
        // 真实卡 data 是完整 V3 结构（16 个字段），含 character_book 内嵌世界书
        assertTrue(imported.card.data.characterBook != null)
        // ST 自带卡根节点混有历史字段（avatar/chat/fav…），必须原样保留
        assertTrue(imported.card.raw.containsKey("avatar"))
        assertEquals(false, imported.warnings.any { it.contains("V1") })
    }

    // ---------- 构造 PNp/zip 的测试工具 ----------

    private fun png(chara: String? = null, ccv3: String? = null): ByteArray {
        val texts = buildList {
            chara?.let { add("chara" to it) }
            ccv3?.let { add("ccv3" to it) }
        }
        return pngText(*texts.toTypedArray())
    }

    private fun pngText(vararg texts: Pair<String, String>): ByteArray {
        val encoded = texts.map { (keyword, json) ->
            keyword to Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        }
        return pngRaw(*encoded.toTypedArray())
    }

    /** texts 的 value 原样写入 tEXt（不再 base64），用于构造损坏样本。 */
    private fun pngRaw(vararg texts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))
        texts.forEach { (keyword, text) ->
            val bytes = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            writeChunk(out, "tEXt", bytes)
        }
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(byteArrayOf(
            (data.size ushr 24).toByte(), (data.size ushr 16).toByte(),
            (data.size ushr 8).toByte(), data.size.toByte(),
        ))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.write(byteArrayOf(
            (crc.value ushr 24).toByte(), (crc.value ushr 16).toByte(),
            (crc.value ushr 8).toByte(), crc.value.toByte(),
        ))
    }

    // ---------- 兼容性 / 健壮性回归（对应 M0 审阅 P1）----------

    @Test
    fun `畸形 PNG chunk 长度不应崩溃`() {
        // 构造 tEXt chunk 长度 = 0x7FFFFFF8：旧实现 dataStart+length 溢出成负数 → 越界崩溃
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ),
        )
        out.write(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF8.toByte())) // length
        out.write("tEXt".toByteArray(Charsets.ISO_8859_1))
        out.write("chara".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + "AAAA".toByteArray(Charsets.ISO_8859_1))
        val bytes = out.toByteArray()
        // 不是崩溃（AIOOBE），而是被当作"没有元数据的 PNG"
        val e = assertFailsWith<CardImportException> { CardParser.parse(bytes) }
        assertTrue(e.message!!.contains("元数据"))
    }

    @Test
    fun `CHARX 解压超限被拒绝（压缩炸弹防护）`() {
        val huge = "a".repeat(17 * 1024 * 1024) // 17 MB > 16 MB 上限
        val jsonCard = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"x","$huge":1}}"""
        val zip = zipOf("card.json" to jsonCard)
        val e = assertFailsWith<CardImportException> { CardParser.parse(zip) }
        assertTrue(e.message!!.contains("过大") || e.message!!.contains("超过"))
    }

    @Test
    fun `V1 平铺卡补 creatorcomment 与 creator`() {
        val legacy = """
            {
              "name": "老卡",
              "description": "V1",
              "creatorcomment": "作者备注",
              "creator": "someone",
              "character_version": "0.9"
            }
        """.trimIndent()
        val imported = CardParser.parse(legacy.toByteArray())
        assertEquals("作者备注", imported.card.data.creatorNotes)
        assertEquals("someone", imported.card.data.creator)
        assertEquals("0.9", imported.card.data.characterVersion)
    }

    @Test
    fun `未知 spec 不拒绝，按兼容模式解析`() {
        val future = """{"spec":"chara_card_v9","spec_version":"9.0","data":{"name":"未来卡"}}"""
        val imported = CardParser.parse(future.toByteArray())
        assertEquals("未来卡", imported.card.data.name)
        assertTrue(imported.warnings.any { it.contains("未知 spec") })
    }
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}