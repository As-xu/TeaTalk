package com.teatalk.xs.data

import android.content.Context
import android.net.Uri
import com.teatalk.core.card.CardImportException
import com.teatalk.xs.R
import com.teatalk.core.card.CardImportReason
import com.teatalk.core.card.CardParser
import com.teatalk.core.card.PngTextReader
import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.db.CharacterDao
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.CharacterWithLastMessage
import com.teatalk.xs.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        val started = System.currentTimeMillis()
        TeaTalkLog.i("Card", "import.start", "uri" to Redact.url(uri.toString()))
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
                updatedAt = System.currentTimeMillis(),
            )
            TeaTalkLog.i(
                "Card",
                "import.ok",
                "source" to imported.source.name,
                "bytes" to bytes.size,
                "warnings" to imported.warnings.size,
                "ms" to (System.currentTimeMillis() - started),
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
            TeaTalkLog.w("Card", "import.too_large", "maxMb" to e.maxMb)
            ImportOutcome.Failure(UiText.of(R.string.import_card_too_large, e.maxMb))
        } catch (e: CardImportException) {
            // :core 的异常文案是开发期诊断用的（中文）；对用户按「原因」给可操作的提示
            TeaTalkLog.w(
                "Card",
                "import.fail",
                "reason" to e.reason.name,
                "ms" to (System.currentTimeMillis() - started),
            )
            ImportOutcome.Failure(UiText.of(reasonString(e.reason)))
        } catch (e: Exception) {
            TeaTalkLog.e("Card", "import.fail", e, "ms" to (System.currentTimeMillis() - started))
            ImportOutcome.Failure(UiText.of(R.string.import_failed_detail, e.message ?: e.javaClass.simpleName))
        }
    }

    /** 把「为什么不是角色卡」翻译成用户能行动的文案。 */
    private fun reasonString(reason: CardImportReason): Int = when (reason) {
        CardImportReason.NO_CHARACTER_DATA -> R.string.import_err_not_a_card
        CardImportReason.UNSUPPORTED_IMAGE -> R.string.import_err_jpeg
        CardImportReason.UNSUPPORTED_FILE -> R.string.import_err_unsupported
        CardImportReason.MISSING_CARD_JSON -> R.string.import_err_charx
        CardImportReason.CARD_JSON_TOO_LARGE -> R.string.import_err_too_large
        CardImportReason.BAD_JSON -> R.string.import_err_bad_json
        CardImportReason.MISSING_DATA -> R.string.import_err_missing_data
    }

    suspend fun delete(character: CharacterEntity) = withContext(Dispatchers.IO) {
        dao.deleteMessages(character.id)
        dao.delete(character)
        character.avatarPath?.let { runCatching { File(appContext.filesDir, it).delete() } }
        TeaTalkLog.i("Card", "delete.ok", "id" to character.id)
        Unit
    }

    // —— D6 卡库：收藏 / 置顶 / 编辑 ——

    suspend fun setFavorite(character: CharacterEntity, on: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(character.id, on, System.currentTimeMillis())
    }

    suspend fun setPinned(character: CharacterEntity, on: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(character.id, on, System.currentTimeMillis())
    }

    /**
     * 保存卡片编辑结果。
     *
     * 只改「元信息 + 设定类字段」，保持 V1/V2 结构不变：
     * - V2/V3：写回 `data.*`（`first_mes` / `creator_notes` 用标准 snake_case）
     * - V1 平铺：写回根对象
     * 三个索引列（name / tagsJson / firstMes）同步更新，避免列表搜索与聊天开场白读到旧值。
     */
    suspend fun updateCard(character: CharacterEntity, edit: CardEdit) = withContext(Dispatchers.IO) {
        val root = runCatching { Json.parseToJsonElement(character.cardJson) as? JsonObject }.getOrNull()
        val dataObject = root?.get("data") as? JsonObject
        val target = dataObject ?: root ?: JsonObject(emptyMap())
        val updated = target.toMutableMap().apply {
            put("name", JsonPrimitive(edit.name))
            put("description", JsonPrimitive(edit.description))
            put("personality", JsonPrimitive(edit.personality))
            put("scenario", JsonPrimitive(edit.scenario))
            put("first_mes", JsonPrimitive(edit.firstMes))
            put("creator_notes", JsonPrimitive(edit.creatorNotes))
            put("tags", JsonArray(edit.tags.map(::JsonPrimitive)))
        }
        val newCardJson = when {
            dataObject != null -> JsonObject(root!!.toMutableMap().apply { put("data", JsonObject(updated)) })
            root != null -> JsonObject(updated)
            else -> return@withContext
        }.toString()
        dao.updateCard(
            id = character.id,
            name = edit.name,
            tagsJson = JsonArray(edit.tags.map(::JsonPrimitive)).toString(),
            firstMes = edit.firstMes,
            cardJson = newCardJson,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * v1→v2 迁移后的标签回填：老数据的 `tagsJson` 是空数组，
     * 从 `cardJson` 重新抽一次（只写 tags，不动其他列）。
     */
    suspend fun backfillTags() = withContext(Dispatchers.IO) {
        dao.charactersWithoutTags().forEach { character ->
            val tags = CardViewParser.parse(character.cardJson).tags
            if (tags.isNotEmpty()) {
                dao.updateCard(
                    id = character.id,
                    name = character.name,
                    tagsJson = JsonArray(tags.map(::JsonPrimitive)).toString(),
                    firstMes = character.firstMes,
                    cardJson = character.cardJson,
                    updatedAt = character.updatedAt,
                )
            }
        }
    }

    /** 首次进聊天页：插入开场白（原子判空插入，重复进入不会插两条）。 */
    suspend fun ensureGreeting(characterId: Long) = withContext(Dispatchers.IO) {
        val character = dao.find(characterId) ?: return@withContext
        val inserted = dao.insertGreetingIfEmpty(
            characterId = characterId,
            content = character.firstMes.ifBlank { appContext.getString(R.string.greeting_fallback) },
            createdAt = System.currentTimeMillis(),
        )
        TeaTalkLog.i("Chat", "greeting.ensure", "characterId" to characterId, "inserted" to inserted)
    }

    /**
     * 落一条消息。M1 起由 [ChatEngine] 在流式前后分别写 user / assistant 两条，
     * 老的「离线假回复」已移除。
     */
    suspend fun addMessage(characterId: Long, role: String, content: String): Long =
        withContext(Dispatchers.IO) {
            val body = content.trim()
            if (body.isEmpty()) return@withContext -1L
            dao.insertMessage(
                MessageEntity(
                    characterId = characterId,
                    role = role,
                    content = body,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    /** 组装 prompt 用：最近 [limit] 条，按时间正序。 */
    suspend fun recentMessages(characterId: Long, limit: Int): List<MessageEntity> =
        withContext(Dispatchers.IO) { dao.recentMessages(characterId, limit) }

    // —— M2：多版本回复 / 切换版本 / 删除消息 ——

    /**
     * 落一条 assistant 回复；若最后一条也是 assistant（重试/换版本），则作为新版本追加。
     *
     * 返回消息 id（-1 表示空文本 / 角色不存在）。状态用 [MessageEntity.STATUS_COMPLETE] 等。
     */
    suspend fun appendAssistantReply(characterId: Long, text: String, status: String): Long = withContext(Dispatchers.IO) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext -1L
        if (dao.find(characterId) == null) return@withContext -1L
        val last = dao.lastMessage(characterId)
        if (last != null && last.role == ROLE_ASSISTANT) {
            val variants = MessageVariants.of(last) + body
            val selected = variants.lastIndex
            dao.updateVariants(
                id = last.id,
                content = body,
                variantsJson = MessageVariants.encode(variants),
                selectedVariant = selected,
                status = status,
            )
            TeaTalkLog.i("Chat", "reply.append", "messageId" to last.id, "variants" to variants.size, "chars" to body.length)
            last.id
        } else {
            val id = dao.insertMessage(
                MessageEntity(
                    characterId = characterId,
                    role = ROLE_ASSISTANT,
                    content = body,
                    createdAt = System.currentTimeMillis(),
                    variantsJson = MessageVariants.encode(listOf(body)),
                    selectedVariant = 0,
                    status = status,
                ),
            )
            TeaTalkLog.i("Chat", "reply.insert", "messageId" to id, "chars" to body.length)
            id
        }
    }

    /** 切换某条消息的版本；返回切换后的文本（null 表示下标无效）。 */
    suspend fun selectVariant(messageId: Long, index: Int): String? = withContext(Dispatchers.IO) {
        val message = dao.findMessage(messageId) ?: return@withContext null
        val variants = MessageVariants.of(message)
        if (index !in variants.indices) return@withContext null
        val text = variants[index]
        dao.selectVariant(messageId, text, index)
        TeaTalkLog.i("Chat", "variant.select", "messageId" to messageId, "index" to index)
        text
    }

    /** 删除一条消息（用户/assistant 都允许）。 */
    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        dao.deleteMessage(messageId)
        TeaTalkLog.i("Chat", "message.delete", "messageId" to messageId)
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

/** 卡片编辑表单（D6）。`tags` 已拆分好，写库时序列化回 JSON 数组。 */
data class CardEdit(
    val name: String,
    val tags: List<String>,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMes: String,
    val creatorNotes: String,
)

/** 单卡超过上限时抛这个，好让上层给出本地化提示（:core 里的异常文案是中文诊断信息）。 */
class CardTooLargeException(val maxMb: Long) : Exception("card exceeds $maxMb MB")
