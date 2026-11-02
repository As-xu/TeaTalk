package com.teatalk.xs.data

/**
 * 群聊回复的多气泡切分。
 *
 * 模型经常在一条回复里让多个角色轮流说话，行文形如：
 *
 * ```
 * （抬头看了一眼手机）
 * 梁诗月：晚课都结束快一个小时了。
 * 白沐桐：我在楼下等你。
 * ```
 *
 * 如果整段塞进一个气泡，气泡头像是「白沐桐」、正文却以「梁诗月：」开头，
 * 既重复姓名又不像群聊。这里按「成员名 + 冒号」把正文切成多段，
 * 每段带上自己的说话者，UI 再把每段落成独立气泡。
 *
 * 规则：
 * - 只有「行首」是已知成员名 + `:` / `：` 才算换人，正文中间出现名字不拆；
 * - 没有名字的行归当前说话者（开头没名字则归本轮轮转到的 [fallbackSpeaker]）；
 * - 相邻同说话者合并，空段落丢弃；
 * - 整个过程是纯函数，方便单测与 golden 对拍。
 */
object GroupReplySplitter {

    /** 一段回复：说话者（null 表示未知）+ 去掉名字前缀后的正文。 */
    data class Segment(val speakerName: String?, val text: String)

    fun split(
        reply: String,
        memberNames: List<String>,
        fallbackSpeaker: String?,
    ): List<Segment> {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return emptyList()
        val names = (memberNames + listOfNotNull(fallbackSpeaker))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (names.isEmpty()) return listOf(Segment(fallbackSpeaker, trimmed))

        // 长名字优先，避免「小茶」抢在「小茶·测试」前面匹配。
        val patterns = names
            .sortedByDescending { it.length }
            .map { it to Regex("^\\s*" + Regex.escape(it) + "\\s*[:：]\\s*") }

        val out = mutableListOf<Segment>()
        val buffer = StringBuilder()
        var currentSpeaker: String? = null

        fun flush() {
            val text = buffer.toString().trim()
            buffer.setLength(0)
            if (text.isNotEmpty()) out += Segment(currentSpeaker, text)
        }

        for (line in trimmed.lines()) {
            val hit = patterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(line) }
            if (hit != null) {
                val (name, pattern) = hit
                val rest = pattern.replaceFirst(line, "").trimEnd()
                if (currentSpeaker != name) {
                    flush()
                    currentSpeaker = name
                }
                if (rest.isNotEmpty()) {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(rest)
                }
            } else {
                if (currentSpeaker == null) currentSpeaker = fallbackSpeaker
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
            }
        }
        flush()

        // 合并相邻同说话者（例如名字行之后又跟了同名行）。
        val merged = mutableListOf<Segment>()
        for (segment in out) {
            val last = merged.lastOrNull()
            if (last != null && last.speakerName == segment.speakerName) {
                merged[merged.lastIndex] = Segment(last.speakerName, last.text + "\n" + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }
}
