package com.teatalk.xs.data

import android.content.Context
import android.util.Log
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.db.MessageEntity
import com.teatalk.xs.data.db.TeaTalkDatabase
import com.teatalk.xs.data.db.WorldBookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * M4 加固：本地备份导出 / 恢复（角色卡 + 世界书 + 会话与消息，单文件 zip）。
 *
 * 文件结构：
 * ```
 * manifest.json        # 全部结构化数据（JSON）
 * avatars/<n>.png      # 角色头像原图（PNG 卡；manifest 里 characters[].avatar 指到 n）
 * ```
 *
 * 设计取舍：
 * - 只做**本机备份**语义，不联网、不加密（与「数据只存在本机」一致）；敏感内容由用户自行保管。
 * - 恢复时按名字去重：同名角色/世界书已存在则跳过对应条目，避免重复刷出副本。
 * - 聊天按 (角色名, 标题) 去重；恢复后会话归属到现有或新建的角色。
 */
class BackupRepository(
    private val appContext: Context,
    private val database: TeaTalkDatabase,
) {

    data class Summary(
        var cards: Int = 0,
        var worldBooks: Int = 0,
        var chats: Int = 0,
        var messages: Int = 0,
        var skipped: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun export(): ByteArray = withContext(Dispatchers.IO) {
        val characterDao = database.characterDao()
        val worldBookDao = database.worldBookDao()
        val conversationDao = database.conversationDao()

        val characters = characterDao.allOnce()
        val worldBooks = worldBookDao.observeAll().first()
        val conversations = conversationDao.allOnce()

        val messagesByConversation = conversations.associate { it.id to characterDao.allMessages(it.id) }
        val avatarEntries = LinkedHashMap<Int, File>()
        characters.forEachIndexed { index, character ->
            val path = character.avatarPath ?: return@forEachIndexed
            val file = File(appContext.filesDir, path)
            if (file.isFile) avatarEntries[index] = file
        }

        val manifest = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            putJsonArray("characters") {
                characters.forEachIndexed { index, c ->
                    addJsonObject {
                        put("name", c.name)
                        put("avatarPath", c.avatarPath)
                        put("cardJson", c.cardJson)
                        put("cardSource", c.cardSource)
                        put("tagsJson", c.tagsJson)
                        put("firstMes", c.firstMes)
                        put("createdAt", c.createdAt)
                        put("favorite", c.favorite)
                        put("pinned", c.pinned)
                        put("updatedAt", c.updatedAt)
                        put("linkedWorldBookIdsJson", c.linkedWorldBookIdsJson)
                        if (index in avatarEntries) put("avatar", index)
                    }
                }
            }
            putJsonArray("worldBooks") {
                worldBooks.forEach { b ->
                    addJsonObject {
                        put("id", b.id)
                        put("name", b.name)
                        put("source", b.source)
                        put("entriesJson", b.entriesJson)
                        put("entryCount", b.entryCount)
                        put("createdAt", b.createdAt)
                        put("updatedAt", b.updatedAt)
                    }
                }
            }
            val characterNames = characters.associate { it.id to it.name }
            putJsonArray("conversations") {
                conversations.forEach { conversation ->
                    val messages = messagesByConversation[conversation.id].orEmpty()
                    addJsonObject {
                        put("characterName", characterNames[conversation.characterId] ?: "")
                        put("title", conversation.title)
                        put("worldBookIdsJson", conversation.worldBookIdsJson)
                        put("createdAt", conversation.createdAt)
                        put("updatedAt", conversation.updatedAt)
                        putJsonArray("messages") {
                            messages.forEach { m ->
                                addJsonObject {
                                    put("role", m.role)
                                    put("content", m.content)
                                    put("createdAt", m.createdAt)
                                    put("variantsJson", m.variantsJson)
                                    put("variantStatusJson", m.variantStatusJson)
                                    put("selectedVariant", m.selectedVariant)
                                    put("status", m.status)
                                    put("providerId", m.providerId)
                                    put("providerName", m.providerName)
                                    put("model", m.model)
                                }
                            }
                        }
                    }
                }
            }
        }

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            avatarEntries.forEach { (index, file) ->
                zip.putNextEntry(ZipEntry("avatars/$index.png"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    /** 快速校验：是不是本应用的备份包（只读 manifest，不落库）。 */
    suspend fun canRestore(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val entries = unzip(bytes) ?: return@withContext false
        val manifestBytes = entries[MANIFEST_ENTRY] ?: return@withContext false
        val manifest = runCatching {
            json.parseToJsonElement(String(manifestBytes, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return@withContext false
        manifest["format"]?.jsonPrimitive?.contentOrNull == FORMAT
    }

    suspend fun restore(bytes: ByteArray): Summary? = withContext(Dispatchers.IO) {
        val entries = unzip(bytes) ?: return@withContext null
        val manifestBytes = entries[MANIFEST_ENTRY] ?: return@withContext null
        val manifest = runCatching { json.parseToJsonElement(String(manifestBytes, Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: return@withContext null
        if (manifest["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return@withContext null

        val characterDao = database.characterDao()
        val worldBookDao = database.worldBookDao()
        val conversationDao = database.conversationDao()
        val summary = Summary()

        // 1) 世界书（先建，方便后面的绑定 id 重映射）
        val bookIdMap = HashMap<Long, Long>()
        val existingBooks = worldBookDao.observeAll().first()
        for (element in manifest["worldBooks"]?.jsonArray.orEmpty()) {
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val exists = existingBooks.firstOrNull { it.name.equals(name, true) }
            val oldId = obj["id"]?.jsonPrimitive?.longOrNull
            if (exists != null) {
                if (oldId != null) bookIdMap[oldId] = exists.id
                summary.skipped++
                continue
            }
            val newId = worldBookDao.insert(
                WorldBookEntity(
                    name = name,
                    source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "st_json",
                    entriesJson = obj["entriesJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    entryCount = obj["entryCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                ),
            )
            if (oldId != null) bookIdMap[oldId] = newId
            summary.worldBooks++
        }

        // 2) 角色卡（同名去重；头像字节写回 filesDir/avatars）
        val characterIdMap = HashMap<Int, Long>()
        val existingCards = characterDao.allOnce()
        val charactersArray = manifest["characters"]?.jsonArray.orEmpty()
        for ((index, element) in charactersArray.withIndex()) {
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val firstMes = obj["firstMes"]?.jsonPrimitive?.contentOrNull ?: ""
            val existing = existingCards.firstOrNull { it.name == name && it.firstMes == firstMes }
            if (existing != null) {
                characterIdMap[index] = existing.id
                summary.skipped++
                continue
            }
            val avatarIndex = obj["avatar"]?.jsonPrimitive?.intOrNull
            var avatarPath: String? = null
            if (avatarIndex != null) {
                val avatarBytes = entries["avatars/$avatarIndex.png"]
                if (avatarBytes != null) avatarPath = saveAvatar(avatarBytes)
            }
            val linkedJson = remapWorldBookIds(
                obj["linkedWorldBookIdsJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                bookIdMap,
            )
            val newId = characterDao.insert(
                CharacterEntity(
                    name = name,
                    avatarPath = avatarPath,
                    cardJson = obj["cardJson"]?.jsonPrimitive?.contentOrNull ?: "",
                    cardSource = obj["cardSource"]?.jsonPrimitive?.contentOrNull ?: "manual",
                    tagsJson = obj["tagsJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    firstMes = firstMes,
                    createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    favorite = obj["favorite"]?.jsonPrimitive?.contentOrNull.toBoolean(),
                    pinned = obj["pinned"]?.jsonPrimitive?.contentOrNull.toBoolean(),
                    updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    linkedWorldBookIdsJson = linkedJson,
                ),
            )
            characterIdMap[index] = newId
            summary.cards++
        }

        // 3) 会话与消息（按 (角色名, 标题) 去重）
        val charactersJson = manifest["characters"]?.jsonArray ?: JsonArray(emptyList())
        val namesById = characterIdMap.mapNotNull { (index, id) ->
            val name = charactersJson.getOrNull(index)?.jsonObject?.get("name")
                ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to name
        }.toMap()
        val allCards = characterDao.allOnce()
        for (element in manifest["conversations"]?.jsonArray.orEmpty()) {
            val obj = element.jsonObject
            val characterName = obj["characterName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val characterId = namesById.entries.firstOrNull { it.value == characterName }?.key
                ?: allCards.firstOrNull { it.name == characterName }?.id
                ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            val existingConversations = conversationDao.observeByCharacter(characterId).first()
            val exists = existingConversations.any { existing ->
                // 有标题时按标题判重；无标题（默认会话）时再比 createdAt，避免把默认会话又恢复一份。
                if (existing.title == title) {
                    if (title.isNotEmpty()) true else existing.createdAt == createdAt
                } else {
                    false
                }
            }
            if (exists) {
                summary.skipped++
                continue
            }
            val conversation = ConversationEntity(
                characterId = characterId,
                title = title,
                worldBookIdsJson = obj["worldBookIdsJson"]?.jsonPrimitive?.contentOrNull.let { raw ->
                    if (raw == null || raw == "null") null else remapWorldBookIds(raw, bookIdMap)
                },
                createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            )
            val messages = obj["messages"]?.jsonArray.orEmpty().map { m ->
                val mo = m.jsonObject
                MessageEntity(
                    characterId = characterId,
                    conversationId = 0L,
                    role = mo["role"]?.jsonPrimitive?.contentOrNull ?: MessageEntity.STATUS_COMPLETE,
                    content = mo["content"]?.jsonPrimitive?.contentOrNull ?: "",
                    createdAt = mo["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    variantsJson = mo["variantsJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    variantStatusJson = mo["variantStatusJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    selectedVariant = mo["selectedVariant"]?.jsonPrimitive?.intOrNull ?: 0,
                    status = mo["status"]?.jsonPrimitive?.contentOrNull ?: MessageEntity.STATUS_COMPLETE,
                    providerId = mo["providerId"]?.jsonPrimitive?.contentOrNull,
                    providerName = mo["providerName"]?.jsonPrimitive?.contentOrNull,
                    model = mo["model"]?.jsonPrimitive?.contentOrNull,
                )
            }
            // 会话与消息必须同成同败：失败时不能留下一条空会话，否则重试会被「已存在」跳过。
            if (messages.isEmpty()) conversationDao.insertExact(conversation)
            else conversationDao.insertExactWithMessages(conversation, messages)
            summary.chats++
            summary.messages += messages.size
        }

        Log.i("TeaTalk", "backup.restore cards=${summary.cards} books=${summary.worldBooks} chats=${summary.chats} msgs=${summary.messages} skipped=${summary.skipped}")
        summary
    }

    private fun remapWorldBookIds(raw: String, map: Map<Long, Long>): String {
        val parsed = WorldBookIds.parse(raw)
        if (parsed.isEmpty()) return raw
        val mapped = parsed.mapNotNull { map[it] }
        return buildJsonArray { mapped.forEach { add(it) } }.toString()
    }

    private fun saveAvatar(bytes: ByteArray): String {
        val dir = File(appContext.filesDir, "avatars").apply { mkdirs() }
        val name = "backup-${UUID.randomUUID()}.png"
        File(dir, name).writeBytes(bytes)
        return "${"avatars"}/$name"
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray>? = runCatching {
        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RESTORE_BYTES) return null
                        out.write(buf, 0, read)
                    }
                    result[entry.name] = out.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        result
    }.getOrNull()

    private fun String?.toBoolean(): Boolean = this == "true"

    companion object {
        private const val FORMAT = "teatalk-backup"
        private const val VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_RESTORE_BYTES = 512L * 1024 * 1024
    }
}
