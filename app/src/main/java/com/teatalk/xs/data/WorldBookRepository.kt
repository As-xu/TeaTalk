package com.teatalk.xs.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoParser
import com.teatalk.xs.R
import com.teatalk.xs.data.db.CharacterDao
import com.teatalk.xs.data.db.ConversationDao
import com.teatalk.xs.data.db.WorldBookDao
import com.teatalk.xs.data.db.WorldBookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 世界书导入结果。 */
sealed interface WorldBookImportOutcome {
    data class Success(val name: String, val entryCount: Int) : WorldBookImportOutcome
    data class Failure(val message: UiText) : WorldBookImportOutcome
}

/**
 * 世界书仓库（D6）：支持 SillyTavern 独立世界书 JSON 与角色卡内嵌世界书。
 *
 * 存原始 JSON（`entriesJson`），条目解析交给 `:core` 的 [WorldInfoParser]；
 * 绑定会话 / 注入 prompt 属于 M2 的 Prompt 管线，这里只做管理。
 */
class WorldBookRepository(
    private val appContext: Context,
    private val dao: WorldBookDao,
    private val characters: CharacterDao,
    private val conversations: ConversationDao,
) {

    fun observeAll(): Flow<List<WorldBookEntity>> = dao.observeAll()

    fun entriesOf(book: WorldBookEntity): List<WorldInfoEntry> = WorldInfoParser.fromCard(book.entriesJson)

    suspend fun import(uri: Uri): WorldBookImportOutcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        TeaTalkLog.i("Card", "worldbook.import.start", "uri" to Redact.url(uri.toString()))
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        } catch (e: Exception) {
            null
        } ?: return@withContext WorldBookImportOutcome.Failure(UiText.of(R.string.import_open_failed))

        val entries = WorldInfoParser.fromCard(text)
        if (entries.isEmpty()) {
            TeaTalkLog.w("Card", "worldbook.import.empty", "bytes" to text.length)
            return@withContext WorldBookImportOutcome.Failure(UiText.of(R.string.worldbook_empty))
        }

        val name = displayName(uri) ?: appContext.getString(R.string.worldbook_default_name)
        val now = System.currentTimeMillis()
        dao.insert(
            WorldBookEntity(
                name = name,
                source = SOURCE_ST_JSON,
                entriesJson = text,
                entryCount = entries.size,
                createdAt = now,
                updatedAt = now,
            ),
        )
        TeaTalkLog.i(
            "Card",
            "worldbook.import.ok",
            "entries" to entries.size,
            "bytes" to text.length,
            "ms" to (System.currentTimeMillis() - started),
        )
        WorldBookImportOutcome.Success(name, entries.size)
    }

    /** M4-2：编辑单条条目（写回原始 JSON，保留未编辑字段）。成功返回 true。 */
    suspend fun updateEntry(book: WorldBookEntity, entry: WorldInfoEntry): Boolean = withContext(Dispatchers.IO) {
        val patched = WorldBookEntryPatch.patch(book.entriesJson, entry) ?: return@withContext false
        val count = WorldInfoParser.fromCard(patched).size
        dao.updateEntries(book.id, patched, count, System.currentTimeMillis())
        TeaTalkLog.i("Card", "worldbook.entry.update", "bookId" to book.id, "uid" to entry.uid)
        true
    }

    suspend fun delete(book: WorldBookEntity) = withContext(Dispatchers.IO) {
        dao.deleteById(book.id)
        // 级联清理绑定：书没了但 id 还挂在角色/会话上时，UI 会显示「0 本书却 1 本已绑定」，
        // 而且该 id 无法通过弹层取消（没有对应行）。这里静默摘除，且不刷新 updatedAt。
        val id = book.id
        var cleared = 0
        characters.allOnce().forEach { character ->
            val ids = WorldBookIds.parse(character.linkedWorldBookIdsJson)
            if (id in ids) {
                characters.setLinkedWorldBooks(character.id, WorldBookIds.encode(ids - id))
                cleared++
            }
        }
        conversations.allOnce().forEach { conversation ->
            val ids = WorldBookIds.parse(conversation.worldBookIdsJson)
            if (id in ids) {
                conversations.setWorldBooksQuiet(conversation.id, WorldBookIds.encode(ids - id))
                cleared++
            }
        }
        TeaTalkLog.i("Card", "worldbook.delete", "id" to id, "clearedBindings" to cleared)
    }

    /** 从 SAF 的 DISPLAY_NAME 取名字，去掉 .json 后缀。 */
    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
            ?.removeSuffix(".json")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= MAX_WORLDBOOK_BYTES) { "world book exceeds limit" }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_WORLDBOOK_BYTES = 8 * 1024 * 1024
        const val SOURCE_ST_JSON = "st_json"
    }
}
