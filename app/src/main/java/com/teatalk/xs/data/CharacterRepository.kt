package com.teatalk.xs.data

import android.content.Context
import android.net.Uri
import com.teatalk.core.card.CardImportException
import com.teatalk.core.card.CardParser
import com.teatalk.core.card.PngTextReader
import com.teatalk.xs.data.db.CharacterDao
import com.teatalk.xs.data.db.CharacterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 一次导入的结果。 */
sealed interface ImportOutcome {
    data class Success(val id: Long, val name: String, val warnings: List<String>) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}

/**
 * 角色卡仓库：内容 URI → 解析（:core）→ 头像落盘 → Room。
 *
 * 解析逻辑全部在 `:core` CardParser，这里是 Android 侧编排。
 */
class CharacterRepository(
    private val appContext: Context,
    private val dao: CharacterDao,
) {

    fun observeCharacters(): Flow<List<CharacterEntity>> = dao.observeAll()

    suspend fun find(id: Long): CharacterEntity? = dao.find(id)

    suspend fun importCard(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use(::readCardBytes)
                ?: return@withContext ImportOutcome.Failure("无法打开所选文件")
            val imported = CardParser.parse(bytes)

            // PNG 卡：原图即头像（与 ST 一致）；JSON/CHARX 暂无头像，下一步做 CHARX 资源。
            val avatarPath = if (PngTextReader.isPng(bytes)) saveAvatar(bytes) else null
            val data = imported.card.data
            val entity = CharacterEntity(
                name = data.name.ifBlank { "未命名角色" },
                avatarPath = avatarPath,
                cardJson = imported.card.raw.toString(),
                cardSource = imported.source.name,
                tagsJson = JsonArray(data.tags.map(::JsonPrimitive)).toString(),
                firstMes = data.firstMes,
                createdAt = System.currentTimeMillis(),
            )
            val id = dao.insert(entity)
            ImportOutcome.Success(id, entity.name, imported.warnings)
        } catch (e: CardImportException) {
            ImportOutcome.Failure(e.message ?: "角色卡解析失败")
        } catch (e: Exception) {
            ImportOutcome.Failure("导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun delete(character: CharacterEntity) = withContext(Dispatchers.IO) {
        dao.delete(character)
        character.avatarPath?.let { runCatching { File(appContext.filesDir, it).delete() } }
        Unit
    }

    private fun saveAvatar(bytes: ByteArray): String {
        val dir = File(appContext.filesDir, AVATAR_DIR).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).writeBytes(bytes)
        return "$AVATAR_DIR/$name"
    }

    /** 限长读取：单张卡上限 64MB，防御超大文件把内存打爆。 */
    private fun readCardBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_CARD_BYTES) throw CardImportException("角色卡超过 ${MAX_CARD_BYTES / 1024 / 1024}MB，已中止")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val AVATAR_DIR = "avatars"
        const val MAX_CARD_BYTES = 64L * 1024 * 1024
    }
}
