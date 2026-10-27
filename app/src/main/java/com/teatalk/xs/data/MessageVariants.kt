package com.teatalk.xs.data

import com.teatalk.xs.data.db.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 同一条回复的「多版本」读写（M2）。
 *
 * 存法：`messages.variantsJson` 是 JSON 字符串数组，`content` 只是 `variants[selectedVariant]` 的
 * 镜像（聊天列表预览、prompt 历史都读 content，保持老代码兼容）。
 *
 * 兼容老数据：`variantsJson` 为空数组时，视为「只有 content 这一个版本」。
 */
object MessageVariants {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(String.serializer())

    fun of(message: MessageEntity): List<String> =
        parse(message.variantsJson, message.content)

    fun parse(variantsJson: String, content: String): List<String> {
        val decoded = runCatching { json.decodeFromString(serializer, variantsJson) }
            .getOrDefault(emptyList())
            .filter { it.isNotEmpty() }
        return decoded.ifEmpty { listOf(content) }
    }

    fun encode(variants: List<String>): String =
        json.encodeToString(serializer, variants.filter { it.isNotEmpty() })
}
