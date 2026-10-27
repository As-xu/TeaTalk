package com.teatalk.core.card

import java.util.Base64

/** PNG tEXt chunk 的一条记录。keyword 大小写不敏感（`chara` / `ccv3`）。 */
data class PngText(val keyword: String, val text: String)

/**
 * 极简 PNG tEXt 读取器。
 *
 * 只做角色卡需要的事：扫 chunk，抽 tEXt，取 base64 里的 JSON。
 * 与 SillyTavern `src/character-card-parser.js` 行为对齐：
 * 只认 tEXt（不读 iTXt/zTXt），`ccv3` 优先于 `chara`。
 */
object PngTextReader {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private const val KEY_CHARA = "chara"
    private const val KEY_CCV3 = "ccv3"

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /** 读出所有 tEXt；非 PNG 返回空表，损坏处直接停止（宽容，不抛）。 */
    fun readTexts(bytes: ByteArray): List<PngText> {
        if (!isPng(bytes)) return emptyList()
        val out = ArrayList<PngText>()
        var pos = SIGNATURE.size.toLong()
        val total = bytes.size.toLong()
        while (pos + 8 <= total) {
            // chunk 长度是无符号 32 位：用 Long 参与运算，避免 Int 溢出绕过越界检查
            // （曾出现 length=0x7FFFFFF8 时 dataStart+length 回绕成负数，随后越界崩）。
            val length = readUInt32(bytes, pos.toInt())
            val type = String(bytes, pos.toInt() + 4, 4, Charsets.ISO_8859_1)
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            // 长度非法 / 越界 → 文件截断或损坏，停止扫描
            if (dataEnd + 4 > total) break
            if (type == "tEXt") {
                parseTextChunk(bytes, dataStart.toInt(), length.toInt())?.let(out::add)
            }
            if (type == "IEND") break
            pos = dataEnd + 4 // 跳过 4 字节 CRC（不校验）
        }
        return out
    }

    fun hasKeyword(bytes: ByteArray, keyword: String): Boolean =
        readTexts(bytes).any { it.keyword.equals(keyword, ignoreCase = true) }

    /**
     * 取出角色卡 JSON 文本：ccv3 优先，其次 chara；都没有返回 null。
     * base64 损坏时抛 [CardImportException]——这是"像卡但不是卡"的情况，要报给用户。
     */
    fun cardJson(bytes: ByteArray): String? {
        val texts = readTexts(bytes)
        val picked = texts.firstOrNull { it.keyword.equals(KEY_CCV3, ignoreCase = true) }
            ?: texts.firstOrNull { it.keyword.equals(KEY_CHARA, ignoreCase = true) }
            ?: return null
        return try {
            String(Base64.getDecoder().decode(picked.text.trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw CardImportException(
                CardImportReason.NO_CHARACTER_DATA,
                "PNG 的 ${picked.keyword} 元数据不是合法 base64",
                e,
            )
        }
    }

    private fun parseTextChunk(bytes: ByteArray, start: Int, length: Int): PngText? {
        // tEXt = keyword(NUL)text，两者都是 Latin-1
        var nul = -1
        for (i in start until start + length) {
            if (bytes[i] == 0.toByte()) {
                nul = i
                break
            }
        }
        if (nul <= start) return null // 没有分隔符 / 空 keyword
        val keyword = String(bytes, start, nul - start, Charsets.ISO_8859_1)
        val text = String(bytes, nul + 1, start + length - nul - 1, Charsets.ISO_8859_1)
        return PngText(keyword, text)
    }

    private fun readUInt32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)
}
