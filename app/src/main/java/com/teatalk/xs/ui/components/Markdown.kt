package com.teatalk.xs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.ui.theme.LocalCodeSurfaces
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 轻量 Markdown：覆盖角色扮演里真正会出现的写法（标题/粗体/斜体/删除线/行内代码/列表/引用/代码块/分隔线）。
 *
 * 为什么不用第三方库：
 * - 只有纯文本渲染需求，不接链接跳转、不接图片；
 * - 流式输出每 60ms 变一次，需要容忍未闭合的标记（`**` 还没收尾时按字面显示）；
 * - 手写解析是纯 Kotlin，可在 JVM 单测里覆盖，不必依赖 Android 运行时。
 *
 * 表格、HTML、脚注、嵌套列表暂不支持（按普通文本渲染）。
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullets(val items: List<String>) : Block
        data class Ordered(val items: List<String>, val start: Int) : Block
        data class Quote(val text: String) : Block
        data class Code(val code: String, val language: String?) : Block
        data object Divider : Block
    }

    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ordered = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val fence = Regex("^\\s*```\\s*([\\w+-]*)\\s*$")
    private val divider = Regex("^\\s*([-*_])\\s*\\1\\s*\\1[-\\s]*$")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            fence.matchEntire(line)?.let { m ->
                val language = m.groupValues[1].ifBlank { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && fence.matchEntire(lines[i]) == null) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // 跳过收尾的 ```
                blocks += Block.Code(body.toString(), language)
                return@let
            } ?: run {
                heading.matchEntire(trimmed)?.let { m ->
                    blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                    i++
                    return@run
                }
                if (divider.matches(trimmed)) {
                    blocks += Block.Divider
                    i++
                    return@run
                }
                if (trimmed.startsWith(">")) {
                    val body = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        body += lines[i].trim().removePrefix(">").trimStart()
                        i++
                    }
                    blocks += Block.Quote(body.joinToString("\n"))
                    return@run
                }
                if (bullet.matches(line)) {
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val m = bullet.matchEntire(lines[i]) ?: break
                        items += m.groupValues[1].trim()
                        i++
                    }
                    blocks += Block.Bullets(items)
                    return@run
                }
                val firstOrdered = ordered.matchEntire(line)
                if (firstOrdered != null) {
                    val start = firstOrdered.groupValues[1].toIntOrNull() ?: 1
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val m = ordered.matchEntire(lines[i]) ?: break
                        items += m.groupValues[2].trim()
                        i++
                    }
                    blocks += Block.Ordered(items, start)
                    return@run
                }
                // 普通段落：连续非空、且不是其它块起始的行合并在一起
                val body = mutableListOf<String>()
                while (i < lines.size) {
                    val candidate = lines[i]
                    if (candidate.trim().isEmpty()) break
                    if (body.isNotEmpty() && startsBlock(candidate)) break
                    body += candidate.trim()
                    i++
                }
                if (body.isEmpty()) {
                    body += trimmed
                    i++
                }
                blocks += Block.Paragraph(body.joinToString("\n"))
            }
        }
        return blocks
    }

    private fun startsBlock(line: String): Boolean =
        fence.containsMatchIn(line) ||
            heading.matches(line.trim()) ||
            divider.matches(line.trim()) ||
            line.trim().startsWith(">") ||
            bullet.matches(line) ||
            ordered.matches(line)

    enum class Style { BOLD, ITALIC, STRIKE, CODE, LINK }

    data class Span(val start: Int, val end: Int, val style: Style, val target: String? = null)

    /** 行内标记解析：返回纯文本 + 各段样式，未闭合的标记按字面文本保留（流式安全）。 */
    fun inline(text: String): Pair<String, List<Span>> {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(text, i + 1, end)
                    spans += Span(start, out.length, Style.CODE)
                    i = end + 1
                    continue
                }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(text, i + 2, end)
                    spans += Span(start, out.length, Style.BOLD)
                    i = end + 2
                    continue
                }
            }
            if (c == '~' && i + 1 < text.length && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(text, i + 2, end)
                    spans += Span(start, out.length, Style.STRIKE)
                    i = end + 2
                    continue
                }
            }
            if (c == '*' && i + 1 < text.length) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(text, i + 1, end)
                    spans += Span(start, out.length, Style.ITALIC)
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                if (close > i + 1) {
                    val end = text.indexOf(')', close + 1)
                    if (end > close + 1) {
                        val label = text.substring(i + 1, close)
                        val target = text.substring(close + 2, end)
                        val start = out.length
                        out.append(label)
                        spans += Span(start, out.length, Style.LINK, target)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString() to merge(spans)
    }

    /** 同名样式区间相邻时合并，减少 AnnotatedString 的 span 数量。 */
    private fun merge(spans: List<Span>): List<Span> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedWith(compareBy({ it.start }, { it.end }))
        val result = mutableListOf<Span>()
        for (span in sorted) {
            val last = result.lastOrNull()
            if (last != null && last.style == span.style && last.target == span.target && last.end == span.start) {
                result[result.lastIndex] = last.copy(end = span.end)
            } else {
                result += span
            }
        }
        return result
    }
}

