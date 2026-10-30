package com.teatalk.xs.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手写角色卡（M4-3）：生成与 ST「Character Card V2」一致的结构，交给现有解析/编辑链路复用。
 *
 * 只产出最小可用字段；后续可在「编辑卡」里继续补（M3 的高级字段也都能写回这份 JSON）。
 */
object ManualCard {

    data class Draft(
        val name: String,
        val description: String = "",
        val personality: String = "",
        val scenario: String = "",
        val firstMessage: String = "",
        val systemPrompt: String = "",
    )

    fun create(draft: Draft): String = buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put(
            "data",
            buildJsonObject {
                put("name", draft.name.trim())
                put("description", draft.description)
                put("personality", draft.personality)
                put("scenario", draft.scenario)
                put("first_mes", draft.firstMessage)
                put("mes_example", "")
                put("creator_notes", "")
                put("system_prompt", draft.systemPrompt)
                put("post_history_instructions", "")
                put("tags", buildJsonArray { })
                put("creator", "")
                put("character_version", "1.0")
                put("extensions", JsonObject(emptyMap()))
            },
        )
    }.toString()
}
