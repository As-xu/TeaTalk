package com.teatalk.core.card

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * SillyTavern 角色卡（V2 / V3 共用 data 结构）。
 *
 * 字段名必须与 ST 逐字对齐——角色卡是外部输入，改名 = 导入丢数据。
 * 未知字段/未来版本字段全部保留在 [raw] 里，导出时原样写回（前向兼容）。
 */
@Serializable
data class StCharacterCard(
    @SerialName("spec") val spec: String = SPEC_V2,
    @SerialName("spec_version") val specVersion: String = "2.0",
    @SerialName("data") val data: StCardData,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        const val SPEC_V2 = "chara_card_v2"
        const val SPEC_V3 = "chara_card_v3"
    }
}

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
    @SerialName("group_only_greetings") val groupOnlyGreetings: List<String> = emptyList(),
    @SerialName("character_book") val characterBook: JsonObject? = null,
    @SerialName("extensions") val extensions: JsonObject = JsonObject(emptyMap()),
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("creator") val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    @SerialName("nickname") val nickname: String = "",
)

/** 角色卡来源；决定解析器选择（PNG 的 tEXt chunk / JSON / 压缩包）。 */
enum class CardSource {
    /** PNG 内只有 chara（V2）chunk。 */
    PNG_V2,

    /** PNG 内有 ccv3 chunk（ST 写卡时会同时写 chara + ccv3，读时优先用 ccv3）。 */
    PNG_V3,

    JSON_V2,
    JSON_V3,

    /** .charx 压缩包（card.json 在根目录）。 */
    CHARX,

    /** ST 早期平铺字段的老卡（无 spec / data），导入时升级成 V2 结构。 */
    LEGACY_JSON,
}

/** 导入结果：永远返回一个可用的卡 + 警告列表，而不是抛异常——真实卡经常缺字段。 */
data class ImportedCard(
    val source: CardSource,
    val card: StCharacterCard,
    val warnings: List<String> = emptyList(),
)

/** 文件根本不是角色卡时抛出（不是"卡有问题"，是"这不是卡"）。 */
/**
 * 「根本不是角色卡」的分类原因。
 *
 * 解析层很宽容：字段缺失只记 warnings；只有下面这些情况才拒绝导入。
 * 带上原因是为了让 UI 给出可操作的提示（「这是普通图片」vs「JSON 坏了」）。
 */
enum class CardImportReason {
    /** 是 PNG，但没有 chara / ccv3 文本块（普通图片/截图）。 */
    NO_CHARACTER_DATA,

    /** 是 JPEG 等不支持携带角色卡数据的图片格式。 */
    UNSUPPORTED_IMAGE,

    /** 既不是 PNG/ZIP，也不是可读文本（未知二进制文件）。 */
    UNSUPPORTED_FILE,

    /** ZIP/CHARX 里没有 card.json。 */
    MISSING_CARD_JSON,

    /** CHARX 里的 card.json 超过解压上限（压缩炸弹防护）。 */
    CARD_JSON_TOO_LARGE,

    /** JSON 无法解析，或顶层不是对象。 */
    BAD_JSON,

    /** 声明是 V2/V3，但缺少 data 对象。 */
    MISSING_DATA,
}

class CardImportException(
    val reason: CardImportReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
