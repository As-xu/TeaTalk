package com.teatalk.xs.data.model

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 生成参数（M3 步骤 4：SillyTavern 预设导入）。
 *
 * 只取「请求体直接能吃」的五个采样参数；ST 预设里其余字段（提示词列表、上下文长度策略等）
 * 暂不映射——它们要等固定管线/预算那一步，提前抄进来只会造成「看起来生效、其实没生效」。
 *
 * 全部可空：null = 该参数不显式发送，作为服务端/接口配置的默认值。
 */
data class SamplingPreset(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
) {
    val isEmpty: Boolean
        get() = temperature == null && topP == null && maxTokens == null &&
            frequencyPenalty == null && presencePenalty == null
}

/** 预设读写（SharedPreferences 一行 JSON；不涉及密钥，不需要 Encrypted 存储）。 */
object SamplingPresetStore {
    private const val PREFS = "teatalk_prefs"
    private const val KEY = "sampling_preset_json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(context: Context): SamplingPreset? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching { parseJson(raw) }.getOrNull()
    }

    fun write(context: Context, preset: SamplingPreset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, toJson(preset))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun toJson(p: SamplingPreset): String = buildString {
        append('{')
        p.temperature?.let { append("\"temperature\":").append(it).append(',') }
        p.topP?.let { append("\"topP\":").append(it).append(',') }
        p.maxTokens?.let { append("\"maxTokens\":").append(it).append(',') }
        p.frequencyPenalty?.let { append("\"frequencyPenalty\":").append(it).append(',') }
        p.presencePenalty?.let { append("\"presencePenalty\":").append(it) }
        if (endsWith(',')) setLength(length - 1)
        append('}')
    }

    private fun parseJson(raw: String): SamplingPreset {
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: return SamplingPreset()
        return SamplingPreset(
            temperature = obj.float("temperature"),
            topP = obj.float("topP"),
            maxTokens = obj.int("maxTokens"),
            frequencyPenalty = obj.float("frequencyPenalty"),
            presencePenalty = obj.float("presencePenalty"),
        )
    }

    /**
     * 解析 SillyTavern 预设 JSON：兼容 `temp_openai`/`temperature`、`top_p_openai`/`top_p`、
     * `openai_max_tokens`/`max_tokens`、`freq_pen_openai`/`frequency_penalty`、`pres_pen_openai`/`presence_penalty`。
     * 值可能是数字，也可能是字符串（ST 的 UI 存的是字符串）。
     */
    fun parseStPreset(raw: String): SamplingPreset? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key -> obj.double(key) }
        val preset = SamplingPreset(
            temperature = num("temp_openai", "temperature")?.toFloat(),
            topP = num("top_p_openai", "top_p")?.toFloat(),
            maxTokens = num("openai_max_tokens", "max_tokens")?.toInt(),
            frequencyPenalty = num("freq_pen_openai", "frequency_penalty")?.toFloat(),
            presencePenalty = num("pres_pen_openai", "presence_penalty")?.toFloat(),
        )
        return preset.takeUnless { it.isEmpty }
    }

    fun summary(preset: SamplingPreset): String {
        val parts = buildList {
            preset.temperature?.let { add("temp $it") }
            preset.topP?.let { add("top_p $it") }
            preset.maxTokens?.let { add("max $it") }
            preset.frequencyPenalty?.let { add("freq $it") }
            preset.presencePenalty?.let { add("pres $it") }
        }
        return parts.joinToString(" · ")
    }

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()

    private fun JsonObject.double(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return primitive.content.takeIf { it.isNotBlank() }?.toDoubleOrNull()
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
