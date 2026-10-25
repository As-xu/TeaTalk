package com.teatalk.core.log

import java.security.MessageDigest

/**
 * 脱敏工具：日志里**永不**出现密钥、Authorization、角色卡正文、聊天原文。
 *
 * 需要定位时只记「长度 + 前 8 位 SHA-256」，例如 `descHash=ab12cd34,len=12043`。
 */
object Redact {

    /** 文本指纹：`hash=ab12cd34,len=12043`；不逆转、不可还原原文。 */
    fun fingerprint(text: String): String {
        val hash = sha256(text).take(8)
        return "hash=$hash,len=${text.length}"
    }

    /** 只保留长度，用于纯计数场景。 */
    fun length(text: String): Int = text.length

    /** URL 只留 `scheme://host/path`，丢弃 query 与 fragment。 */
    fun url(raw: String): String {
        return try {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme ?: return "<no-scheme>"
            val host = uri.host ?: return "$scheme://<no-host>"
            val path = uri.path.orEmpty()
            if (path.isEmpty()) "$scheme://$host" else "$scheme://$host$path"
        } catch (e: Exception) {
            // 连 URI 都解析不了：绝不回显原文（可能带 key 或 query）
            "<unparsable-url,len=${raw.length}>"
        }
    }

    /**
     * 异常摘要：`类型: 消息`（消息先过 URL 脱敏）+ 栈首 5 行。
     * 消息里可能带完整 URL（含 key），因此按 URL 规则截断。
     */
    fun throwable(t: Throwable, maxStackLines: Int = 5): String {
        val message = t.message.orEmpty()
        val safeMessage = if (message.contains("://")) url(message) else message.take(300)
        val frames = t.stackTrace.take(maxStackLines)
            .joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return buildString {
            append(t.javaClass.simpleName)
            if (safeMessage.isNotEmpty()) append(": ").append(safeMessage)
            if (frames.isNotEmpty()) append(" @ ").append(frames)
        }
    }

    /** 密钥类字符串的日志形态：永不回显，只给后 4 位提示是否配错。 */
    fun apiKey(key: String): String =
        if (key.isBlank()) "<empty>" else "***${key.takeLast(4)}"

    /** 请求头：只保留白名单，其余打码。 */
    fun headers(headers: Map<String, String>): String =
        headers.keys.joinToString(",") {
            if (it.equals("Authorization", ignoreCase = true) || it.contains("key", ignoreCase = true)) "$it=***" else it
        }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
