package com.teatalk.xs.log

import android.content.Context
import android.os.Build
import com.teatalk.core.log.RingBufferSink
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录（C 阶段）：
 *
 * 进程级未捕获异常的兜底 —— 把堆栈 + 设备信息 + 最近日志落到
 * `filesDir/crashes/crash-<时间>.txt`，然后在「我的 → 关于」里可查看/分享。
 *
 * 不替换系统行为：记录完仍然交给默认处理器（系统照常弹崩溃/写 tombstone），
 * 这里只额外留一份用户可导出的证据。只在主进程安装一次。
 */
object CrashLogStore {

    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 10
    private const val MAX_RING_LINES = 200

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 崩溃文件，新→旧。 */
    fun files(context: Context): List<File> {
        val dir = File(context.filesDir, DIR_NAME)
        val list = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }.orEmpty()
        return list.sortedByDescending { it.name }
    }

    fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val ring = runCatching {
            TeaTalkLog.sinks()
                .filterIsInstance<RingBufferSink>()
                .firstOrNull()
                ?.snapshot()
                .orEmpty()
        }
            .getOrElse { emptyList() }
            .take(MAX_RING_LINES)
            .joinToString("\n") { it.toLine() }
        val body = buildString {
            appendLine("time=$stamp")
            appendLine("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread=${thread.name}")
            appendLine("--- stack ---")
            appendLine(stack)
            appendLine("--- recent logs (new→old) ---")
            appendLine(ring)
        }
        file.writeText(body)
        prune(dir)
        PerfLog.event("crash.saved", "file" to file.name, "size" to file.length())
    }

    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
    }

    private fun prune(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}
