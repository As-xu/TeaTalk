package com.teatalk.xs.data

import android.content.Context
import android.net.Uri
import com.teatalk.core.card.CardImportException
import com.teatalk.xs.R
import com.teatalk.core.card.CardParser
import com.teatalk.core.card.PngTextReader
import com.teatalk.xs.data.db.CharacterDao
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.CharacterWithLastMessage
import com.teatalk.xs.data.db.MessageEntity
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
    data class Failure(val message: UiText) : ImportOutcome
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

    fun observeCharacter(id: Long): Flow<CharacterEntity?> = dao.observeById(id)

    fun observeMessages(characterId: Long): Flow<List<MessageEntity>> = dao.observeMessages(characterId)

    fun observeChats(): Flow<List<CharacterWithLastMessage>> = dao.observeChats()

    suspend fun find(id: Long): CharacterEntity? = dao.find(id)

    /** 首屏决策：有过会话就直接进「聊天」Tab（否则进「角色」）。 */
    suspend fun hasAnyChat(): Boolean = withContext(Dispatchers.IO) { dao.countChats() > 0 }

    suspend fun importCard(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use(::readCardBytes)
                ?: return@withContext ImportOutcome.Failure(UiText.of(R.string.import_open_failed))
            val imported = CardParser.parse(bytes)

            // PNG 卡：原图即头像（与 ST 一致）；JSON/CHARX 暂无头像，下一步做 CHARX 资源。
            val avatarPath = if (PngTextReader.isPng(bytes)) saveAvatar(bytes) else null
            val data = imported.card.data
            val entity = CharacterEntity(
                name = data.name.ifBlank { appContext.getString(R.string.unnamed_character) },
                avatarPath = avatarPath,
                cardJson = imported.card.raw.toString(),
                cardSource = imported.source.name,
                tagsJson = JsonArray(data.tags.map(::JsonPrimitive)).toString(),
                firstMes = data.firstMes,
                createdAt = System.currentTimeMillis(),
            )
            val id = try {
                dao.insert(entity)
            } catch (e: Exception) {
                // 入库失败：别把刚写的头像变成孤儿文件
                avatarPath?.let { runCatching { File(appContext.filesDir, it).delete() } }
                throw e
            }
            ImportOutcome.Success(id, entity.name, imported.warnings)
        } catch (e: CardTooLargeException) {
            ImportOutcome.Failure(UiText.of(R.string.import_card_too_large, e.maxMb))
        } catch (e: CardImportException) {
            // :core 的异常文案是开发期诊断用的（中文），对用户统一显示本地化的“解析失败”
            ImportOutcome.Failure(UiText.of(R.string.import_parse_failed))
        } catch (e: Exception) {
            ImportOutcome.Failure(UiText.of(R.string.import_failed_detail, e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun delete(character: CharacterEntity) = withContext(Dispatchers.IO) {
        dao.deleteMessages(character.id)
        dao.delete(character)
        character.avatarPath?.let { runCatching { File(appContext.filesDir, it).delete() } }
        Unit
    }

    /** 首次进聊天页：插入开场白（原子判空插入，重复进入不会插两条）。 */
    suspend fun ensureGreeting(characterId: Long) = withContext(Dispatchers.IO) {
        val character = dao.find(characterId) ?: return@withContext
        dao.insertGreetingIfEmpty(
            characterId = characterId,
            content = character.firstMes.ifBlank { appContext.getString(R.string.greeting_fallback) },
            createdAt = System.currentTimeMillis(),
        )
    }

    /**
     * 发送一条用户消息并落一条**离线示例回复**。
     *
     * D4 只为把聊天页的数据链路跑通；D5/M2 接上模型后，这里换成 Prompt 管线 + 流式 SSE。
     */
    suspend fun sendMessage(character: CharacterEntity, text: String) = withContext(Dispatchers.IO) {
        val content = text.trim()
        if (content.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        dao.insertMessage(MessageEntity(characterId = character.id, role = ROLE_USER, content = content, createdAt = now))
        val reply = appContext.getString(R.string.offline_reply, content, character.name)
        dao.insertMessage(
            MessageEntity(characterId = character.id, role = ROLE_ASSISTANT, content = reply, createdAt = now + 1),
        )
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
            if (total > MAX_CARD_BYTES) throw CardTooLargeException(MAX_CARD_BYTES / 1024 / 1024)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val AVATAR_DIR = "avatars"
        const val MAX_CARD_BYTES = 64L * 1024 * 1024
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** 单卡超过上限时抛这个，好让上层给出本地化提示（:core 里的异常文案是中文诊断信息）。 */
class CardTooLargeException(val maxMb: Long) : Exception("card exceeds $maxMb MB")
