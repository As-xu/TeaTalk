package com.teatalk.xs.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoParser
import com.teatalk.xs.R
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
) {

    fun observeAll(): Flow<List<WorldBookEntity>> = dao.observeAll()

    fun entriesOf(book: WorldBookEntity): List<WorldInfoEntry> = WorldInfoParser.fromCard(book.entriesJson)

    suspend fun import(uri: Uri): WorldBookImportOutcome = withContext(Dispatchers.IO) {
        val text = try {
            appContext.contentResolver.openInputStream(uri)?.use { readLimited(it) }
        } catch (e: Exception) {
            null
        } ?: return@withContext WorldBookImportOutcome.Failure(UiText.of(R.string.import_open_failed))

        val entries = WorldInfoParser.fromCard(text)
        if (entries.isEmpty()) {
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
        WorldBookImportOutcome.Success(name, entries.size)
    }

    suspend fun delete(book: WorldBookEntity) = withContext(Dispatchers.IO) { dao.deleteById(book.id) }

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
