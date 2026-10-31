package com.teatalk.xs.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.teatalk.core.card.CardParser
import com.teatalk.core.card.PngTextReader
import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * M4-4/M4 加固：把 SillyTavern 的 `data/default-user` 目录（或它的 `.zip` 整包）导入。
 *
 * 只处理三类内容（其余如 settings/themes/presets 由各自入口负责）：
 * - `characters/` 下的 `.png` / `.json` / `.charx` → 角色卡
 * - `worlds/` 下的 `.json` → 独立世界书
 * - `chats/<角色名>/` 下的 `.jsonl` → 该角色的聊天记录（按角色名匹配；匹配不到的计入跳过）
 *
 * 目录名大小写不敏感；根目录下没有 `characters/` 时把根目录内的卡文件当作角色卡。
 *
 * 加固点（0.11.4）：
 * - 去重：角色卡按 `(name, firstMes)`、世界书按书名、聊天按 `(角色, 标题)`，重复导入不再产生副本。
 * - `.zip` 整包：解压到缓存后走同一套导入逻辑；条目数/大小有上限，路径穿越条目直接丢弃。
 */
class StDirectoryImporter(
    private val context: Context,
    private val characters: CharacterRepository,
    private val worldBooks: WorldBookRepository,
) {

    data class Summary(
        var cards: Int = 0,
        var cardsFailed: Int = 0,
        var cardsSkipped: Int = 0,
        var worldBooks: Int = 0,
        var worldBooksFailed: Int = 0,
        var worldBooksSkipped: Int = 0,
        var chats: Int = 0,
        var chatsSkipped: Int = 0,
    ) {
        val total: Int get() = cards + worldBooks + chats
    }

    private data class Node(val documentId: String, val name: String?, val isDir: Boolean)
    private data class ImportFile(val name: String, val uri: Uri)
    private data class ChatFile(val folder: String, val title: String, val uri: Uri)

    /** 导入 SAF 目录树。返回 null 表示选中的目录不可读（权限被撤销等）。 */
    suspend fun import(treeUri: Uri): Summary? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext null
        val rootChildren = children(resolver, treeUri, rootId) ?: return@withContext null

        val charactersDir = rootChildren.firstOrNull { it.isDir && it.name.equals("characters", true) }
        val cardNodes = charactersDir
            ?.let { children(resolver, treeUri, it.documentId) }
            ?.filter { !it.isDir && isCardFile(it.name) }
            ?: rootChildren.filter { !it.isDir && isCardFile(it.name) }
        val cards = cardNodes.mapNotNull { node ->
            node.name?.let { ImportFile(it, documentUri(treeUri, node.documentId)) }
        }

        val worldsDir = rootChildren.firstOrNull { it.isDir && it.name.equals("worlds", true) }
        val worlds = worldsDir
            ?.let { children(resolver, treeUri, it.documentId) }
            .orEmpty()
            .filter { !it.isDir && it.name?.endsWith(".json", true) == true }
            .mapNotNull { node -> node.name?.let { ImportFile(it, documentUri(treeUri, node.documentId)) } }

        val chatsDir = rootChildren.firstOrNull { it.isDir && it.name.equals("chats", true) }
        val chats = if (chatsDir == null) {
            emptyList()
        } else {
            children(resolver, treeUri, chatsDir.documentId).orEmpty()
                .filter { it.isDir }
                .flatMap { dir ->
                    val folder = dir.name ?: return@flatMap emptyList()
                    children(resolver, treeUri, dir.documentId).orEmpty()
                        .filter { !it.isDir && it.name?.endsWith(".jsonl", true) == true }
                        .map { file ->
                            ChatFile(
                                folder = folder,
                                title = file.name?.removeSuffix(".jsonl").orEmpty(),
                                uri = documentUri(treeUri, file.documentId),
                            )
                        }
                }
        }

        importAll(cards, worlds, chats)
    }

    /** 导入 ST 导出的 `.zip` 整包（解压到缓存后复用同一套导入逻辑）。 */
    suspend fun importZip(zipUri: Uri): Summary? = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val tempZip = File(context.cacheDir, "st-import-$stamp.zip")
        val root = File(context.cacheDir, "st-import-$stamp")
        try {
            val copied = runCatching {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    tempZip.outputStream().use { out ->
                        copyLimited(input, out, MAX_ZIP_BYTES)
                    }
                } ?: false
            }.getOrDefault(false)
            if (!copied) return@withContext null

            root.mkdirs()
            if (!unzip(tempZip, root)) return@withContext null

            val base = findImportRoot(root)
            val cardFiles = (dirNamed(base, "characters")?.listFiles()
                ?: base.listFiles())
                ?.filter { !it.isDirectory && isCardFile(it.name) }
                .orEmpty()
                .map { ImportFile(it.name, Uri.fromFile(it)) }

            val worlds = (dirNamed(base, "worlds")?.listFiles() ?: emptyArray())
                .filter { !it.isDirectory && it.name.endsWith(".json", true) }
                .map { ImportFile(it.name, Uri.fromFile(it)) }

            val chats = (dirNamed(base, "chats")?.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .flatMap { dir ->
                    (dir.listFiles() ?: emptyArray())
                        .filter { !it.isDirectory && it.name.endsWith(".jsonl", true) }
                        .map { ChatFile(dir.name, it.name.removeSuffix(".jsonl"), Uri.fromFile(it)) }
                }

            importAll(cardFiles, worlds, chats)
        } finally {
            runCatching { tempZip.delete() }
            runCatching { root.deleteRecursively() }
        }
    }

    private suspend fun importAll(
        cards: List<ImportFile>,
        worlds: List<ImportFile>,
        chats: List<ChatFile>,
    ): Summary {
        val summary = Summary()

        // 1) 角色卡：按 (name, firstMes) 去重
        val existingCards = runCatching { characters.observeCharacters().first() }
            .getOrDefault(emptyList())
            .map { it.name.trim() to it.firstMes.trim() }
            .toHashSet()
        for (file in cards) {
            val key = cardKey(file.uri)
            if (key != null && key in existingCards) {
                summary.cardsSkipped++
                continue
            }
            when (runCatching { characters.importCard(file.uri) }.getOrNull()) {
                is ImportOutcome.Success -> {
                    summary.cards++
                    if (key != null) existingCards += key
                }
                else -> summary.cardsFailed++
            }
        }

        // 2) 世界书：按书名去重
        val existingWorlds = runCatching { worldBooks.observeAll().first() }
            .getOrDefault(emptyList())
            .map { it.name.trim().lowercase() }
            .toHashSet()
        for (file in worlds) {
            val name = file.name.removeSuffix(".json")
            if (name.trim().lowercase() in existingWorlds) {
                summary.worldBooksSkipped++
                continue
            }
            when (runCatching { worldBooks.import(file.uri) }.getOrNull()) {
                is WorldBookImportOutcome.Success -> {
                    summary.worldBooks++
                    existingWorlds += name.trim().lowercase()
                }
                else -> summary.worldBooksFailed++
            }
        }

        // 3) 聊天：chats/<角色名>/*.jsonl；按角色名匹配，按 (角色, 标题) 去重
        if (chats.isNotEmpty()) {
            val byName = characters.observeCharacters().first().associateBy { it.name }
            val seenTitles = mutableSetOf<Pair<Long, String>>()
            // 已有会话也要参与去重：重复选中同一份 ST 目录/zip 时不应再产生同名会话。
            val existingTitlesByCharacter = mutableMapOf<Long, MutableSet<String>>()
            for (chat in chats) {
                val character = byName[chat.folder]
                    ?: byName.entries.firstOrNull { it.key.equals(chat.folder, true) }?.value
                if (character == null) {
                    summary.chatsSkipped++
                    continue
                }
                val key = character.id to chat.title
                val existingTitles = existingTitlesByCharacter.getOrPut(character.id) {
                    runCatching {
                        characters.observeConversations(character.id).first()
                            .map { it.title.trim() }
                            .toMutableSet()
                    }.getOrDefault(mutableSetOf())
                }
                if (key in seenTitles || chat.title.trim() in existingTitles) {
                    summary.chatsSkipped++
                    continue
                }
                val result = runCatching {
                    characters.importChatJsonl(
                        characterId = character.id,
                        uri = chat.uri,
                        title = chat.title,
                    )
                }.getOrNull()
                if (result is CharacterRepository.ChatImportOutcome.Imported) {
                    summary.chats++
                    seenTitles += key
                    existingTitles += chat.title.trim()
                } else {
                    summary.chatsSkipped++
                }
            }
        }

        TeaTalkLog.i(
            "Import",
            "st.dir cards=${summary.cards}/${summary.cardsFailed}/skip=${summary.cardsSkipped} " +
                "worlds=${summary.worldBooks}/${summary.worldBooksFailed}/skip=${summary.worldBooksSkipped} " +
                "chats=${summary.chats}/${summary.chatsSkipped}",
        )
        return summary
    }

    /** 读卡解析出 `(name, firstMes)` 作为去重键；读不到/解析失败返回 null。 */
    private fun cardKey(uri: Uri): Pair<String, String>? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_CARD_BYTES.toInt()) }
            ?: return@runCatching null
        val imported = CardParser.parse(bytes)
        imported.card.data.name.trim() to imported.card.data.firstMes.trim()
    }.getOrNull()

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > max) break
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, max: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) return true
            total += read
            if (total > max) return false
            out.write(buf, 0, read)
        }
    }

    private fun unzip(zipFile: File, target: File): Boolean {
        var count = 0
        var total = 0L
        return runCatching {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    count++
                    if (count > MAX_ZIP_ENTRIES) return false
                    val name = entry.name.replace('\\', '/')
                    val outFile = File(target, name)
                    if (!outFile.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var bytes = 0L
                            while (true) {
                                val read = zis.read(buf)
                                if (read < 0) break
                                bytes += read
                                total += read
                                if (bytes > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_BYTES) return false
                                out.write(buf, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun findImportRoot(root: File): File {
        if (dirNamed(root, "characters") != null || dirNamed(root, "chats") != null ||
            dirNamed(root, "worlds") != null
        ) {
            return root
        }
        val dirs = root.listFiles()?.filter { it.isDirectory && it.name != "__MACOSX" }.orEmpty()
        if (dirs.size == 1) return findImportRoot(dirs.first())
        return root
    }

    private fun dirNamed(parent: File, name: String): File? =
        parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, true) }

    /** 列出某个目录的子项；查询失败返回 null（权限问题）。 */
    private fun children(resolver: ContentResolver, treeUri: Uri, parentDocumentId: String): List<Node>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Node(
                                documentId = cursor.getString(idIndex),
                                name = cursor.getString(nameIndex),
                                isDir = cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                            ),
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun isCardFile(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".json") || lower.endsWith(".charx")
    }

    private companion object {
        const val MAX_CARD_BYTES = 64L * 1024 * 1024
        const val MAX_ZIP_BYTES = 256L * 1024 * 1024
        const val MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 5000
    }
}
