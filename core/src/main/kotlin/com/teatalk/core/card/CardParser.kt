package com.teatalk.core.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 角色卡导入入口：PNG / JSON(.json) / CHARX(.charx) 三路输入统一成 [ImportedCard]。
 *
 * 宽容策略（对齐 ST 导入体验）：字段缺失、类型异常、老 V1 平铺卡都不报错，
 * 统一补默认值并把问题写进 `warnings` 交给 UI 提示。
 * 只有"根本不是卡"（PNG 无元数据、ZIP 无 card.json、JSON 解析失败）才抛 [CardImportException]。
 */
object CardParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** CHARX 内 card.json 的解压上限（防压缩炸弹）。 */
    private const val MAX_CHARX_JSON_BYTES = 16 * 1024 * 1024

    /** V2 规范要求 data 下的字段；缺失只告警不失败。 */
    private val V2_REQUIRED_IN_DATA = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions",
        "alternate_greetings", "tags", "creator", "character_version", "extensions",
    )

    /** V1 平铺卡 → V2 data 的字段映射（同名字段）。 */
    private val V1_FLAT_FIELDS = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
    )

    /** V1 独有字段 → V2 字段名（ST `characters.js` 的 V1 兼容映射）。 */
    private val V1_TO_V2_FIELDS = mapOf(
        "creatorcomment" to "creator_notes",
        "creator" to "creator",
        "character_version" to "character_version",
    )

    fun parse(bytes: ByteArray): ImportedCard = when {
        PngTextReader.isPng(bytes) -> parsePng(bytes)
        isZip(bytes) -> parseCharx(bytes)
        isJpeg(bytes) -> throw CardImportException(
            CardImportReason.UNSUPPORTED_IMAGE,
            "JPEG 图片不能携带角色卡数据（ST 只认 PNG tEXt）",
        )
        isBinary(bytes) -> throw CardImportException(
            CardImportReason.UNSUPPORTED_FILE,
            "不认识这个文件：既不是 PNG/CHARX，也不是 JSON 文本",
        )
        else -> parseJsonText(decodeUtf8(bytes), preferredSource = null)
    }

    // ---- PNG ----

    private fun parsePng(bytes: ByteArray): ImportedCard {
        val cardJson = PngTextReader.cardJson(bytes)
            ?: throw CardImportException(
            CardImportReason.NO_CHARACTER_DATA,
            "PNG 里没有 chara / ccv3 元数据，不是角色卡",
        )
        val preferred = if (PngTextReader.hasKeyword(bytes, "ccv3")) CardSource.PNG_V3 else CardSource.PNG_V2
        return parseJsonText(cardJson, preferredSource = preferred)
    }

    // ---- CHARX ----

    private fun parseCharx(bytes: ByteArray): ImportedCard {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("card.json", ignoreCase = true)) {
                    // 压缩炸弹防护：先看声明尺寸，再按上限边读边计数（ZIP 头部尺寸不可信）
                    if (entry.size > MAX_CHARX_JSON_BYTES) {
                        throw CardImportException(
                            CardImportReason.CARD_JSON_TOO_LARGE,
                            "CHARX 内 card.json 过大（声明 ${entry.size} 字节）",
                        )
                    }
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_CHARX_JSON_BYTES) {
                            throw CardImportException(
                                CardImportReason.CARD_JSON_TOO_LARGE,
                                "CHARX 内 card.json 解压后超过 ${MAX_CHARX_JSON_BYTES / 1024 / 1024} MB，已拒绝",
                            )
                        }
                        out.write(chunk, 0, n)
                    }
                    val imported = parseJsonText(out.toString(Charsets.UTF_8), preferredSource = CardSource.CHARX)
                    return imported.copy(
                        warnings = imported.warnings + "CHARX 内的 avatar/assets 资源 M0 暂不导入",
                    )
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        throw CardImportException(CardImportReason.MISSING_CARD_JSON, "CHARX 压缩包里没有 card.json")
    }

    // ---- JSON ----

    private fun parseJsonText(text: String, preferredSource: CardSource?): ImportedCard {
        val warnings = mutableListOf<String>()
        val root = try {
            json.parseToJsonElement(stripBom(text).trim()) as? JsonObject
                ?: throw CardImportException(CardImportReason.BAD_JSON, "角色卡 JSON 顶层不是对象")
        } catch (e: CardImportException) {
            throw e
        } catch (e: Exception) {
            throw CardImportException(CardImportReason.BAD_JSON, "角色卡 JSON 解析失败", e)
        }

        val spec = root.strOrNull("spec")
        val specVersion = root.strOrNull("spec_version").orEmpty()
        val dataObj: JsonObject
        val derivedSource: CardSource
        var isLegacy = false

        when {
            spec == StCharacterCard.SPEC_V3 -> {
                warnings += "V3 卡：多出的字段（group_only_greetings / assets 等）已保留在原始 JSON 中"
                dataObj = (root["data"] as? JsonObject)
                    ?: throw CardImportException(CardImportReason.MISSING_DATA, "V3 卡缺少 data 对象")
                derivedSource = preferredSource ?: CardSource.JSON_V3
            }

            spec == StCharacterCard.SPEC_V2 -> {
                dataObj = (root["data"] as? JsonObject)
                    ?: throw CardImportException(CardImportReason.MISSING_DATA, "V2 卡缺少 data 对象")
                derivedSource = preferredSource ?: CardSource.JSON_V2
            }

            spec != null -> {
                // 未来版本 / 拼写错误的 spec：不拒绝，按 data 结构与 V1 平铺兜底
                warnings += "未知 spec「$spec」，按兼容模式解析"
                val dataOrNull = root["data"] as? JsonObject
                if (dataOrNull != null) {
                    dataObj = dataOrNull
                    derivedSource = preferredSource ?: CardSource.JSON_V2
                } else {
                    dataObj = legacyFlatToData(root, warnings)
                    derivedSource = preferredSource ?: CardSource.LEGACY_JSON
                    isLegacy = true
                }
            }

            root["data"] is JsonObject -> {
                warnings += "缺少 spec 字段，按 V2 解析"
                dataObj = root["data"] as JsonObject
                derivedSource = preferredSource ?: CardSource.JSON_V2
            }

            else -> {
                warnings += "老版 V1 平铺卡，已升级为 V2 结构"
                dataObj = legacyFlatToData(root, warnings)
                derivedSource = preferredSource ?: CardSource.LEGACY_JSON
                isLegacy = true
            }
        }

        val data = mapData(dataObj, warnings).let { mapped ->
            // 兼容 ST 自带卡：tags 写在根节点而不是 data.tags
            if (mapped.tags.isEmpty()) {
                val legacyTags = root.stringList("tags", warnings)
                if (legacyTags.isNotEmpty()) {
                    warnings += "tags 位于根节点（旧格式），已兼容读取"
                    mapped.copy(tags = legacyTags)
                } else {
                    mapped
                }
            } else {
                mapped
            }
        }
        if (data.name.isBlank()) warnings += "角色名为空，SillyTavern 可能拒绝这张卡"
        val isV3 = !isLegacy && (
            derivedSource == CardSource.JSON_V3 ||
                derivedSource == CardSource.PNG_V3 ||
                derivedSource == CardSource.CHARX && spec == StCharacterCard.SPEC_V3
            )
        val card = StCharacterCard(
            spec = if (isV3) StCharacterCard.SPEC_V3 else StCharacterCard.SPEC_V2,
            specVersion = specVersion.ifBlank { if (isV3) "3.0" else "2.0" },
            data = data,
            raw = root,
        )
        return ImportedCard(source = derivedSource, card = card, warnings = warnings)
    }

    private fun legacyFlatToData(root: JsonObject, warnings: MutableList<String>): JsonObject {
        val extra = root["extensions"]
        return JsonObject(
            buildMap {
                V1_FLAT_FIELDS.forEach { key -> root.strOrNull(key)?.let { put(key, JsonPrimitive(it)) } }
                V1_TO_V2_FIELDS.forEach { (v1, v2) -> root.strOrNull(v1)?.let { put(v2, JsonPrimitive(it)) } }
                // V1 的 tags 可能在根节点且非数组
                if (root["tags"] is JsonArray) put("tags", root["tags"] as JsonArray)
                val alt = root["alternate_greetings"]
                if (alt is JsonArray) put("alternate_greetings", alt)
                if (extra is JsonObject) put("extensions", extra)
            },
        )
    }

    private fun mapData(data: JsonObject, warnings: MutableList<String>): StCardData {
        for (field in V2_REQUIRED_IN_DATA) {
            if (data[field] == null || data[field] == JsonNull) {
                warnings += "缺少字段 data.$field，已补默认值"
            } else if (field != "extensions" && field != "alternate_greetings" && field != "tags") {
                if (data[field] !is JsonPrimitive && field != "character_book") {
                    warnings += "字段 data.$field 类型异常，已忽略"
                }
            }
        }
        val extensions = (data["extensions"] as? JsonObject) ?: run {
            if (data["extensions"] != null) warnings += "data.extensions 不是对象，已置空"
            JsonObject(emptyMap())
        }
        val characterBook = when (val book = data["character_book"]) {
            null, JsonNull -> null
            is JsonObject -> book
            else -> {
                warnings += "data.character_book 不是对象，已忽略"
                null
            }
        }
        return StCardData(
            name = data.strOrNull("name").orEmpty(),
            description = data.strOrNull("description").orEmpty(),
            personality = data.strOrNull("personality").orEmpty(),
            scenario = data.strOrNull("scenario").orEmpty(),
            firstMes = data.strOrNull("first_mes").orEmpty(),
            mesExample = data.strOrNull("mes_example").orEmpty(),
            creatorNotes = data.strOrNull("creator_notes").orEmpty(),
            systemPrompt = data.strOrNull("system_prompt").orEmpty(),
            postHistoryInstructions = data.strOrNull("post_history_instructions").orEmpty(),
            alternateGreetings = data.stringList("alternate_greetings", warnings),
            groupOnlyGreetings = data.stringList("group_only_greetings", warnings),
            characterBook = characterBook,
            extensions = extensions,
            tags = data.stringList("tags", warnings),
            creator = data.strOrNull("creator").orEmpty(),
            characterVersion = data.strOrNull("character_version").orEmpty(),
            nickname = data.strOrNull("nickname").orEmpty(),
        )
    }

    // ---- helpers ----

    /** JPEG 魔术数 FF D8 FF（手机相册里的图几乎都是这种，给明确提示而不是「解析失败」）。 */
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** 前 1KB 里出现 NUL 基本可判定二进制；纯文本 JSON 不会有。 */
    private fun isBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 1024)
        for (i in 0 until limit) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text

    private fun decodeUtf8(bytes: ByteArray): String = stripBom(String(bytes, Charsets.UTF_8))

    private fun JsonObject.strOrNull(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.stringList(key: String, warnings: MutableList<String>): List<String> {
        val element = this[key] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        if (element !is JsonArray) {
            warnings += "字段 data.$key 不是数组（旧版单串格式），已转成单元素列表"
            return (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { listOf(it) } ?: emptyList()
        }
        return element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}
