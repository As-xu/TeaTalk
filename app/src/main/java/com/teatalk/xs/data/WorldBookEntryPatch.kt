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
 */
object WorldBookEntryPatch {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 返回整本世界书 patch 后的 JSON；找不到目标条目或结构不认识时返回 null。 */
    fun patch(bookJson: String, entry: WorldInfoEntry): String? {
        val root = runCatching { json.parseToJsonElement(bookJson) }.getOrNull() ?: return null
        return when (root) {
            is JsonArray -> patchEntriesArray(root, entry, cardStyle = true)?.let { patched ->
                JsonArray(root + patched).toString()
            }
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
                val key = entry.uid.toString()
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
            entries.forEach { element ->
                val obj = element as? JsonObject
                if (obj != null && obj.uid() == entry.uid) {
                    found = true
                    add(patchEntry(obj, entry, cardStyle))
                } else {
                    add(element)
                }
            }
        }
        return if (found) patched else null
    }

    private fun JsonObject.uid(): Int? = (this["uid"] as? JsonPrimitive)?.content?.toIntOrNull()

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
            put("order", entry.order)
            put("constant", entry.constant)
            put("selective", entry.selective)
            put("selectiveLogic", entry.selectiveLogic)
            put("caseSensitive", entry.caseSensitive)
            put("matchWholeWords", entry.matchWholeWords)
            if (cardStyle) {
                put("keys", keysArray)
                put("secondary_keys", secondaryArray)
                put("enabled", entry.enabled)
                // 解析器与 ST 都是「extensions 优先」，位置类字段写进 extensions 才不会被卡面默认值覆盖。
                val ext = (target["extensions"] as? JsonObject) ?: JsonObject(emptyMap())
                put(
                    "extensions",
                    buildJsonObject {
                        ext.forEach { (k, v) -> put(k, v) }
                        put("position", entry.position)
                        put("depth", entry.depth)
                        put("role", entry.role)
                    },
                )
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
