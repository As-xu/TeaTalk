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
 */
object Diagnostics {

    private val lineTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun recentLines(ring: RingBufferSink, limit: Int = 500): List<String> =
        ring.snapshot().take(limit).map { format(it) }

    fun header(context: Context): String = buildString {
        appendLine("TeaTalk diagnostics")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("package: ${context.packageName}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("dbVersion: ${TeaTalkDatabase.VERSION}")
        append("exportedAt: ").appendLine(lineTime.format(Date()))
    }

    /** 生成导出文件（cacheDir/diagnostics-*.txt）。 */
    fun exportToFile(context: Context, ring: RingBufferSink): File {
        val file = File(context.cacheDir, "teatalk-diagnostics-${System.currentTimeMillis()}.txt")
        file.writeText(header(context) + "\n" + recentLines(ring, limit = 2000).joinToString("\n"), Charsets.UTF_8)
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
}
