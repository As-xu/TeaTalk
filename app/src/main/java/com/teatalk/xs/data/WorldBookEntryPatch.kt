package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 世界书条目编辑：直接在**原始 JSON** 上打补丁，保留解析器不认识/暂不编辑的字段。
 *
 * 覆盖两种形态：
 * - ST 独立世界书文件：`{ "entries": { "<uid>": { "key": "a,b", "position": 0 … } } }`
 *   （也接受 `entries` 是数组的旧文件）
 * - 角色卡内嵌：`{ "data": { "character_book": { "entries": [ { "keys": ["a"], "extensions": {…} } ] } } }`
 *
 * 为什么不反序列化再序列化：`WorldInfoEntry` 只保留了我们认识的字段，
 * 写回会把 ST 的 `group_weight / automation_id / …` 全部抹掉。这里只改被编辑的字段。
 *
 * 写回时必须与 `WorldInfoParser` 的读取优先级保持一致，否则会出现「保存了但界面/ST 读不到」：
 * - order：解析器先读 `insertion_order`，再读 `order`；两者都写，保证任何读取方一致。
 * - case/match：解析器先读 `extensions.case_sensitive`，再读 `case_sensitive`，最后 `caseSensitive`。
 *   卡片形态把规范字段写进 extensions，同时写顶层两种拼写兼容其它读取方。
 * - uid 匹配：解析器在条目没有 `uid` 时回落到 `id`，再回落到数组下标；补丁也按同一规则匹配。
 */
object WorldBookEntryPatch {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 返回整本世界书 patch 后的 JSON；找不到目标条目或结构不认识时返回 null。 */
    fun patch(bookJson: String, entry: WorldInfoEntry): String? {
        val root = runCatching { json.parseToJsonElement(bookJson) }.getOrNull() ?: return null
        return when (root) {
            // 根数组：直接返回补丁后的数组，不能与原文拼接（否则整本内容重复）。
            is JsonArray -> patchEntriesArray(root, entry, cardStyle = true)?.toString()
            is JsonObject -> patchObject(root, entry)
            else -> null
        }
    }

    private fun patchObject(root: JsonObject, entry: WorldInfoEntry): String? {
        val data = root["data"] as? JsonObject
        val cardBook = data?.get("character_book") as? JsonObject
        if (cardBook != null) {
            val patched = patchEntries(cardBook, entry, cardStyle = true) ?: return null
            val newBook = cardBook.replaceField("entries", patched.entries)
            val newData = data.replaceField("character_book", newBook)
            return root.replaceField("data", newData).toString()
        }
        val patched = patchEntries(root, entry, cardStyle = false) ?: return null
        return root.replaceField("entries", patched.entries).toString()
    }

    private class Patched(val entries: JsonElement)

    private fun patchEntries(root: JsonObject, entry: WorldInfoEntry, cardStyle: Boolean): Patched? {
        return when (val entries = root["entries"]) {
            is JsonObject -> {
                val byKey = entries[entry.uid.toString()] as? JsonObject
                val targetKey = byKey?.let { entry.uid.toString() }
                    ?: entries.entries.firstOrNull { (k, v) ->
                        val obj = v as? JsonObject ?: return@firstOrNull false
                        obj.effectiveUid(k.toIntOrNull() ?: -1) == entry.uid
                    }?.key
                val key = targetKey ?: return null
                val target = entries[key] as? JsonObject ?: return null
                Patched(
                    buildJsonObject {
                        entries.forEach { (k, v) -> put(k, v) }
                        put(key, patchEntry(target, entry, cardStyle))
                    },
                )
            }
            is JsonArray -> patchEntriesArray(entries, entry, cardStyle)?.let { Patched(it) }
            else -> null
        }
    }

    private fun patchEntriesArray(entries: JsonArray, entry: WorldInfoEntry, cardStyle: Boolean): JsonArray? {
        var found = false
        val patched = buildJsonArray {
            entries.forEachIndexed { index, element ->
                val obj = element as? JsonObject
                if (obj != null && obj.effectiveUid(index) == entry.uid) {
                    found = true
                    add(patchEntry(obj, entry, cardStyle))
                } else {
                    add(element)
                }
            }
        }
        return if (found) patched else null
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** 与解析器同口径：uid → id → 数组下标 / 对象键。 */
    private fun JsonObject.effectiveUid(fallback: Int): Int? =
        intOrNull("uid") ?: intOrNull("id") ?: fallback

    private fun JsonObject.replaceField(key: String, value: JsonElement): JsonObject = buildJsonObject {
        this@replaceField.forEach { (k, v) -> if (k != key) put(k, v) }
        put(key, value)
    }

    private fun patchEntry(target: JsonObject, entry: WorldInfoEntry, cardStyle: Boolean): JsonObject {
        val keysArray = buildJsonArray { entry.keys.forEach { add(JsonPrimitive(it)) } }
        val secondaryArray = buildJsonArray { entry.secondaryKeys.forEach { add(JsonPrimitive(it)) } }
        return buildJsonObject {
            target.forEach { (k, v) -> put(k, v) }
            put("comment", entry.comment)
            put("content", entry.content)
            // 解析器优先读 insertion_order；两个都写，避免两套读取方看到不同的值。
            put("order", entry.order)
            put("insertion_order", entry.order)
            put("constant", entry.constant)
            put("selective", entry.selective)
            put("selectiveLogic", entry.selectiveLogic)
            // 顶层同时写 camelCase 与 snake_case，兼容不同读取方。
            put("caseSensitive", entry.caseSensitive)
            put("case_sensitive", entry.caseSensitive)
            put("matchWholeWords", entry.matchWholeWords)
            put("match_whole_words", entry.matchWholeWords)
            val ext = (target["extensions"] as? JsonObject) ?: JsonObject(emptyMap())
            if (cardStyle || target.containsKey("extensions")) {
                // 解析器 extensions 优先，规范字段必须写进这里，否则会被卡面默认值罩住。
                put(
                    "extensions",
                    buildJsonObject {
                        ext.forEach { (k, v) -> put(k, v) }
                        put("case_sensitive", entry.caseSensitive)
                        put("match_whole_words", entry.matchWholeWords)
                        if (cardStyle) {
                            put("position", entry.position)
                            put("depth", entry.depth)
                            put("role", entry.role)
                        }
                    },
                )
            }
            if (cardStyle) {
                put("keys", keysArray)
                put("secondary_keys", secondaryArray)
                put("enabled", entry.enabled)
            } else {
                put("key", entry.keys.joinToString(", "))
                put("keysecondary", entry.secondaryKeys.joinToString(", "))
                put("disable", !entry.enabled)
                put("position", entry.position)
                put("depth", entry.depth)
                put("role", entry.role)
            }
        }
    }
}
