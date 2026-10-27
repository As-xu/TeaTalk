package com.teatalk.xs.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.teatalk.core.log.LogRecord
import com.teatalk.core.log.RingBufferSink
import com.teatalk.xs.BuildConfig
import com.teatalk.xs.data.db.TeaTalkDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断信息：把「版本 + 设备 + DB 版本 + 最近日志」拼成一份可分享的 txt。
 * 只看内存环形缓冲（最多 2000 条），不读整个历史文件。
 *
 * 分享出去的 URI 只暴露 cacheDir/logs/ 这个子目录（见 res/xml/file_paths.xml），
 * 导出前再对每一行做一次「兜底脱敏」，防止日志点漏写导致 key 外泄。
 */
object Diagnostics {

    private val lineTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // 兜底：即便某个调用点忘记脱敏，也不让 sk-xxx / Bearer xxx 流出去
    private val secretPattern = Regex("""(sk-[A-Za-z0-9_\-]{8,}|Bearer\s+[A-Za-z0-9._\-]{8,})""")

    fun recentLines(ring: RingBufferSink, limit: Int = 500): List<String> =
        ring.snapshot().take(limit).map { sanitize(format(it)) }

    fun header(context: Context): String = buildString {
        appendLine("TeaTalk diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("package: ${context.packageName}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("dbVersion: ${TeaTalkDatabase.VERSION}")
        append("exportedAt: ").appendLine(lineTime.format(Date()))
    }

    /** 生成导出文件（cacheDir/logs/diagnostics-*.txt），顺手清理 1 小时前的旧导出。 */
    fun exportToFile(context: Context, ring: RingBufferSink): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { old ->
            if (old.name.startsWith("diagnostics-") && old.lastModified() < cutoff) {
                runCatching { old.delete() }
            }
        }
        val file = File(dir, "diagnostics-${System.currentTimeMillis()}.txt")
        file.writeText(
            header(context) + "\n" + recentLines(ring, limit = 2000).joinToString("\n"),
            Charsets.UTF_8,
        )
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TeaTalk diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun format(record: LogRecord): String =
        "${lineTime.format(Date(record.timeMs))} ${record.toLine()}"

    private fun sanitize(line: String): String = secretPattern.replace(line) { m ->
        if (m.value.startsWith("sk-")) "sk-***" else "Bearer ***"
    }
}
