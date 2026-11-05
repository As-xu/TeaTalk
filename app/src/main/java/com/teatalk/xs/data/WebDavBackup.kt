package com.teatalk.xs.data

import android.content.Context
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.model.AppPrefs
import com.teatalk.xs.data.model.SecretStore
import com.teatalk.xs.data.net.WebDavClient
import com.teatalk.xs.data.net.WebDavConfig
import com.teatalk.xs.data.net.WebDavEntry
import com.teatalk.xs.data.net.WebDavException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 保留策略：远端最近 N 份、本地恢复前快照最近 M 份。纯函数，便于单测。 */
object WebDavRetention {

    const val REMOTE_KEEP = 10
    const val LOCAL_KEEP = 3

    /** 返回应删除的文件名（按名字倒序 = 新→旧，跳过前 keep 个）。 */
    fun stale(names: List<String>, keep: Int): List<String> =
        names.sortedDescending().drop(keep.coerceAtLeast(0))
}

/**
 * 0.24.0：WebDAV 云备份。
 *
 * 布局：`<根>/<webdavDir>/backup-yyyyMMdd-HHmmss.zip`，保留最近 10 份。
 * 备份是「整包上传」；恢复是「补缺导入」——同名角色卡 / 世界书 / 会话跳过，不覆盖、不删除已有数据。
 * 恢复前把当前数据导出到应用私有目录 `filesDir/webdav-snapshots/`（保留 3 份）作为排障留底。
 *
 * 不做自动合并：冲突策略需要逐条定义，超出本版范围。
 */
