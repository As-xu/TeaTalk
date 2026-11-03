package com.teatalk.xs.data

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * 变量宏（V1 正则/变量）。
 *
 * 支持（与 ST 对齐的常用子集）：
 * - 变量：`{{getvar::k}}`、`{{setvar::k::v}}`、`{{addvar::k::v}}`
 * - 名字：`{{user}}`、`{{char}}`
 * - 时间：`{{time}}`(HH:mm)、`{{date}}`(yyyy-MM-dd)、`{{datetime}}`、`{{weekday}}`
 * - 随机：`{{random::a::b::c}}` 从候选里随机取一个；两个都是整数时当作 [min,max) 随机整数
 * - 抽取：`{{pick::a::b}}` 同 random，但用「会话内稳定」的种子（同一会话多次解析结果一致，直至变量变化）
 *
 * 设计取舍：
 * - **未知宏原样保留**：卡里可能有别的工具的自定义宏（`<USER>`、`{{original}}` 之类），
 *   不认识就不要静默删掉，宁可让模型看到原文。
 * - **多轮展开（最多 [MAX_PASSES] 轮）**：`setvar` 写进变量后，同一段文本后续出现的 `getvar` 能读到新值；
 *   上限防止 `{{setvar::a::{{getvar::a}}}}` 这类自引用死循环。
 * - **写入可见**：[MacroContext.variables] 是可变 map，调用方解析完只需把「与解析前不同的 key」持久化。
 */
class MacroContext(
    /** 会话变量（就地读写，解析后由调用方 diff 出写入集合）。 */
    val variables: MutableMap<String, String>,
    val userName: String = "User",
    val characterName: String? = null,
    /** 测试可注入固定时间。 */
    val now: LocalDateTime = LocalDateTime.now(),
    /** 测试可注入固定随机数源。 */
    val random: Random = Random.Default,
)

object MacroEngine {
    const val MAX_PASSES = 5

    private val TOKEN = Regex("\\{\\{([^{}]+)}}")

    fun resolve(text: String, context: MacroContext): String {
        if (text.isEmpty() || !text.contains("{{")) return text
        var out = text
        var pass = 0
        while (pass < MAX_PASSES && TOKEN.containsMatchIn(out)) {
            val next = TOKEN.replace(out) { match -> expand(match.groupValues[1], context) }
            if (next == out) break
            out = next
            pass++
        }
        return out
    }

    /** 解析单个宏体（`{{ }}` 内部的东西）。未知宏返回原样（保留花括号）。 */
    private fun expand(body: String, context: MacroContext): String {
        val parts = body.split("::")
        val head = parts.firstOrNull()?.trim().orEmpty().lowercase(Locale.ROOT)
        return when (head) {
            "user" -> context.userName
            "char" -> context.characterName ?: ""
            "time" -> context.now.format(TIME)
            "date" -> context.now.format(DATE)
            "datetime" -> context.now.format(DATETIME)
            "weekday" -> context.now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "getvar" -> parts.getOrNull(1)?.trim()?.let { context.variables[it].orEmpty() } ?: ""
            "setvar" -> {
                val key = parts.getOrNull(1)?.trim().orEmpty()
                val value = parts.drop(2).joinToString("::")
                if (key.isNotEmpty()) context.variables[key] = value
                ""
            }
            "addvar" -> {
                val key = parts.getOrNull(1)?.trim().orEmpty()
                val raw = parts.drop(2).joinToString("::")
                if (key.isNotEmpty()) {
                    val current = context.variables[key]
                    val added = if (current != null && current.trim().toIntOrNull() != null && raw.trim().toIntOrNull() != null) {
                        (current.trim().toInt() + raw.trim().toInt()).toString()
                    } else {
                        (current.orEmpty()) + raw
                    }
                    context.variables[key] = added
                }
                ""
            }
            "random", "pick" -> {
                val candidates = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                pick(candidates, context)
            }
            else -> "{{$body}}"
        }
    }

    private fun pick(candidates: List<String>, context: MacroContext): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 2) {
            val from = candidates[0].toIntOrNull()
            val to = candidates[1].toIntOrNull()
            if (from != null && to != null && to > from) {
                return context.random.nextInt(from, to).toString()
            }
        }
        return candidates[context.random.nextInt(candidates.size)]
    }

    private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATETIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
