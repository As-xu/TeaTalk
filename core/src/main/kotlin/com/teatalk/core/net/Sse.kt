package com.teatalk.core.net

/**
 * 极简 SSE 行解析（OpenAI 兼容流式）。
 *
 * 只看两类行：
 * - `data: <payload>` → 返回 payload（`[DONE]` 原样返回）
 * - 空行 / `:comment` / 其它字段（`event:` / `id:` / `retry:`）→ 忽略
 *
 * 这样把「传输细节」留在 `:app` 的 OkHttp 层，`:core` 只做纯函数，便于单测。
 */
object Sse {

    const val DONE = "[DONE]"

    fun dataOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        return trimmed.substring(5).trim()
    }

    fun isDone(payload: String): Boolean = payload == DONE
}
