package com.teatalk.core.worldinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一条世界书条目。
 *
 * 同时承载两种来源（字段已合并成同一套 camelCase）：
 * - V2 `character_book.entries[]`（标准字段名 `keys` / `insertion_order` / `enabled` …）
 * - SillyTavern World Info 文件（`{entries:{"<uid>":{…}}}`，字段名 `key` / `order` / `disable` …）
 */
data class WorldInfoEntry(
    val uid: Int = 0,
    val comment: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val constant: Boolean = false,
    val enabled: Boolean = true,
    val order: Int = 100,
    /** 0=角色定义之前，1=之后（ST 还有 2/3/4 的 AN 深度位，M0 先保留原值） */
    val position: Int = 0,
    val depth: Int = 4,
    /** 0=system，1=user，2=assistant */
    val role: Int = 0,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val useRegex: Boolean = false,
    val selective: Boolean = true,
    val group: String = "",
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val extensions: JsonObject = JsonObject(emptyMap()),
) {
    /** 空内容或 disabled 的条目永远不参与扫描。 */
    fun isActive(): Boolean = enabled && content.isNotBlank()
}

/** 世界书解析：把两种 JSON 形态统一成 [WorldInfoEntry] 列表。宽容解析，坏字段按默认值处理。 */
object WorldInfoParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 从角色卡原始 JSON 抽世界书：优先 `data.character_book`，其次顶层 `entries`（ST 世界书文件）。 */
    fun fromCard(cardJson: String): List<WorldInfoEntry> = runCatching {
        val root = json.parseToJsonElement(cardJson) as? JsonObject ?: return@runCatching emptyList()
        val book = (root["data"] as? JsonObject)?.get("character_book") as? JsonObject
        if (book != null) parseCharacterBook(book) else parseWorldInfoFile(root)
    }.getOrDefault(emptyList())

    /** V2 角色卡内嵌世界书。 */
    fun parseCharacterBook(book: JsonObject): List<WorldInfoEntry> =
        entriesOf(book["entries"]).map(::normalize)

    /** SillyTavern 独立世界书文件 / 角色卡顶层 entries。 */
    fun parseWorldInfoFile(root: JsonObject): List<WorldInfoEntry> =
        entriesOf(root["entries"]).map(::normalize)

    private fun entriesOf(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> element.values.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun normalize(e: JsonObject): WorldInfoEntry {
        val extensions = (e["extensions"] as? JsonObject) ?: JsonObject(emptyMap())
        return WorldInfoEntry(
            uid = e.intOrNull("uid") ?: e.intOrNull("id") ?: 0,
            comment = e.string("comment"),
            keys = e.stringList("keys").ifEmpty { e.stringList("key") },
            secondaryKeys = e.stringList("secondary_keys").ifEmpty { e.stringList("keysecondary") },
            content = e.string("content"),
            constant = e.boolOrNull("constant") ?: false,
            enabled = e.boolOrNull("enabled") ?: !(e.boolOrNull("disable") ?: false),
            order = e.intOrNull("insertion_order") ?: e.intOrNull("order") ?: 100,
            position = parsePosition(e["position"]),
            depth = e.intOrNull("depth") ?: 4,
            role = parseRole(e["role"]),
            caseSensitive = e.boolOrNull("case_sensitive") ?: e.boolOrNull("caseSensitive") ?: false,
            matchWholeWords = e.boolOrNull("match_whole_words") ?: e.boolOrNull("matchWholeWords") ?: false,
            useRegex = e.boolOrNull("use_regex") ?: e.boolOrNull("useRegex") ?: false,
            selective = e.boolOrNull("selective") ?: true,
            group = e.string("group"),
            probability = e.intOrNull("probability") ?: 100,
            useProbability = e.boolOrNull("use_probability") ?: e.boolOrNull("useProbability") ?: true,
            extensions = extensions,
        )
    }

    private fun parsePosition(element: JsonElement?): Int = when (element) {
        is JsonPrimitive -> when {
            element.isString -> when (element.content.lowercase()) {
                "before_char" -> 0
                "after_char" -> 1
                "an_top" -> 2
                "an_bottom" -> 3
                "at_depth" -> 4
                else -> element.content.toIntOrNull() ?: 0
            }
            else -> element.content.toIntOrNull() ?: 0
        }
        else -> 0
    }

    private fun parseRole(element: JsonElement?): Int = when (element) {
        is JsonPrimitive -> when {
            element.isString -> when (element.content.lowercase()) {
                "system" -> 0
                "user" -> 1
                "assistant" -> 2
                else -> element.content.toIntOrNull() ?: 0
            }
            else -> element.content.toIntOrNull() ?: 0
        }
        else -> 0
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun JsonObject.intOrNull(name: String): Int? = when (val v = this[name]) {
        is JsonPrimitive -> if (v.isString) v.content.toIntOrNull() else v.content.toIntOrNull()
        else -> null
    }

    private fun JsonObject.boolOrNull(name: String): Boolean? = when (val v = this[name]) {
        is JsonPrimitive -> when {
            v.isString -> v.content.equals("true", ignoreCase = true)
            else -> v.content.toBooleanStrictOrNull()
        }
        else -> null
    }

    private fun JsonObject.stringList(name: String): List<String> = when (val v = this[name]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        is JsonPrimitive -> if (v.isString && v.content.isNotBlank()) listOf(v.content) else emptyList()
        else -> emptyList()
    }
}

/**
 * 极简触发引擎（M0 版）：常量条目 + 关键词命中 + 次要关键词 + 预算截断。
 *
 * 明确不做（留给 M2 的 Prompt 管线一起做）：递归扫描、概率、sticky/cooldown、
 * 分角色/分深度的注入位置排序、token 级预算。
 */
class WorldInfoEngine(entries: List<WorldInfoEntry>, private val charBudget: Int = 8000) {

    private val active: List<WorldInfoEntry> = entries
        .filter { it.isActive() }
        .sortedWith(compareBy({ it.order }, { it.uid }))

    /** 按 order 升序返回被激活的条目，超出字符预算的跳过。 */
    fun activated(text: String): List<WorldInfoEntry> {
        val result = ArrayList<WorldInfoEntry>()
        var used = 0
        for (entry in active) {
            if (!matches(entry, text)) continue
            val cost = entry.content.length + 2
            if (result.isNotEmpty() && used + cost > charBudget) continue
            result += entry
            used += cost
        }
        return result
    }

    fun matches(entry: WorldInfoEntry, text: String): Boolean {
        if (entry.constant) return true
        if (entry.keys.isEmpty()) return false
        val primaryHit = entry.keys.any { matchesKey(it, text, entry) }
        if (!primaryHit) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        return entry.secondaryKeys.any { matchesKey(it, text, entry) }
    }

    private fun matchesKey(key: String, text: String, entry: WorldInfoEntry): Boolean {
        if (key.isBlank()) return false
        if (entry.useRegex) {
            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(key, options).containsMatchIn(text) }.getOrDefault(false)
        }
        if (entry.matchWholeWords) {
            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(key)}(?![\\p{L}\\p{N}])", options)
            return pattern.containsMatchIn(text)
        }
        return text.contains(key, ignoreCase = !entry.caseSensitive)
    }
}
