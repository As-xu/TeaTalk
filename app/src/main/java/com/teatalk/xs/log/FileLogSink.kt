package com.teatalk.xs.log

import com.teatalk.core.log.LogRecord
import com.teatalk.core.log.LogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 调试日志落盘：
 * - 位置：`filesDir/logs/teatalk-yyyyMMdd.log`
 * - 单文件上限 2MB，超限轮转为 `.1`（只保留一份轮转）
 * - 启动时清理 3 天前的文件
 * - 单线程串行写，不阻塞业务线程
 */
class FileLogSink(private val logDir: File) : LogSink {

    @Volatile
    var enabled: Boolean = true

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TeaTalkFileLog").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    init {
        logDir.mkdirs()
        cleanupOldFiles()
    }

    override fun write(record: LogRecord) {
        if (!enabled) return
        executor.execute {
            runCatching { append(record) }
        }
    }

    private fun append(record: LogRecord) {
        val file = File(logDir, "teatalk-${dayFormat.format(Date(record.timeMs))}.log")
        if (file.length() > MAX_BYTES) {
            File(logDir, file.name + ".1").runCatching {
                if (exists()) delete()
                file.renameTo(this)
            }
        }
        val line = "${timeFormat.format(Date(record.timeMs))} ${record.toLine()}\n"
        file.appendText(line, Charsets.UTF_8)
    }

    private fun cleanupOldFiles() {
        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * 24L * 60 * 60 * 1000
        logDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val RETAIN_DAYS = 3
    }
}
