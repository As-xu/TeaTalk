package com.teatalk.xs.data.update

import android.content.Context
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 0.26.0：从 GitHub Releases 读最新版本。
 *
 * 仓库可能还没有 release（API 404），也可能在私有网络里根本连不上：
 * 两种情况都只是「没有更新」，不弹错误、不影响使用。
 */
class UpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val apiBase: String = DEFAULT_API_BASE,
) {

    data class Release(
        val version: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sha256: String?,
        val publishedAt: String?,
        val notesUrl: String?,
    )

    sealed interface Result {
        data class Found(val release: Release) : Result

        /** 远端没有 release，或没有可用的 apk 资产。 */
        data object None : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun latest(feedUrl: String? = null): Result = withContext(Dispatchers.IO) {
        val url = feedUrl?.takeIf { it.isNotBlank() } ?: "$apiBase/repos/$OWNER/$REPO/releases/latest"
        try {
            // 非法 URL（用户/调试传入）只算一次失败的检查，不能让异常穿到 UI 协程。
            val parsed = url.toHttpUrlOrNull()
                ?: return@withContext Result.Failed("invalid feed url")
            val request = Request.Builder()
                .url(parsed)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    TeaTalkLog.i("Update", "update.none", "status" to 404)
                    return@withContext Result.None
                }
                if (!response.isSuccessful) {
                    TeaTalkLog.w("Update", "update.fail", "status" to response.code)
                    return@withContext Result.Failed("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parse(body)?.let { Result.Found(it) } ?: Result.None
            }
        } catch (e: Exception) {
            TeaTalkLog.w("Update", "update.fail", "error" to e.message)
            Result.Failed(e.message ?: "network")
        }
    }

    internal fun parse(body: String): Release? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isBlank()) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var digest: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apk = asset
                // GitHub 新版 API 会给资产算 sha256 digest；老仓库没有就靠同名 .sha256 文件。
                digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")
            }
        }
        val asset = apk ?: return null
        return Release(
            version = tag,
            apkUrl = asset.optString("browser_download_url"),
            apkName = asset.optString("name"),
            apkSize = asset.optLong("size"),
            sha256 = digest?.lowercase(),
            publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
            notesUrl = json.optString("html_url").takeIf { it.isNotBlank() },
        )
    }

    /** 版本号比较：只认数字段，`0.26.0` > `0.25.9`。 */
    fun isNewer(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    companion object {
        const val OWNER = "As-xu"
        const val REPO = "TeaTalk"
        const val DEFAULT_API_BASE = "https://api.github.com"
    }
}
