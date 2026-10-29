package com.teatalk.xs.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 世界书 id 列表的序列化/反序列化（`characters.linkedWorldBookIdsJson` 等列）。
 *
 * 反序列化必须宽容：历史数据里出现过空串、`null`、坏 JSON 与对象形态。
 */
internal object WorldBookIds {

    fun encode(ids: List<Long>): String =
        ids.distinct().sorted().joinToString(prefix = "[", postfix = "]", separator = ",")

    fun parse(json: String?): List<Long> {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as JsonArray
            array.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }.distinct()
        }.getOrDefault(emptyList())
    }
}
