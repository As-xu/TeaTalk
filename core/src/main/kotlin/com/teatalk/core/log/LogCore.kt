package com.teatalk.core.log

/** 日志级别（数值越大越严重）。 */
enum class LogLevel(val severity: Int) {
    V(0),
    D(1),
    I(2),
    W(3),
    E(4),
    ;

    companion object {
        /** 从字符串/字段名解析，兼容 "W"、"warn"、"WARNING"。 */
        fun parse(raw: String): LogLevel? = when (raw.trim().lowercase()) {
            "v", "verbose" -> V
            "d", "debug" -> D
            "i", "info" -> I
            "w", "warn", "warning" -> W
            "e", "error" -> E
            else -> null
        }
    }
}

/**
 * 一条结构化日志。
 *
 * 约定：`tag` 只放域名（Net/Card/Db/Chat/Prompt/Ui），`event` 用点号事件名（import.ok），
 * 细节一律放 [fields]，不要拼长字符串。
 */
data class LogRecord(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val event: String,
    val fields: Map<String, String> = emptyMap(),
    /** 已在写入前由调用方脱敏（见 [Redact]），sink 不应再读原始 message。 */
    val throwableSummary: String? = null,
) {
    /** 单行文本形式，供 logcat / 文件 sink 使用。 */
    fun toLine(): String {
        val sb = StringBuilder()
        sb.append(level.name).append('/').append(tag).append(' ').append(event)
        fields.forEach { (k, v) -> sb.append(' ').append(k).append('=').append(v) }
        throwableSummary?.let { sb.append(" error=\"").append(it).append('"') }
        return sb.toString()
    }
}

/** 日志输出端。实现必须线程安全（可能在任意线程被调用）。 */
fun interface LogSink {
    fun write(record: LogRecord)
}

/** 丢弃一切。 */
object NoopSink : LogSink {
    override fun write(record: LogRecord) = Unit
}

/**
 * 内存环形缓冲：默认 2000 条，供「查看最近日志」使用。
 * 写入 O(1)、有界内存；读取时返回快照（新→旧）。
 */
class RingBufferSink(private val capacity: Int = 2000) : LogSink {

    private val buffer = ArrayDeque<LogRecord>(capacity.coerceAtLeast(1))
    private val lock = Any()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    override fun write(record: LogRecord) {
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(record)
        }
    }

    /** 快照，按时间新→旧。 */
    fun snapshot(): List<LogRecord> = synchronized(lock) { buffer.toList().asReversed() }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) { buffer.clear() }
}
