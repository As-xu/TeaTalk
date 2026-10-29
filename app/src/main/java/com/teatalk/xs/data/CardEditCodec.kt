package com.teatalk.xs.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 把编辑结果写回 cardJson（纯函数，便于单测）。
 *
 * V2/V3 卡写 `data.*`；V1 平铺卡写根对象。空字符串视为「删除该键」，
 * 避免把空壳字段写进别人的卡里。无法解析的 JSON 返回 null，由调用方提示失败。
 */
fun applyCardEdit(cardJson: String, edit: CardEdit): String? {
    val root = runCatching { Json.parseToJsonElement(cardJson).jsonObject }.getOrNull() ?: return null
    val data = root["data"] as? JsonObject
    val target = data ?: root
    val updated = target.toMutableMap().apply {
        put("name", JsonPrimitive(edit.name))
        put("description", JsonPrimitive(edit.description))
        put("personality", JsonPrimitive(edit.personality))
        put("scenario", JsonPrimitive(edit.scenario))
        put("first_mes", JsonPrimitive(edit.firstMes))
        put("creator_notes", JsonPrimitive(edit.creatorNotes))
        put("tags", JsonArray(edit.tags.map(::JsonPrimitive)))
        putText("system_prompt", edit.systemPrompt)
        putText("post_history_instructions", edit.postHistoryInstructions)
        putText("mes_example", edit.mesExample)
        if (edit.alternateGreetings.isEmpty()) {
            remove("alternate_greetings")
        } else {
            put("alternate_greetings", JsonArray(edit.alternateGreetings.map(::JsonPrimitive)))
        }
    }
    return if (data != null) {
        JsonObject(root.toMutableMap().apply { put("data", JsonObject(updated)) }).toString()
    } else {
        JsonObject(updated).toString()
    }
}

private fun MutableMap<String, JsonElement>.putText(key: String, value: String) {
    if (value.isBlank()) remove(key) else put(key, JsonPrimitive(value))
}
