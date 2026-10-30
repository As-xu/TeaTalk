package com.teatalk.xs.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * M4-4：把 SillyTavern 的 `data/default-user` 目录整包导入。
 *
 * 只处理三类内容（其余如 settings/themes/presets 由各自入口负责，不在这里静默吞掉）：
 * - `characters/` 下的 `.png` / `.json` / `.charx` → 角色卡
 * - `worlds/` 下的 `.json` → 独立世界书
 * - `chats/<角色名>/` 下的 `.jsonl` → 该角色的聊天记录（按角色名匹配；匹配不到的计入跳过）
 *
 * 目录名大小写不敏感；根目录下没有 `characters/` 时，把根目录内的卡文件当作角色卡
 * （方便用户只导角色、或直接选 characters 目录）。
 *
 * 实现说明：不引第三方依赖，走 `DocumentsContract` 查询 SAF 树
 * （`documentfile` 库要额外拉依赖，本机网络不稳；这里只需要「列子项 + 子项名字/是否目录」）。
 */
class StDirectoryImporter(
    private val context: Context,
    private val characters: CharacterRepository,
    private val worldBooks: WorldBookRepository,
) {

    data class Summary(
        var cards: Int = 0,
        var cardsFailed: Int = 0,
        var worldBooks: Int = 0,
        var worldBooksFailed: Int = 0,
        var chats: Int = 0,
        var chatsSkipped: Int = 0,
    ) {
        val total: Int get() = cards + worldBooks + chats
    }

    private data class Node(val documentId: String, val name: String?, val isDir: Boolean)

    /**
     * 导入整包。返回 null 表示**选中的目录不可读**（权限被撤销等）；
     * 目录可读但内容为空时返回全 0 的 Summary。
     */
    suspend fun import(treeUri: Uri): Summary? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext null
        val summary = Summary()

        val rootChildren = children(resolver, treeUri, rootId) ?: return@withContext null

        // 1) 角色卡
        val charactersDir = rootChildren.firstOrNull { it.isDir && it.name.equals("characters", true) }
        val cardNodes = charactersDir
            ?.let { children(resolver, treeUri, it.documentId) }
            ?.filter { !it.isDir && isCardFile(it.name) }
            ?: rootChildren.filter { !it.isDir && isCardFile(it.name) }
        for (node in cardNodes) {
            val uri = documentUri(treeUri, node.documentId)
            when (runCatching { characters.importCard(uri) }.getOrNull()) {
                is ImportOutcome.Success -> summary.cards++
                else -> summary.cardsFailed++
            }
        }

        // 2) 世界书
        val worldsDir = rootChildren.firstOrNull { it.isDir && it.name.equals("worlds", true) }
        val worldNodes = worldsDir?.let { children(resolver, treeUri, it.documentId) }.orEmpty()
            .filter { !it.isDir && it.name?.endsWith(".json", true) == true }
        for (node in worldNodes) {
            val uri = documentUri(treeUri, node.documentId)
            when (runCatching { worldBooks.import(uri) }.getOrNull()) {
                is WorldBookImportOutcome.Success -> summary.worldBooks++
                else -> summary.worldBooksFailed++
            }
        }

        // 3) 聊天：chats/<角色名>/*.jsonl；按角色名匹配
        val chatsDir = rootChildren.firstOrNull { it.isDir && it.name.equals("chats", true) }
        if (chatsDir != null) {
            val byName = characters.observeCharacters().first().associateBy { it.name }
            for (dir in children(resolver, treeUri, chatsDir.documentId).orEmpty().filter { it.isDir }) {
                val folderName = dir.name ?: continue
                val files = children(resolver, treeUri, dir.documentId).orEmpty()
                    .filter { !it.isDir && it.name?.endsWith(".jsonl", true) == true }
                val character = byName[folderName]
                if (character == null) {
                    summary.chatsSkipped += files.size
                    continue
                }
                for (file in files) {
                    val title = file.name?.removeSuffix(".jsonl").orEmpty()
                    val result = runCatching {
                        characters.importChatJsonl(
                            characterId = character.id,
                            uri = documentUri(treeUri, file.documentId),
                            title = title,
                        )
                    }.getOrNull()
                    if (result != null) summary.chats++ else summary.chatsSkipped++
                }
            }
        }

        TeaTalkLog.i(
            "Import",
            "st.dir cards=${summary.cards}/${summary.cardsFailed} worlds=${summary.worldBooks}/${summary.worldBooksFailed} chats=${summary.chats}/${summary.chatsSkipped}",
        )
        summary
    }

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
}
