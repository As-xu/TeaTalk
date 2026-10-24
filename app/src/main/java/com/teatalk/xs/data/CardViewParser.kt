package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 详情页要展示的角色卡字段（从 `cardJson` 原始 JSON 按 V1/V2 双形态抽取）。 */
data class CardView(
    val name: String = "",
    val creator: String = "",
    val version: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val worldBook: List<WorldInfoEntry> = emptyList(),
)

/**
 * 宽容解析：V2 走 `data.*`，V1 直接读根对象；字段缺失一律空串，绝不抛异常。
 *
 * 之所以在 App 侧重解析而不是在 Room 里存散字段：`cardJson` 才是唯一事实来源（方案 §4.6），
 * 详情页展示属于"读视图"，随时可以推翻。
 */
object CardViewParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(cardJson: String): CardView = runCatching {
        val root = json.parseToJsonElement(cardJson) as? JsonObject ?: return@runCatching CardView()
        val data = (root["data"] as? JsonObject) ?: root
        CardView(
            name = data.string("name"),
            creator = data.string("creator"),
            version = data.string("character_version").ifBlank { data.string("version") },
            tags = data.stringList("tags"),
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            firstMes = data.string("first_mes"),
            mesExample = data.string("mes_example"),
            creatorNotes = data.string("creator_notes"),
            systemPrompt = data.string("system_prompt"),
            postHistoryInstructions = data.string("post_history_instructions"),
            worldBook = WorldInfoParser.fromCard(cardJson),
        )
    }.getOrDefault(CardView())

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun JsonObject.stringList(name: String): List<String> = when (val v = this[name]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
}
