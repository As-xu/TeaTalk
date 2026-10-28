package com.teatalk.xs.data.net

import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.core.net.Sse
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 一次流式请求里发生的事。 */
sealed interface ChatEvent {
    /** 本次请求最终使用的接口与模型（调用方据此落库来源，不再旁路读取）。 */
    data class Started(val providerId: String, val providerName: String, val model: String) : ChatEvent

    data class Delta(val text: String) : ChatEvent

    /** 正常结束；[truncated] 表示服务端因长度上限提前截断（finish_reason=length）。 */
    data class Completed(val truncated: Boolean = false) : ChatEvent

    data class Failed(val error: ChatError, val detail: String? = null) : ChatEvent
}

sealed interface ChatError {
    data object NO_PROVIDER : ChatError

    /** Provider 行存在但 key 丢失（如加密存储恢复失败）。 */
    data object NO_KEY : ChatError

    /** 没有选择默认模型（不要拿角色名兜底）。 */
    data object NO_MODEL : ChatError

    data object UNAUTHORIZED : ChatError

    data object RATE_LIMITED : ChatError

    /** 其它 4xx/5xx：带状态码，便于用户投诉/排查。 */
    data class HTTP(val code: Int) : ChatError

    data object TIMEOUT : ChatError

    data object NETWORK : ChatError

    data object BAD_RESPONSE : ChatError
}

/** 日志用的稳定短名（HTTP 带状态码，便于 grep）。 */
val ChatError.label: String
    get() = when (this) {
        ChatError.NO_PROVIDER -> "NO_PROVIDER"
        ChatError.NO_KEY -> "NO_KEY"
        ChatError.NO_MODEL -> "NO_MODEL"
        ChatError.UNAUTHORIZED -> "UNAUTHORIZED"
        ChatError.RATE_LIMITED -> "RATE_LIMITED"
        ChatError.TIMEOUT -> "TIMEOUT"
        ChatError.NETWORK -> "NETWORK"
        ChatError.BAD_RESPONSE -> "BAD_RESPONSE"
        is ChatError.HTTP -> "HTTP_${code}"
    }

/**
 * OpenAI 兼容的 `/chat/completions` 流式客户端（SSE）。
 *
 * 只做传输与解析：prompt 组装在 [com.teatalk.xs.data.ChatEngine]，
 * key 不落日志，URL 只记 host。
 */
class ChatClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.8,
    ): Flow<ChatEvent> = flow {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val host = Redact.url(url)
        val started = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        TeaTalkLog.i("Net", "chat.req.start", "host" to host, "stream" to true, "msgs" to messages.size)
        var chunks = 0
        var chars = 0
        var firstTokenMs = -1L
        var truncated = false
        val collected = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    // 401 才是「key 无效」；403 是「key 有效但没权限」，带上状态码让文案不误导。
                    val error = when (response.code) {
                        401 -> ChatError.UNAUTHORIZED
                        429 -> ChatError.RATE_LIMITED
                        else -> ChatError.HTTP(response.code)
                    }
                    TeaTalkLog.w(
                        "Net",
                        "chat.req.fail",
                        "host" to host,
                        "status" to response.code,
                        "ms" to (System.currentTimeMillis() - started),
                    )
                    emit(ChatEvent.Failed(error, body.take(200)))
                    return@use
                }
                val source = response.body?.source()
                if (source == null) {
                    emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "empty body"))
                    return@use
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val data = Sse.dataOf(line) ?: continue
                    // 有些服务端会在流中途发 `{"error":{...}}`：必须显式报错，不能当成「空回复」。
                    errorOf(data)?.let { message ->
                        TeaTalkLog.w(
                            "Net",
                            "chat.req.fail",
                            "host" to host,
                            "kind" to "STREAM_ERROR",
                            "ms" to (System.currentTimeMillis() - started),
                        )
                        emit(ChatEvent.Failed(ChatError.HTTP(200), message))
                        return@use
                    }
                    if (Sse.isDone(data)) {
                        emit(ChatEvent.Completed(truncated))
                        TeaTalkLog.i(
                            "Net",
                            "chat.req.ok",
                            "host" to host,
                            "ms" to (System.currentTimeMillis() - started),
                            "ttftMs" to firstTokenMs,
                            "chunks" to chunks,
                            "chars" to chars,
                            "truncated" to truncated,
                        )
                        return@use
                    }
                    if (finishReasonOf(data) == "length") truncated = true
                    val delta = deltaOf(data) ?: continue
                    if (delta.isEmpty()) continue
                    if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - started
                    chunks++
                    chars += delta.length
                    collected.append(delta)
                    emit(ChatEvent.Delta(delta))
                }
                // 没有 [DONE] 就自然结束：按完成处理
                TeaTalkLog.i(
                    "Net",
                    "chat.req.ok",
                    "host" to host,
                    "ms" to (System.currentTimeMillis() - started),
                    "ttftMs" to firstTokenMs,
                    "chunks" to chunks,
                    "chars" to chars,
                    "eof" to true,
                    "truncated" to truncated,
                )
                if (chunks == 0) {
                    emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "no content"))
                } else {
                    emit(ChatEvent.Completed(truncated))
                }
            }
        } catch (e: CancellationException) {
            TeaTalkLog.i("Net", "chat.req.cancel", "host" to host, "chars" to collected.length)
            throw e
        } catch (e: IOException) {
            // 网络异常与协程取消可能同时发生（OkHttp 在取消时抛 IOException）：
            // 先确认协程本身没被取消，否则会把「用户点了停止」误报成「网络不可用」。
            currentCoroutineContext().ensureActive()
            val kind = if (e is InterruptedIOException || e.message?.contains("timeout", true) == true) {
                ChatError.TIMEOUT
            } else {
                ChatError.NETWORK
            }
            TeaTalkLog.w(
                "Net",
                "chat.req.fail",
                "host" to host,
                "kind" to kind.label,
                "ms" to (System.currentTimeMillis() - started),
            )
            emit(ChatEvent.Failed(kind, e.message))
        } finally {
            runCatching { call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    /** `{"choices":[{"delta":{"content":"你"}}]}`；返回 null 表示该帧无正文。 */
    private fun deltaOf(data: String): String? = runCatching {
        val json = JSONObject(data)
        val choices = json.optJSONArray("choices") ?: return@runCatching null
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching null
        // 注意：org.json 的 optString 遇到 JSON null 会返回字符串 "null"，必须先判断 isNull
        if (delta.isNull("content")) return@runCatching null
        delta.optString("content", "").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** `[{"choices":[{"finish_reason":"length"}]}]`；只有流末帧才带。org.json 的 optString 会把 JSON null 读成 "null"，先判 isNull。 */
    private fun finishReasonOf(data: String): String? = runCatching {
        val choices = JSONObject(data).optJSONArray("choices") ?: return@runCatching null
        val choice = choices.optJSONObject(0) ?: return@runCatching null
        if (choice.isNull("finish_reason")) return@runCatching null
        choice.optString("finish_reason", "").takeIf { it.isNotEmpty() && it != "null" }
    }.getOrNull()

    /** 流中途的 `{"error":{"message":"...","type":"..."}}`；返回可直接展示的短文案。 */
    private fun errorOf(data: String): String? = runCatching {
        val json = JSONObject(data)
        if (json.isNull("error")) return@runCatching null
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message", "").takeIf { it.isNotEmpty() }
                ?: error.optString("type", "").takeIf { it.isNotEmpty() }
            is String -> error.takeIf { it.isNotEmpty() }
            else -> "unknown stream error"
        }?.take(200)
    }.getOrNull()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
