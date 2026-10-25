package com.teatalk.core.log

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志门面：业务代码只依赖这里，不直接碰 android.util.Log。
 *
 * 用法：
 * ```
 * TeaTalkLog.install(AndroidLogSink(), RingBufferSink())
 * TeaTalkLog.i("Net", "req.start", "host" to "api.openai.com", "stream" to true)
 * TeaTalkLog.e("Net", "req.error", Redact.throwable(t), "status" to 401)
 * ```
 *
 * - 级别阈值：release 默认 W（只留警告/错误），debug 默认 D
 * - 端：logcat + 内存环形 +（debug）文件；由 App 层在启动时 [install]
 * - 写入顺序：按注册顺序同步调用，sink 自己负责异步/落盘
 */
object TeaTalkLog {

    @Volatile
    var minLevel: LogLevel = LogLevel.D

    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val dropped = AtomicLong(0)
    private val clock: () -> Long = System::currentTimeMillis

    fun install(vararg newSinks: LogSink) {
        sinks.clear()
        newSinks.forEach(sinks::add)
    }

    fun addSink(sink: LogSink) = sinks.addIfAbsent(sink)

    fun removeSink(sink: LogSink) = sinks.remove(sink)

    fun sinks(): List<LogSink> = sinks.toList()

    fun isEnabled(level: LogLevel): Boolean = level.severity >= minLevel.severity

    fun v(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.V, tag, event, null, fields)

    fun d(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.D, tag, event, null, fields)

    fun i(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.I, tag, event, null, fields)

    fun w(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.W, tag, event, null, fields)

    fun e(tag: String, event: String, throwable: Throwable?, vararg fields: Pair<String, Any?>) =
        log(LogLevel.E, tag, event, throwable, fields)

    /** 错误但不需要堆栈（例如 HTTP 4xx）。 */
    fun e(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        log(LogLevel.E, tag, event, null, fields)

    /** 被丢弃条数（阈值以下不会走到这里，仅用于「sink 异常被吞」计数）。 */
    fun droppedCount(): Long = dropped.get()

    internal fun log(
        level: LogLevel,
        tag: String,
        event: String,
        throwable: Throwable?,
        fields: Array<out Pair<String, Any?>>,
    ) {
        if (!isEnabled(level)) return
        val safeFields = LinkedHashMap<String, String>(fields.size)
        fields.forEach { (k, value) -> safeFields[k] = value?.toString() ?: "null" }
        val record = LogRecord(
            timeMs = clock(),
            level = level,
            tag = tag,
            event = event,
            fields = safeFields,
            throwableSummary = throwable?.let { Redact.throwable(it) },
        )
        sinks.forEach { sink ->
            // 单个 sink 出错不能影响业务与其它 sink
            runCatching { sink.write(record) }.onFailure { dropped.incrementAndGet() }
        }
    }
}
