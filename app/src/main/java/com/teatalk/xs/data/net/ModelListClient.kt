package com.teatalk.xs.data.net

import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 只负责 OpenAI 兼容的 `GET {baseUrl}/models`（拉模型列表 / 测连接）。
 * 不记录 key，只记录 host/path/状态码/耗时。
 */
class ModelListClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/models"
            val host = Redact.url(url)
            val started = System.currentTimeMillis()
            TeaTalkLog.i("Net", "req.start", "host" to host, "api" to "models")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val ms = System.currentTimeMillis() - started
                    if (!response.isSuccessful) {
                        TeaTalkLog.w(
                            "Net",
                            "req.fail",
                            "host" to host,
                            "status" to response.code,
                            "ms" to ms,
                        )
                        return@withContext Result.failure(
                            HttpStatusException(response.code, response.message),
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    TeaTalkLog.i(
                        "Net",
                        "req.ok",
                        "host" to host,
                        "status" to response.code,
                        "ms" to ms,
                        "bytes" to body.length,
                    )
                    Result.success(parseModels(body))
                }
            } catch (e: IOException) {
                TeaTalkLog.w(
                    "Net",
                    "req.fail",
                    "host" to host,
                    "ms" to (System.currentTimeMillis() - started),
                    "kind" to "io",
                )
                Result.failure(e)
            } catch (e: Exception) {
                TeaTalkLog.e("Net", "req.fail", e, "host" to host)
                Result.failure(e)
            }
        }

    /** `{"data":[{"id":"gpt-4o"},...]}`；也兼容裸数组。 */
    private fun parseModels(body: String): List<String> {
        val array = runCatching {
            val json = JSONObject(body)
            json.optJSONArray("data")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val id = array.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }
}

class HttpStatusException(val status: Int, message: String) : Exception("HTTP $status $message")
