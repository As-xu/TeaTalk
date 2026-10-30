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
 *
 * **V2 卡的关键坑**：ST 导出时把 `position / depth / role / probability / group / case_sensitive /
 * match_whole_words / selectiveLogic` 写进 `entries[].extensions.*`，而 `convertCharacterBook`
 * （`SillyTavern/public/scripts/world-info.js:5617`）也是 **extensions 优先、顶层兜底**。
 * 只读顶层会把这些字段全部落默认值——真实卡里 `position=0/1`、`depth=4`、`role=0`、
 * `probability=100` 看似正常，其实来自默认值而非卡面配置。
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
    /**
     * 正则开关：ST 的 `convertCharacterBook` **不映射** `use_regex`，但导出时无条件写 `true`；
     * 因此角色卡里的该字段一律不采纳（否则含 `(`/`[` 的普通键会被当正则解释）。
     * 该字段仅供 M2 的全局"世界书正则"设置使用。
     */
    val useRegex: Boolean = false,
    /** ST selectiveLogic：0=AND_ANY，1=AND_ALL，2=NOT_ALL，3=NOT_ANY */
    val selectiveLogic: Int = 0,
    val selective: Boolean = false,
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

    /** 解析出的未知 position 值（不受支持，注入时丢弃）。 */
    const val UNSUPPORTED_POSITION = -1

    /**
     * 统一入口：角色卡 / 独立世界书文件 / 根数组三种形态都吃。
     *
     * 顺序：`data.character_book` → 顶层 `entries`（对象或数组）→ 根数组。
     * 卡片与独立文件共用同一套解析，避免「显示 N 条、注入 0 条」。
     */
    fun fromAny(json: String): List<WorldInfoEntry> = runCatching {
        when (val root = this.json.parseToJsonElement(json)) {
            is JsonArray -> entriesOf(root).mapIndexed { index, (key, entry) ->
                normalize(entry, isCard = false, fallbackUid = key?.toIntOrNull() ?: index)
            }
            is JsonObject -> {
                val book = (root["data"] as? JsonObject)?.get("character_book") as? JsonObject
                if (book != null) parseCharacterBook(book) else parseWorldInfoFile(root)
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** 从角色卡原始 JSON 抽世界书（同 [fromAny]）。 */
    fun fromCard(cardJson: String): List<WorldInfoEntry> = fromAny(cardJson)

    /** 独立世界书文件（ST 世界书 JSON：`{entries:{…}}` 或根数组）——D6 导入的 `world_books.entriesJson` 走这里。 */
    fun fromStandalone(json: String): List<WorldInfoEntry> = fromAny(json)

    /** V2 角色卡内嵌世界书（`isCard=true`：ST 导出默认 selective=false）。 */
    fun parseCharacterBook(book: JsonObject): List<WorldInfoEntry> =
        entriesOf(book["entries"]).mapIndexed { index, (key, entry) ->
            normalize(entry, isCard = true, fallbackUid = key?.toIntOrNull() ?: index)
        }

    /** SillyTavern 独立世界书文件 / 角色卡顶层 entries（模板默认 selective=true）。 */
    fun parseWorldInfoFile(root: JsonObject): List<WorldInfoEntry> =
        entriesOf(root["entries"]).mapIndexed { index, (key, entry) ->
            normalize(entry, isCard = false, fallbackUid = key?.toIntOrNull() ?: index)
        }

    /** 条目既可能是数组（V2 卡），也可能是 `{"<uid>":{…}}` 对象（ST 世界书）；保留对象 key 供 uid 兜底。 */
    private fun entriesOf(element: JsonElement?): List<Pair<String?, JsonObject>> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }.map { null to it }
        is JsonObject -> element.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
        else -> emptyList()
    }

    private fun normalize(e: JsonObject, isCard: Boolean, fallbackUid: Int): WorldInfoEntry {
        val extensions = (e["extensions"] as? JsonObject) ?: JsonObject(emptyMap())
        return WorldInfoEntry(
            uid = e.intOrNull("uid") ?: e.intOrNull("id") ?: fallbackUid,
            comment = e.string("comment"),
            // ST 独立世界书把关键词存成逗号字符串（`"key": "茶,tea"`）；数组形态也兼容。
            keys = e.stringList("keys").ifEmpty { e.keyList("key") },
            secondaryKeys = e.stringList("secondary_keys").ifEmpty { e.keyList("keysecondary") },
            content = e.string("content"),
            constant = e.boolOrNull("constant") ?: false,
            enabled = e.boolOrNull("enabled") ?: !(e.boolOrNull("disable") ?: false),
            order = e.intOrNull("insertion_order") ?: e.intOrNull("order") ?: 100,
            // —— 以下字段均 extensions 优先（对齐 ST convertCharacterBook）——
            position = extensions.intOrNull("position") ?: parsePosition(e["position"]),
            depth = extensions.intOrNull("depth") ?: e.intOrNull("depth") ?: 4,
            role = extensions.intOrNull("role") ?: parseRole(e["role"]),
            caseSensitive = extensions.boolOrNull("case_sensitive")
                ?: e.boolOrNull("case_sensitive") ?: e.boolOrNull("caseSensitive") ?: false,
            matchWholeWords = extensions.boolOrNull("match_whole_words")
                ?: e.boolOrNull("match_whole_words") ?: e.boolOrNull("matchWholeWords") ?: false,
            useRegex = false, // 见字段注释：卡面 use_regex 不可信，不予采纳
            selectiveLogic = extensions.intOrNull("selectiveLogic")
                ?: extensions.intOrNull("selective_logic") ?: 0,
            selective = e.boolOrNull("selective") ?: !isCard,
            group = extensions.string("group").ifEmpty { e.string("group") },
            probability = extensions.intOrNull("probability") ?: e.intOrNull("probability") ?: 100,
            useProbability = extensions.boolOrNull("useProbability")
                ?: extensions.boolOrNull("use_probability")
                ?: e.boolOrNull("use_probability") ?: e.boolOrNull("useProbability") ?: true,
            extensions = extensions,
        )
    }

    private fun parsePosition(element: JsonElement?): Int = when (element) {
        // 字段缺失 / null：ST 默认 0（before_char），不能当“不受支持”丢弃。
        null -> 0
        is JsonPrimitive -> when {
            element.isString -> when (element.content.lowercase()) {
                "before_char" -> 0
                "after_char" -> 1
                "an_top" -> 2
                "an_bottom" -> 3
                "at_depth" -> 4
                // 未知字符串：返回 -1（不受支持），由 WorldInfoInjector.select 丢弃；
                // 不能回落 0，否则会跑到 system prompt 最前。
                else -> element.content.toIntOrNull() ?: UNSUPPORTED_POSITION
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
        is JsonPrimitive -> v.content.toIntOrNull()
        else -> null
    }

    private fun JsonObject.boolOrNull(name: String): Boolean? = when (val v = this[name]) {
        is JsonPrimitive -> when {
            v.isString -> v.content.equals("true", ignoreCase = true)
            else -> v.content.toBooleanStrictOrNull()
        }
        else -> null
    }

    /** ST 文件形态：`"key": "a, b"` 或数组 `["a","b"]` 都吃。 */
private fun JsonObject.keyList(name: String): List<String> = when (val v = this[name]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    is JsonPrimitive -> if (v.isString) v.content.split(',').map(String::trim).filter(String::isNotEmpty) else emptyList()
    else -> emptyList()
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
 * 排序对齐 ST `world-info.js:88` 的 `sortFn = (a, b) => b.order - a.order`（**降序**）。
 *
 * 明确不做（留给 M2 的 Prompt 管线一起做）：递归扫描、概率、sticky/cooldown、
 * 分角色/分深度的注入位置排序、token 级预算。
 */
class WorldInfoEngine(entries: List<WorldInfoEntry>, private val charBudget: Int = 8000) {

    private val active: List<WorldInfoEntry> = entries
        .filter { it.isActive() }
        .sortedWith(compareByDescending<WorldInfoEntry> { it.order }.thenBy { it.uid })

    /** 按 order 降序返回被激活的条目，超出字符预算的跳过。 */
    fun activated(text: String): List<WorldInfoEntry> {
        val result = ArrayList<WorldInfoEntry>()
        var used = 0
        for (entry in active) {
            if (!matches(entry, text)) continue
            val cost = entry.content.length + 2
            // 单条就超预算：整条跳过，不因「结果还为空」而放行（审阅 round 2 P1）。
            if (cost > charBudget) continue
            if (used + cost > charBudget) continue
            result += entry
            used += cost
        }
        return result
    }

    fun matches(entry: WorldInfoEntry, text: String): Boolean {
        if (entry.constant) return true
        if (entry.keys.isEmpty()) return false
        if (entry.keys.none { matchesKey(it, text, entry) }) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        val hits = entry.secondaryKeys.count { matchesKey(it, text, entry) }
        return when (entry.selectiveLogic) {
            1 -> hits == entry.secondaryKeys.size // AND_ALL
            2 -> hits == 0 // NOT_ALL
            3 -> hits != entry.secondaryKeys.size // NOT_ANY
            else -> hits > 0 // AND_ANY（默认）
        }
    }

    private fun matchesKey(key: String, text: String, entry: WorldInfoEntry): Boolean {
        if (key.isBlank()) return false
        if (entry.matchWholeWords) {
            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(key)}(?![\\p{L}\\p{N}])", options)
            return pattern.containsMatchIn(text)
        }
        return text.contains(key, ignoreCase = !entry.caseSensitive)
    }
}
