package com.teatalk.xs.data.tts

/**
 * 0.25.0：把模型回复整理成「适合朗读的句子」。
 *
 * 模型输出带 Markdown、动作描写（`（抬眼…）`）、代码块与表情；直接丢给 TTS 会把
 * `**`、`~~` 念出来，也会一口气念两三百字。这里做两件事：
 * 1. [clean] 去掉不该发音的符号，保留文字本身；
 * 2. [segments] 按句末标点 / 换行切成 ≤ [MAX_SEGMENT_CHARS] 的段，逐段合成。
 *
 * 纯函数、无 Android 依赖，便于单测。
 */
object TtsText {

    /** 单段上限：超过 200 字时多数引擎会停顿异常或直接被服务端拒。 */
    const val MAX_SEGMENT_CHARS = 200

    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val markdownLink = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    /** 带协议的裸链接：逐字符念 `h-t-t-p-s` 没有意义。 */
    private val bareUrl = Regex("https?://\\S+")
    /** 成对强调标记（`**粗体**`、`*斜体*`、`~~删除线~~`）。 */
    private val emphasisPair = Regex("(\\*\\*|~~|\\*|__)(.+?)\\1")
    /** 只在行首删列表 / 引用 / 标题符号，避免把 `snake_case`、`C#` 里的符号吃掉。 */
    private val linePrefix = Regex("^\\s*(?:[-*+>]+|#{1,6})\\s*", RegexOption.MULTILINE)
    private val quotedNames = Regex("[「」『』“”\"]")
    private val multiSpace = Regex("[ \\t\\u00A0]{2,}")

    /** 去掉 Markdown 标记、代码块、多余空白；动作描写保留括号里的文字（它也是叙述的一部分）。 */
    fun clean(raw: String): String {
        var text = raw.replace(codeBlock, " ")
        text = inlineCode.replace(text, "$1")
        text = markdownLink.replace(text, "$1")
        text = bareUrl.replace(text, " ")
        text = emphasisPair.replace(text, "$2")
        text = linePrefix.replace(text, "")
        text = quotedNames.replace(text, "")
        // 表格分隔行（`| --- | --- |`）整行去掉，别念出一串横线。
        text = text.replace(Regex("^[\\s|:-]+$", RegexOption.MULTILINE), "")
        text = text.replace("|", " ")
        text = multiSpace.replace(text, " ")
        return text.trim()
    }

    /**
     * 切成可逐段合成的句子。
     *
     * 先按换行与句末标点断句，再把过短的句子合并到 [max] 以内；单句超长时硬切，
     * 保证每段都不超过上限。
     */
    fun segments(raw: String, max: Int = MAX_SEGMENT_CHARS): List<String> {
        val text = clean(raw)
        if (text.isEmpty()) return emptyList()
        val limit = max.coerceAtLeast(20)

        val pieces = ArrayList<String>()
        val buffer = StringBuilder()
        fun flush() {
            val piece = buffer.toString().trim()
            buffer.setLength(0)
            if (piece.isNotEmpty()) pieces += piece
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            buffer.append(ch)
            val sentenceEnd = ch in "。！？!?；;…" || ch == '\n'
            if (sentenceEnd) flush()
            i++
        }
        flush()

        val out = ArrayList<String>(pieces.size)
        val merged = StringBuilder()
        for (piece in pieces) {
            if (piece.length > limit) {
                if (merged.isNotEmpty()) {
                    out += merged.toString()
                    merged.setLength(0)
                }
                out += hardSplit(piece, limit)
                continue
            }
            if (merged.isEmpty()) {
                merged.append(piece)
            } else if (merged.length + 1 + piece.length <= limit) {
                merged.append(' ').append(piece)
            } else {
                out += merged.toString()
                merged.setLength(0)
                merged.append(piece)
            }
        }
        if (merged.isNotEmpty()) out += merged.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun hardSplit(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + limit).coerceAtMost(text.length)
            // 不要把 emoji（代理对）劈成两半：宁可少一个字，也不能产生半个字符。
            if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate()) {
                end -= 1
            }
            out += text.substring(start, end).trim()
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }
}
