package com.teatalk.xs.data.update

import android.content.Context
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.BuildConfig
import com.teatalk.xs.data.model.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 0.26.0：更新检查的状态机（检查 → 有新版 → 下载 → 拉起安装）。
 *
 * 24 小时节流存在 AppPrefs，`force = true` 时无视（用户手动点的「检查更新」）。
 */
class UpdateManager(
    private val context: Context,
    private val prefs: AppPrefs,
    private val client: UpdateClient = UpdateClient(),
    private val installer: UpdateInstaller = UpdateInstaller(context),
) {

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class UpToDate(val current: String) : State

        data class Available(val release: UpdateClient.Release) : State

        data class Downloading(val downloaded: Long, val total: Long, val percent: Int) : State

        data class Ready(val version: String, val file: File) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pending: UpdateClient.Release? = null

    /** 用户取消下载后，后台线程的进度回调不能再把状态写回 Downloading。 */
    @Volatile
    private var cancelled = false

    /** 上次检查时间（给「关于」页展示）。 */
    fun lastCheckAt(): Long = prefs.lastUpdateCheckAt

    /**
     * 检查更新。请求跑在 manager 自己的 scope 上，页面销毁不会把协程取消掉；
     * 即便调用方取消了，`finally` 也会把 `Checking` 复位，避免状态机永久卡死。
     */
    fun check(force: Boolean = false) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        val now = System.currentTimeMillis()
        val last = prefs.lastUpdateCheckAt
        if (!force && last in 1L until now && now - last < THROTTLE_MS) {
            TeaTalkLog.i("Update", "update.throttled", "sinceMs" to (now - last))
            return
        }
        _state.value = State.Checking
        prefs.lastUpdateCheckAt = now
        scope.launch {
            try {
                runCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TeaTalkLog.w("Update", "update.check_fail", "error" to e.message)
                _state.value = State.Failed(e.message ?: "check failed")
            } finally {
                if (_state.value is State.Checking) _state.value = State.Idle
            }
        }
    }

    private suspend fun runCheck() {
        // 只有显式开启的调试构建才读自定义更新源，避免残留值或同机 App 改写渠道。
        val feed = if (BuildConfig.FEED_OVERRIDE) prefs.updateFeedUrl else null
        when (val result = client.latest(feed)) {
            is UpdateClient.Result.Found -> {
                if (client.isNewer(result.release.version)) {
                    TeaTalkLog.i("Update", "update.available", "version" to result.release.version)
                    pending = result.release
                    _state.value = State.Available(result.release)
                } else {
                    _state.value = State.UpToDate(current = result.release.version)
                }
            }

            UpdateClient.Result.None -> _state.value = State.UpToDate(current = "")
            is UpdateClient.Result.Failed -> _state.value = State.Failed(result.reason)
        }
    }

    fun download() {
        val release = (_state.value as? State.Available)?.release ?: return
        if (job?.isActive == true) return
        pending = release
        cancelled = false
        _state.value = State.Downloading(downloaded = 0L, total = release.apkSize, percent = 0)
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                installer.download(
                    release = release,
                    onProgress = { downloaded, total ->
                        if (!cancelled && _state.value is State.Downloading) {
                            val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                            _state.value = State.Downloading(downloaded, total, percent)
                        }
                    },
                )
            }
            if (!isActive) return@launch
            result.onSuccess { file ->
                _state.value = State.Ready(release.version, file)
            }.onFailure { error ->
                if (error is CancellationException) {
                    _state.value = State.Available(release)
                } else {
                    _state.value = State.Failed(error.message ?: "download failed")
                }
            }
        }
    }

    /** 下载中点「关闭」：取消下载并回到「发现新版本」。 */
    fun cancelDownload() {
        if (_state.value !is State.Downloading) return
        val release = pending
        cancelled = true
        job?.cancel()
        job = null
        // 取消时 withContext 会抛 CancellationException，launch 里的收尾不保证执行，
        // 所以这里直接把状态退回「发现新版本」，并把未完成的 .part 交给下载器自己删。
        _state.value = release?.let { State.Available(it) } ?: State.Idle
    }

    fun install() {
        val ready = _state.value as? State.Ready ?: return
        installer.install(ready.file).onFailure {
            _state.value = State.Failed(it.message ?: "install failed")
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    companion object {
        const val THROTTLE_MS = 24L * 60 * 60 * 1000
    }
}
