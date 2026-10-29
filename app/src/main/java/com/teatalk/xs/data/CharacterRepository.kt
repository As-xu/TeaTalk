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
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoParser
import com.teatalk.xs.data.db.CharacterDao
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.ConversationDao
import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.db.ConversationWithLastMessage
import com.teatalk.xs.data.db.MessageEntity
import com.teatalk.xs.data.db.WorldBookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val conversations: ConversationDao,
    private val worldBookDao: WorldBookDao,
) {

    /**
     * 回复落库的进程内互斥。
     *
     * 「追加版本」本质是读-改-写（读最后一条 → 拼 variantsJson → 写回）。停止与完成
     * 两条路径都可能同时进入，若不串行会互相覆盖：完整回复被半截版覆盖，或出现两条相同变体。
     * 进程内只有这一个仓库实例，Mutex 足够；跨进程无写入方。
     */
    private val replyMutex = Mutex()

    fun observeCharacters(): Flow<List<CharacterEntity>> = dao.observeAll()

    fun observeCharacter(id: Long): Flow<CharacterEntity?> = dao.observeById(id)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    fun observeChats(): Flow<List<ConversationWithLastMessage>> = conversations.observeChats()

    /** 某角色下的所有会话（会话切换面板用），按最近活跃排序。 */
    fun observeConversations(characterId: Long): Flow<List<ConversationEntity>> =
        conversations.observeByCharacter(characterId)

    suspend fun conversation(id: Long): ConversationEntity? = withContext(Dispatchers.IO) { conversations.find(id) }

    /** 聊天页订阅会话本身（会话 → 角色 id → 角色）。 */
    fun observeConversation(id: Long): Flow<ConversationEntity?> = conversations.observeById(id)

    suspend fun find(id: Long): CharacterEntity? = dao.find(id)

    /** 首屏决策：有过会话就直接进「聊天」Tab（否则进「角色」）。 */
    suspend fun hasAnyChat(): Boolean = withContext(Dispatchers.IO) { dao.anyMessageExists() }

    /**
     * 「开始聊天」入口：有会话就回到最近一条，没有就建一条并插入开场白。
     *
     * @return 会话 id；角色不存在返回 null。
     */
    suspend fun ensureConversation(characterId: Long): Long? = withContext(Dispatchers.IO) {
        val character = dao.find(characterId) ?: return@withContext null
        val existing = conversations.latest(characterId)
        val id = existing?.id ?: createConversationInternal(character)
        TeaTalkLog.i("Chat", "conversation.ensure", "characterId" to characterId, "conversationId" to id, "created" to (existing == null))
        id
    }

    /** 显式新建会话（「新建会话」按钮）：总是新建并插入开场白。 */
    suspend fun createConversation(characterId: Long, title: String = ""): Long? = withContext(Dispatchers.IO) {
        val character = dao.find(characterId) ?: return@withContext null
        val id = createConversationInternal(character, title)
        TeaTalkLog.i("Chat", "conversation.create", "characterId" to characterId, "conversationId" to id)
        id
    }

    private suspend fun createConversationInternal(character: CharacterEntity, title: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = conversations.insert(
            ConversationEntity(characterId = character.id, title = title, createdAt = now, updatedAt = now),
        )
        val greeting = character.firstMes.ifBlank { appContext.getString(R.string.greeting_fallback) }
        dao.insertGreetingIfEmpty(conversationId = id, characterId = character.id, content = greeting, createdAt = now)
        return id
    }

    suspend fun renameConversation(conversationId: Long, title: String) = withContext(Dispatchers.IO) {
        conversations.rename(conversationId, title.trim(), System.currentTimeMillis())
    }

    // ---------------- M3：世界书绑定（角色默认 + 会话覆盖） ----------------

    /** 角色默认绑定的世界书 id。 */
    suspend fun linkedWorldBookIds(characterId: Long): List<Long> = withContext(Dispatchers.IO) {
        val character = dao.find(characterId) ?: return@withContext emptyList()
        parseIds(character.linkedWorldBookIdsJson)
    }

    /**
     * 会话级覆盖；`null` 表示继承角色，`[]` 表示显式不启用。
     */
    suspend fun conversationWorldBookOverride(conversationId: Long): List<Long>? = withContext(Dispatchers.IO) {
        val conversation = conversations.find(conversationId) ?: return@withContext null
        conversation.worldBookIdsJson?.let { parseIds(it) }
    }

    /** 实际生效的世界书 id：会话覆盖优先，否则角色默认。 */
    suspend fun effectiveWorldBookIds(characterId: Long, conversationId: Long?): List<Long> =
        withContext(Dispatchers.IO) {
            if (conversationId != null) {
                val override = conversations.find(conversationId)?.worldBookIdsJson
                if (override != null) return@withContext parseIds(override)
            }
            val character = dao.find(characterId) ?: return@withContext emptyList()
            parseIds(character.linkedWorldBookIdsJson)
        }

    suspend fun setCharacterWorldBooks(characterId: Long, ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.setLinkedWorldBooks(characterId, encodeIds(ids), System.currentTimeMillis())
        TeaTalkLog.i("Prompt", "worldbook.bind", "scope" to "character", "count" to ids.size)
    }

    /** `ids = null` 恢复继承角色默认。 */
    suspend fun setConversationWorldBooks(conversationId: Long, ids: List<Long>?) = withContext(Dispatchers.IO) {
        conversations.setWorldBooks(conversationId, ids?.let { encodeIds(it) }, System.currentTimeMillis())
        TeaTalkLog.i(
            "Prompt", "worldbook.bind",
            "scope" to "conversation", "count" to (ids?.size ?: -1),
        )
    }

    /** 按 id 批量取世界书条目（保留传入顺序，忽略已删除的书）。 */
    suspend fun worldBookEntries(ids: List<Long>): List<WorldInfoEntry> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val books = worldBookDao.findMany(ids)
        val byId = books.associateBy { it.id }
        ids.mapNotNull { byId[it] }
            .flatMap { WorldInfoParser.fromStandalone(it.entriesJson) }
    }

    /** 删除一段会话：先删消息，再删会话行（角色保留）。 */
    suspend fun countMessages(conversationId: Long): Long = withContext(Dispatchers.IO) {
        dao.countMessages(conversationId)
    }

    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        dao.deleteMessages(conversationId)
        conversations.deleteById(conversationId)
        TeaTalkLog.i("Chat", "conversation.delete", "conversationId" to conversationId)
    }

    suspend fun touchConversation(conversationId: Long) {
        conversations.touch(conversationId, System.currentTimeMillis())
    }

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
        dao.deleteMessagesForCharacter(character.id)
        conversations.deleteByCharacter(character.id)
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
    suspend fun ensureGreeting(conversationId: Long) = withContext(Dispatchers.IO) {
        val conversation = conversations.find(conversationId) ?: return@withContext
        val character = dao.find(conversation.characterId) ?: return@withContext
        val inserted = dao.insertGreetingIfEmpty(
            conversationId = conversationId,
            characterId = character.id,
            content = character.firstMes.ifBlank { appContext.getString(R.string.greeting_fallback) },
            createdAt = System.currentTimeMillis(),
        )
        TeaTalkLog.i("Chat", "greeting.ensure", "conversationId" to conversationId, "inserted" to inserted)
    }

    /**
     * 落一条消息。M1 起由 [ChatEngine] 在流式前后分别写 user / assistant 两条，
     * 老的「离线假回复」已移除。
     */
    suspend fun addMessage(conversationId: Long, role: String, content: String): Long =
        withContext(Dispatchers.IO) {
            val body = content.trim()
            if (body.isEmpty()) return@withContext -1L
            val conversation = conversations.find(conversationId) ?: return@withContext -1L
            val id = dao.insertMessage(
                MessageEntity(
                    characterId = conversation.characterId,
                    conversationId = conversationId,
                    role = role,
                    content = body,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            touchConversation(conversationId)
            id
        }

    /** 组装 prompt 用：最近 [limit] 条，按时间正序。 */
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity> =
        withContext(Dispatchers.IO) { dao.recentMessages(conversationId, limit) }

    // —— M2：多版本回复 / 切换版本 / 删除消息 ——

    /**
     * 落一条 assistant 回复；若最后一条也是 assistant（重试/换版本），则作为新版本追加。
     *
     * 返回消息 id（-1 表示空文本 / 角色不存在）。状态用 [MessageEntity.STATUS_COMPLETE] 等。
     */
    suspend fun appendAssistantReply(
        conversationId: Long,
        text: String,
        status: String,
        providerId: String? = null,
        providerName: String? = null,
        model: String? = null,
        replyToMessageId: Long? = null,
    ): Long = replyMutex.withLock {
      withContext(Dispatchers.IO) {
        val body = text.trim()
        if (body.isEmpty()) return@withContext -1L
        val conversation = conversations.find(conversationId) ?: return@withContext -1L
        if (dao.find(conversation.characterId) == null) return@withContext -1L
        val last = dao.lastMessage(conversationId)
        // 锚点必须对得上才允许「追加版本」：否则说明写库顺序已经被打乱（停止与新发送交错），
        // 宁可新插入一条，也不要把半截文本盖到别的回复上。
        val anchored = replyToMessageId == null || last?.id == replyToMessageId
        if (last != null && last.role == ROLE_ASSISTANT && anchored) {
            val (variantsJson, statusJson) = MessageVariants.append(
                variantsJson = last.variantsJson,
                variantStatusJson = last.variantStatusJson,
                content = last.content,
                text = body,
                status = status,
            )
            val selected = MessageVariants.parse(variantsJson, body).lastIndex
            dao.updateVariants(
                id = last.id,
                content = body,
                variantsJson = variantsJson,
                variantStatusJson = statusJson,
                selectedVariant = selected,
                status = status,
                providerId = providerId ?: last.providerId,
                providerName = providerName ?: last.providerName,
                model = model ?: last.model,
            )
            TeaTalkLog.i("Chat", "reply.append", "messageId" to last.id, "variants" to (selected + 1), "chars" to body.length, "status" to status)
            last.id
        } else {
            val id = dao.insertMessage(
                MessageEntity(
                    characterId = conversation.characterId,
                    conversationId = conversationId,
                    role = ROLE_ASSISTANT,
                    content = body,
                    createdAt = System.currentTimeMillis(),
                    variantsJson = MessageVariants.encode(listOf(body)),
                    variantStatusJson = MessageVariants.encodeStatuses(listOf(status)),
                    selectedVariant = 0,
                    status = status,
                    providerId = providerId,
                    providerName = providerName,
                    model = model,
                ),
            )
            TeaTalkLog.i("Chat", "reply.insert", "messageId" to id, "chars" to body.length)
            id
        }.also { touchConversation(conversationId) }
      }
    }

    /** 切换某条消息的版本；返回切换后的文本（null 表示下标无效）。 */
    suspend fun selectVariant(messageId: Long, index: Int): String? = withContext(Dispatchers.IO) {
        val message = dao.findMessage(messageId) ?: return@withContext null
        val variants = MessageVariants.of(message)
        if (index !in variants.indices) return@withContext null
        val text = variants[index]
        val status = MessageVariants.statuses(message)[index]
        dao.selectVariant(messageId, text, index, status)
        TeaTalkLog.i("Chat", "variant.select", "messageId" to messageId, "index" to index)
        text
    }

    /** 该角色最后一条 assistant 消息 id（重试入口的守卫：只有在有回复可重生成时才允许重试）。 */
    suspend fun lastAssistantMessageId(conversationId: Long): Long? = withContext(Dispatchers.IO) {
        dao.lastMessage(conversationId)?.takeIf { it.role == ROLE_ASSISTANT }?.id
    }

    /** 删除会话前的快照：会话行 + 全部消息（用于 5s 内撤销）。 */
    data class DeletedConversation(
        val conversation: ConversationEntity,
        val messages: List<MessageEntity>,
    )

    /**
     * 删除会话并返回快照。
     *
     * 为什么快照而不是软删除：会话量级小、删除是低频操作；快照足以支撑「撤销」，
     * 又不需要在每个查询里过滤 `deleted=0`（少一个容易忘的条件）。
     */
    suspend fun deleteConversationWithSnapshot(id: Long): DeletedConversation? = withContext(Dispatchers.IO) {
        val conversation = conversations.find(id) ?: return@withContext null
        val messages = dao.allMessages(id)
        conversations.deleteById(id)
        dao.deleteMessages(id)
        TeaTalkLog.i(
            "Chat",
            "conversation.delete",
            "conversationId" to id,
            "messages" to messages.size,
        )
        DeletedConversation(conversation, messages)
    }

    /** 撤销删除：按原 id 写回会话与消息。 */
    suspend fun restoreConversation(snapshot: DeletedConversation) = withContext(Dispatchers.IO) {
        conversations.insertExact(snapshot.conversation)
        if (snapshot.messages.isNotEmpty()) dao.insertMessagesExact(snapshot.messages)
        TeaTalkLog.i(
            "Chat",
            "conversation.restore",
            "conversationId" to snapshot.conversation.id,
            "messages" to snapshot.messages.size,
        )
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

    private fun encodeIds(ids: List<Long>): String =
        ids.distinct().sorted().joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun parseIds(json: String?): List<Long> = runCatching {
        val array = Json.parseToJsonElement(json ?: "[]") as JsonArray
        array.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
    }.getOrDefault(emptyList())
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
