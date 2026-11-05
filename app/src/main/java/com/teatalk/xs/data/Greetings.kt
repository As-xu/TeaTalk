package com.teatalk.xs.data

/**
 * 0.26.1：开场白候选。
 *
 * 真实卡里常见「`first_mes` 只写模板占位、真正开场白全在 `alternate_greetings`」的写法
 * （例如 `<音乐>` 与 `<开场白>` 两行占位），所以开始聊天前要把候选列出来让用户挑，
 * 而不是直接播占位符。
 */
object Greetings {

    private val WHITESPACE = Regex("\\s+")
    private val ANGLE = Regex("<[^>]*>")
    private val BRACES = Regex("\\{\\{[^}]*\\}\\}")
    private val BRACKETS = Regex("\\[[^\\]]*\\]")

    /** 候选列表：`first_mes` 在前（非空且不是占位模板时），随后是去空、去重后的 `alternate_greetings`。 */
    fun options(firstMes: String, alternateGreetings: List<String>): List<String> {
        val alternates = alternates(alternateGreetings)
        val head = firstMes.trim()
        val out = LinkedHashSet<String>()
        if (head.isNotEmpty() && (!isShell(head) || alternates.isEmpty())) out += head
        out += alternates
        return out.toList()
    }

    /** 卡片真正可读的开场白：优先 `first_mes`（非占位时），否则取第一条备用开场白。 */
    fun defaultOpening(firstMes: String, alternateGreetings: List<String>): String? {
        val head = firstMes.trim()
        if (head.isNotEmpty() && !isShell(head)) return head
        alternates(alternateGreetings).firstOrNull()?.let { return it }
        return head.takeIf { it.isNotEmpty() }
    }

    private fun alternates(list: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        list.forEach { text ->
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) out += trimmed
        }
        return out.toList()
    }

    /**
     * 是否是「模板占位」开场白。
     *
     * 判据：去掉 `<...>`、`{{...}}`、`[...]` 之后没有字母或数字（例如 `<音乐>` 与 `<开场白>` 两行），
     * 或去壳后短于 3 个字符。真实开场白哪怕很短也会带汉字/标点。
     */
    fun isShell(text: String): Boolean {
        val stripped = text
            .replace(ANGLE, " ")
            .replace(BRACES, " ")
            .replace(BRACKETS, " ")
            .replace(WHITESPACE, " ")
            .trim()
        if (stripped.isEmpty()) return true
        if (stripped.none { it.isLetterOrDigit() }) return true
        return stripped.length < 3
    }

    /**
     * 选项列表里展示的摘要。
     *
     * 开场白常以 `#` 标题、`===` 分隔线或 YAML 头开局，直接截前 80 字只会看到符号，
     * 所以先挑第一条「像正文」的行，找不到就退回第一条非空行，再压平空白并截断。
     */
    fun preview(text: String, limit: Int = 72): String {
        val body = text.split('\n')
            .firstOrNull { line -> looksLikeProse(line) }
            ?.trim()
            ?: text.split('\n').firstOrNull { it.isNotBlank() }?.trim()
            ?: text
        val flat = body.replace(WHITESPACE, " ").trim()
        return truncate(flat, limit)
    }

    /** 按码点截断，避免把 emoji 的代理对切成孤立的一半。 */
    private fun truncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val points = text.codePointCount(0, text.length)
        val end = text.offsetByCodePoints(0, points.coerceAtMost(limit))
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun looksLikeProse(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 4) return false
        if (trimmed.startsWith("#") || trimmed.startsWith("```") || trimmed.startsWith("---")) return false
        if (trimmed.none { it.isLetterOrDigit() }) return false
        val decoration = trimmed.count { it == '=' || it == '-' || it == '~' || it == '*' || it == '#' }
        return decoration * 2 < trimmed.length
    }

    /** 是否需要弹「选择开场白」：可读候选有两条以上（占位模板已从候选里剔除）。 */
    fun needsChoice(firstMes: String, alternateGreetings: List<String>): Boolean =
        options(firstMes, alternateGreetings).size > 1
}