class WebDavBackup(
    private val context: Context,
    private val backups: BackupRepository,
    private val prefs: AppPrefs,
    private val secrets: SecretStore,
    private val client: WebDavClient = WebDavClient(),
) {

    data class RemoteBackup(
        val name: String,
        val path: String,
        val size: Long,
    )

    data class BackupResult(
        val name: String,
        val bytes: Int,
        val pruned: Int,
    )

    fun hasConfig(): Boolean = prefs.webDavUrl.isNotBlank()

    fun hasPassword(): Boolean = secrets.hasNamed(PASSWORD_KEY)

    fun maskedPassword(): String? = secrets.getNamed(PASSWORD_KEY)
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.length < 12) "****" else "****" + it.takeLast(4) }

    fun config(): WebDavConfig {
        val url = prefs.webDavUrl
        if (url.isBlank()) throw WebDavException("webdav not configured")
        return WebDavConfig(
            baseUrl = url,
            username = prefs.webDavUser,
            password = secrets.getNamed(PASSWORD_KEY).orEmpty(),
        )
    }

    fun configOrNull(): WebDavConfig? = runCatching { config() }.getOrNull()

    fun remoteDir(): String = prefs.webDavDir

    fun saveConfig(url: String, user: String, password: String) {
        prefs.webDavUrl = url
        prefs.webDavUser = user
        // 应用专用密码可能带首尾空格，不能 trim：trim 掉后 401 极难排查。
        if (password.isNotBlank()) secrets.putNamed(PASSWORD_KEY, password)
    }

    /** 清除配置：地址、用户名、远端目录与加密保存的密码一起删掉。 */
    fun clearConfig() {
        prefs.webDavUrl = ""
        prefs.webDavUser = ""
        prefs.webDavDir = ""
        secrets.removeNamed(PASSWORD_KEY)
    }

    fun saveDir(dir: String) {
        prefs.webDavDir = dir
    }

    suspend fun test(): Result<Unit> = runCatchingCooperative { client.test(config()) }
        .onFailure { TeaTalkLog.w("Net", "webdav.test_fail", "error" to it.message.orEmpty()) }

    suspend fun list(): Result<List<RemoteBackup>> = runCatchingCooperative {
        client.list(config(), prefs.webDavDir)
            .filter { !it.isCollection && it.name.startsWith("backup-") && it.name.endsWith(".zip") }
            .sortedByDescending { it.name }
            .map { RemoteBackup(name = it.name, path = it.path, size = it.size) }
    }.onFailure { TeaTalkLog.w("Net", "webdav.list_fail", "error" to it.message.orEmpty()) }

    suspend fun backupNow(): Result<BackupResult> = runCatchingCooperative {
        val cfg = config()
        val bytes = backups.export()
        val name = "backup-${stamp()}.zip"
        val dir = prefs.webDavDir
        client.makeDirs(cfg, dir)
        client.put(cfg, "$dir/$name", bytes)
        // 清理旧备份失败不该让整次备份报错：文件已经传上去了，提示失败会诱导用户重复上传。
        val pruned = runCatching { pruneRemote(cfg, dir) }
            .onFailure { TeaTalkLog.w("Net", "webdav.prune_fail", "error" to it.message.orEmpty()) }
            .getOrDefault(0)
        TeaTalkLog.i(
            "Net",
            "webdav.backup",
            "name" to name,
            "bytes" to bytes.size,
            "pruned" to pruned,
            "dir" to dir,
            "base" to cfg.redactedBase(),
        )
        BackupResult(name = name, bytes = bytes.size, pruned = pruned)
    }.onFailure { TeaTalkLog.w("Net", "webdav.backup_fail", "error" to it.message.orEmpty()) }

    /** 恢复：下载远端整包 → 校验 → 落本地快照 → 导入（快照写失败就不动库）。 */
    suspend fun restore(remote: RemoteBackup): Result<BackupRepository.Summary> = runCatchingCooperative {
        val cfg = config()
        // 1) 先下载并确认是合法 zip：坏包/404 时不必白做一次全量导出，也不会挤掉旧快照。
        val bytes = client.get(cfg, remote.path)
        if (!backups.canRestore(bytes)) throw WebDavException("bad backup file")
        // 2) 本地留底（失败就不继续，避免「远端也坏了、本地也没有」）。
        val snapshot = writeLocalSnapshot()
        TeaTalkLog.i("Net", "webdav.restore.begin", "remote" to remote.name, "snapshot" to snapshot.name)
        // 3) 导入。
        val summary = backups.restore(bytes) ?: throw WebDavException("bad backup file")
        TeaTalkLog.i(
            "Net",
            "webdav.restore.done",
            "cards" to summary.cards,
            "worldBooks" to summary.worldBooks,
            "chats" to summary.chats,
            "messages" to summary.messages,
            "skipped" to summary.skipped,
        )
        summary
    }.onFailure { TeaTalkLog.w("Net", "webdav.restore_fail", "error" to it.message.orEmpty()) }

    // ---- 内部 ----

    private suspend fun pruneRemote(cfg: WebDavConfig, dir: String): Int {
        val entries = client.list(cfg, dir)
            .filter { !it.isCollection && it.name.startsWith("backup-") && it.name.endsWith(".zip") }
        val stale = WebDavRetention.stale(entries.map { it.name }, WebDavRetention.REMOTE_KEEP)
        var pruned = 0
        stale.forEach { name ->
            val entry: WebDavEntry? = entries.firstOrNull { it.name == name }
            if (entry != null && client.delete(cfg, entry.path)) pruned++
        }
        return pruned
    }

    private suspend fun writeLocalSnapshot(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "webdav-snapshots").apply { mkdirs() }
        val file = File(dir, "snapshot-${stamp()}.zip")
        file.writeBytes(backups.export())
        // 只保留最近 3 份，避免「恢复前快照」把手机存储吃满。
        val stale = WebDavRetention.stale(dir.list()?.toList().orEmpty(), WebDavRetention.LOCAL_KEEP)
        stale.forEach { runCatching { File(dir, it).delete() } }
        file
    }

    // 带毫秒：同一秒内连续两次备份不会互相覆盖，字典序仍等于时间序。
    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    companion object {
        private const val PASSWORD_KEY = "webdav_password"
    }
}

/**
 * 与 [runCatching] 相同，但让协程取消穿透：取消不是「失败」，
 * 不能被翻成 Result.failure 后继续走后续流程。
 */
private inline fun <T> runCatchingCooperative(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
