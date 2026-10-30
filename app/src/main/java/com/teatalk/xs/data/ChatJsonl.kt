package com.teatalk.xs.data

import com.teatalk.xs.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * SillyTavern 聊天记录（`.jsonl`）读写。
 *
 * 文件结构（与 ST `chats/` 目录下的 `.jsonl` 文件一致）：
 * - 第 1 行是头：`{"chat_metadata":{...},"user_name":"unused","character_name":"unused"}`
 * - 之后每行一条消息：`{"name","is_user","is_system","send_date","mes","extra","swipe_id","swipes","swipe_info"}`
 *
 * 我们只依赖与对话语义相关的字段：`name/is_user/is_system/mes/swipes/swipe_id/send_date/extra.api/model`。
 * 其余字段导出时按 ST 的形状补默认值；导入时忽略未知字段，保证向前兼容。
 */
object ChatJsonl {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParsedMessage(
        val name: String,
        val isUser: Boolean,
        val isSystem: Boolean,
        val createdAt: Long,
        /** 当前选中的文本（= swipes[swipeId]，若数组异常则回退 mes）。 */
        val content: String,
        val swipes: List<String>,
        val swipeId: Int,
        val api: String?,
        val model: String?,
    ) {
        val role: String
            get() = when {
                isSystem -> ROLE_SYSTEM
                isUser -> ROLE_USER
                else -> ROLE_ASSISTANT
            }
    }

    data class Parsed(
        val messages: List<ParsedMessage>,
        val skippedLines: Int,
    )

    /**
     * 解析 ST `.jsonl` 文本。头行/空行/坏行都不会让整次导入失败：
     * 坏行计入 [Parsed.skippedLines]，其余照常返回。
     */
    fun parse(text: String): Parsed {
        val messages = ArrayList<ParsedMessage>()
        var skipped = 0
        var index = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            index++
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            if (obj == null || obj["mes"] == null) {
                // 头行（或其它非消息行）不算失败，仅跳过。
                continue
            }
            val parsed = runCatching { toMessage(obj) }.getOrNull()
            if (parsed == null) skipped++ else messages.add(parsed)
        }
        return Parsed(messages = messages, skippedLines = skipped)
    }

    private fun toMessage(obj: JsonObject): ParsedMessage? {
        val mes = (obj["mes"] as? JsonPrimitive)?.contentOrNull ?: return null
        val swipes = (obj["swipes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val swipeId = (obj["swipe_id"] as? JsonPrimitive)?.intOrNull ?: 0
        val content = swipes.getOrNull(swipeId) ?: mes
        val extra = obj["extra"] as? JsonObject
        val createdAt = runCatching {
            (obj["send_date"] as? JsonPrimitive)?.contentOrNull?.let { Instant.parse(it).toEpochMilli() }
        }.getOrNull() ?: System.currentTimeMillis()
        return ParsedMessage(
            name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            isUser = (obj["is_user"] as? JsonPrimitive)?.booleanOrNull ?: false,
            isSystem = (obj["is_system"] as? JsonPrimitive)?.booleanOrNull ?: false,
            createdAt = createdAt,
            content = content,
            swipes = if (swipes.isEmpty()) listOf(mes) else swipes,
            swipeId = swipeId.coerceAtLeast(0),
            api = (extra?.get("api") as? JsonPrimitive)?.contentOrNull,
            model = (extra?.get("model") as? JsonPrimitive)?.contentOrNull,
        )
    }

    /**
     * 导出为 ST 可读的 `.jsonl`：头行 + 每条消息一行。
     * 多版本回复写成 `swipes`/`swipe_id`/`swipe_info`，单版本也写成单元素数组（ST 同样处理）。
     */
    fun export(characterName: String, messages: List<MessageEntity>): String {
        val builder = StringBuilder()
        builder.append(
            buildJsonObject {
                put("chat_metadata", buildJsonObject {})
                put("user_name", "unused")
                put("character_name", "unused")
            }.toString(),
        )
        builder.append('\n')
        for (message in messages) {
            val variants = MessageVariants.of(message)
            val swipes = if (variants.isEmpty()) listOf(message.content) else variants
            val swipeId = message.selectedVariant.coerceIn(0, (swipes.size - 1).coerceAtLeast(0))
            val sendDate = Instant.ofEpochMilli(message.createdAt).toString()
            val isUser = message.role == ROLE_USER
            val isSystem = message.role == ROLE_SYSTEM
            val line = buildJsonObject {
                put("name", if (isUser) "User" else characterName.ifBlank { "Character" })
                put("is_user", isUser)
                put("is_system", isSystem)
                put("send_date", sendDate)
                put("mes", message.content)
                put("extra", buildJsonObject {
                    message.model?.let { put("model", it) }
                    if (message.providerName != null) put("api", message.providerName)
                })
                put("swipe_id", swipeId)
                put("swipes", buildJsonArray { swipes.forEach { add(JsonPrimitive(it)) } })
                put("swipe_info", buildJsonArray {
                    swipes.forEach {
                        add(buildJsonObject {
                            put("send_date", sendDate)
                            put("extra", buildJsonObject {})
                        })
                    }
                })
            }
            builder.append(line.toString())
            builder.append('\n')
        }
        return builder.toString()
    }

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
    const val ROLE_SYSTEM = "system"
}
