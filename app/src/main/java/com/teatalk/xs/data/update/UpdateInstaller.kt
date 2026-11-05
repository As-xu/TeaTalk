package com.teatalk.xs.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 0.26.0：下载更新包并拉起系统安装器。
 *
 * 只信任 GitHub 返回的资产地址；下载完先校验 sha256 再交给安装器，
 * 校验失败就删掉文件，避免把半截包或投毒包装进用户手机。
 */
class UpdateInstaller(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    class UpdateException(message: String) : Exception(message)

    private val dir: File
        get() = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun download(
        release: UpdateClient.Release,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        // 只保留本次的包，旧包占空间也没用。
        dir.listFiles()?.forEach { it.delete() }
        // 远端可控的文件名不能直接拼路径：去掉目录部分，并确认落点仍在 updates/ 内。
        val safeName = release.apkName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "teatalk-${release.version}.apk" }
        val target = File(dir, safeName)
        require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            "illegal apk name: ${release.apkName}"
        }
        val part = File(dir, "${target.name}.part")
        try {
            var total = release.apkSize
            var expectedSize = 0L
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("HTTP ${response.code}")
                val body = response.body ?: throw UpdateException("empty body")
                if (total <= 0L) total = body.contentLength()
                expectedSize = total
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            // 下载中点「关闭」会取消协程：这里主动让出控制权并收尾，
                            // 避免半截包留在缓存里。
                            if (!currentCoroutineContext().isActive) {
                                throw kotlinx.coroutines.CancellationException("cancelled")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            val expected = release.sha256 ?: fetchSidecarSha(release.apkUrl)
            val actual = sha256(part)
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                part.delete()
                TeaTalkLog.w("Update", "update.verify_fail", "expected" to expected, "actual" to actual)
                return@withContext Result.failure(UpdateException("sha256 mismatch"))
            }
            if (expected == null) {
                // 没有摘要时至少校验长度，截断的包交给安装器只会得到「解析包错误」。
                if (expectedSize > 0 && part.length() != expectedSize) {
                    part.delete()
                    TeaTalkLog.w("Update", "update.size_fail", "expected" to expectedSize, "actual" to part.length())
                    return@withContext Result.failure(UpdateException("size mismatch"))
                }
                TeaTalkLog.w("Update", "update.no_digest", "file" to release.apkName)
            }
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext Result.failure(UpdateException("rename failed"))
            }
            TeaTalkLog.i("Update", "update.downloaded", "version" to release.version, "bytes" to target.length())
            Result.success(target)
        } catch (e: Exception) {
            // 取消/失败都别把半截包留在缓存里（用户取消后重新下载会先清目录，
            // 但失败重试前删掉更干净）。
            part.delete()
            if (e is CancellationException) {
                TeaTalkLog.i("Update", "update.download_cancelled")
            } else {
                TeaTalkLog.w("Update", "update.download_fail", "error" to e.message)
            }
            Result.failure(e)
        }
    }

    /** 没带 digest 的仓库：再取一次同名 `.sha256` 文件，格式是 `<hash> *path`。 */
    private fun fetchSidecarSha(apkUrl: String): String? = runCatching {
        client.newCall(Request.Builder().url("$apkUrl.sha256").build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.trim()?.substringBefore(' ')?.takeIf { it.length == 64 }
        }
    }.getOrNull()

    /**
     * 边读边算摘要；每 4MB 让出一次，取消下载时不会在哈希阶段卡住几十秒。
     */
    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var sinceYield = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                sinceYield += read
                if (sinceYield >= 4 * 1024 * 1024) {
                    sinceYield = 0
                    yield()
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun install(file: File): Result<Unit> = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        TeaTalkLog.i("Update", "update.install_launch", "file" to file.name)
    }.onFailure {
        TeaTalkLog.w("Update", "update.install_fail", "error" to it.message)
    }
}
