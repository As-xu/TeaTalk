package com.teatalk.xs.data.net

import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 非流式的 `/chat/completions` 客户端，专供翻译。
 *
 * 翻译不需要流式（一次几十到几百字），非流式更简单、也更容易做错误处理；
 * 与 [ChatClient] 共用 OkHttp 默认配置，但单独一个实例，互不影响超时设置。
 */
class TranslationClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.2,
        maxTokens: Int = 4096,
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val host = Redact.url(url)
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                },
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val started = System.currentTimeMillis()
        TeaTalkLog.i("Net", "trans.req.start", "host" to host, "model" to model)
        try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                if (!response.isSuccessful) {
                    TeaTalkLog.w("Net", "trans.req.fail", "host" to host, "status" to response.code)
                    return@use Result.failure(
                        IllegalStateException("HTTP ${response.code}"),
                    )
                }
                val text = extractText(body)
                if (text.isBlank()) {
                    TeaTalkLog.w("Net", "trans.req.empty", "host" to host)
                    return@use Result.failure(IllegalStateException("empty translation"))
                }
                TeaTalkLog.i(
                    "Net",
                    "trans.req.ok",
                    "host" to host,
                    "ms" to (System.currentTimeMillis() - started),
                    "chars" to text.length,
                )
                Result.success(text)
            }
        } catch (e: IOException) {
            TeaTalkLog.w("Net", "trans.req.fail", "host" to host, "kind" to e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /** 解析 `choices[0].message.content`；容忍服务端把 content 返回成数组片段。 */
    internal fun extractText(body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val choices = root.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message")
        val content = message?.opt("content")
        val raw = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i)
                    append(part?.optString("text").orEmpty())
                }
            }
            else -> ""
        }
        return clean(raw)
    }

    /** 去掉模型偶尔多加的包裹（引号 / ``` 代码块），保留内部换行与标记。 */
    private fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```")
            // 去掉语言标注行（```text）
            val firstBreak = text.indexOf('\n')
            if (firstBreak in 0..20) text = text.substring(firstBreak + 1)
            text = text.trim()
        }
        if (text.length > 1) {
            val first = text.first()
            val last = text.last()
            if ((first == '"' && last == '"') || (first == '「' && last == '」') || (first == '“' && last == '”')) {
                text = text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
