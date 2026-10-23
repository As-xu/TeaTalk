package com.teatalk.core.card

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * SillyTavern 角色卡 V2（`spec = chara_card_v2`）。
 *
 * 字段名必须与 ST 逐字对齐——角色卡是外部输入，改名 = 导入丢数据。
 * V3（`spec = chara_card_v3`）复用同一 data 结构，只多 `group_only_greetings` 等少量字段，
 * 后续以 `JsonObject` 兜底未知字段，保证前向兼容。
 */
@Serializable
data class StCharacterCard(
    @SerialName("spec") val spec: String = "chara_card_v2",
    @SerialName("spec_version") val specVersion: String = "2.0",
    @SerialName("data") val data: StCardData,
)

@Serializable
data class StCardData(
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("personality") val personality: String = "",
    @SerialName("scenario") val scenario: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    @SerialName("character_book") val characterBook: JsonObject? = null,
    @SerialName("extensions") val extensions: JsonObject = JsonObject(emptyMap()),
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("creator") val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
)

/** 角色卡来源；决定解析器选择（PNG 的 tEXt chunk / JSON / 压缩包）。 */
enum class CardSource { PNG_V2, PNG_V3, JSON_V2, CHARX }

/** 导入结果：永远返回一个可用的卡 + 警告列表，而不是抛异常——真实卡经常缺字段。 */
data class ImportedCard(
    val source: CardSource,
    val card: StCharacterCard,
    val warnings: List<String> = emptyList(),
)