/** 把一行/一段 Markdown 行内标记渲染成带样式的 AnnotatedString。 */
fun annotatedInline(
    text: String,
    color: Color,
    codeBackground: Color,
    linkColor: Color,
    fontWeight: FontWeight = FontWeight.Normal,
): AnnotatedString {
    val (plain, spans) = Markdown.inline(text)
    if (spans.isEmpty()) return AnnotatedString(plain)
    return buildAnnotatedString {
        append(plain)
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = color)
        for (span in spans) {
            when (span.style) {
                Markdown.Style.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                Markdown.Style.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                Markdown.Style.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                Markdown.Style.CODE -> addStyle(codeStyle, span.start, span.end)
                Markdown.Style.LINK -> addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    span.start,
                    span.end,
                )
            }
        }
        if (fontWeight != FontWeight.Normal) addStyle(SpanStyle(fontWeight = fontWeight), 0, length)
    }
}

/**
 * 渲染 Markdown 文本。`textSize` 是正文大小，标题按比例放大。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    fontSize: TextUnit = 14.5.sp,
    lineHeight: TextUnit = 22.sp,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // 行内代码与代码块用专用底色：surfaceContainerHighest 与气泡底色几乎同色（审阅实测 ~1.05:1）
    val code = LocalCodeSurfaces.current
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is Markdown.Block.Heading -> {
                    val size = when (block.level) {
                        1 -> (fontSize.value + 5).sp
                        2 -> (fontSize.value + 3).sp
                        3 -> (fontSize.value + 1).sp
                        else -> fontSize
                    }
                    Text(
                        text = annotatedInline(block.text, color, code.inline, cs.primary, FontWeight.SemiBold),
                        fontSize = size,
                        lineHeight = (size.value + 6).sp,
                        color = color,
                    )
                }
                is Markdown.Block.Paragraph -> Text(
                    text = annotatedInline(block.text, color, code.inline, cs.primary),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                )
                is Markdown.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("•", fontSize = fontSize, color = color, modifier = Modifier.width(14.dp))
                            Text(
                                text = annotatedInline(item, color, code.inline, cs.primary),
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is Markdown.Block.Ordered -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${block.start + index}.",
                                fontSize = fontSize,
                                color = color,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = annotatedInline(item, color, code.inline, cs.primary),
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(cs.outline, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = annotatedInline(block.text, cs.onSurfaceVariant, code.inline, cs.primary),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                is Markdown.Block.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(code.block, RoundedCornerShape(8.dp))
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    val language = block.language
                    if (!language.isNullOrBlank()) {
                        Text(
                            language,
                            fontSize = TypeScale.s10,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        block.code,
                        fontSize = (fontSize.value - 1).sp,
                        lineHeight = (lineHeight.value - 2).sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                    )
                }
                Markdown.Block.Divider -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .height(1.dp)
                        .background(cs.outlineVariant),
                )
            }
        }
    }
}
