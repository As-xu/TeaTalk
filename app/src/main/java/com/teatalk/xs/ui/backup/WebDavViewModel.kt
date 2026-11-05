package com.teatalk.xs.ui.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.WebDavBackup
import com.teatalk.xs.data.net.WebDavException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 0.24.0：云备份页状态（配置草稿 + 远端列表 + 忙碌/提示）。 */
data class WebDavUiState(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val dir: String = "",
    val hasPassword: Boolean = false,
    val maskedPassword: String? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
    val backups: List<WebDavBackup.RemoteBackup> = emptyList(),
    val loaded: Boolean = false,
    val restoreTarget: WebDavBackup.RemoteBackup? = null,
)

class WebDavViewModel(
    private val context: Context,
    private val webDav: WebDavBackup,
) : ViewModel() {

    private val _state = MutableStateFlow(
        WebDavUiState(
            url = webDav.configOrNull()?.baseUrl.orEmpty(),
            user = "",
            dir = "",
            hasPassword = webDav.hasPassword(),
            maskedPassword = webDav.maskedPassword(),
        ),
    )
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = webDav.configOrNull()
            _state.update {
                it.copy(
                    url = prefs?.baseUrl.orEmpty(),
                    user = prefs?.username.orEmpty(),
                    dir = webDav.remoteDir(),
                    hasPassword = webDav.hasPassword(),
                    maskedPassword = webDav.maskedPassword(),
                )
            }
            // 没配过服务器时不要一进页面就弹「读取失败」。
            if (webDav.hasConfig()) refresh()
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value) }
    fun onUserChange(value: String) = _state.update { it.copy(user = value) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }
    fun onDirChange(value: String) = _state.update { it.copy(dir = value) }

    /** 保存草稿；地址为空时给出提示并返回 false，调用方据此决定要不要继续。 */
    fun save(): Boolean {
        val s = _state.value
        if (s.url.isBlank()) {
            _state.update { it.copy(message = UiText.of(R.string.webdav_need_config)) }
            return false
        }
        webDav.saveConfig(s.url.trim(), s.user.trim(), s.password)
        webDav.saveDir(s.dir)
        val serverChanged = webDav.configOrNull()?.baseUrl.orEmpty() != s.url.trim()
        _state.update {
            it.copy(
                password = "",
                hasPassword = webDav.hasPassword(),
                maskedPassword = webDav.maskedPassword(),
                dir = webDav.remoteDir(),
                // 换了服务器就先把旧列表清掉：否则列表里的条目属于上一台服务器，
                // 点「恢复」会按新地址去取同名文件。
                backups = if (serverChanged) emptyList() else it.backups,
                message = UiText.of(R.string.webdav_saved),
            )
        }
        refresh()
        return true
    }

    fun clearConfig() {
        webDav.clearConfig()
        _state.update {
            it.copy(
                url = "",
                user = "",
                password = "",
                dir = "",
                hasPassword = false,
                maskedPassword = null,
                backups = emptyList(),
                loaded = false,
                message = UiText.of(R.string.webdav_cleared),
            )
        }
    }

    fun test() {
        if (_state.value.busy) return
        if (!save()) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = webDav.test()
            _state.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { UiText.of(R.string.webdav_test_ok) },
                        onFailure = { UiText.of(R.string.webdav_test_failed, it.describe()) },
                    ),
                )
            }
        }
    }

    fun backup() {
        if (_state.value.busy) return
        if (!save()) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = webDav.backupNow()
            result.fold(
                onSuccess = { done ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = UiText.of(R.string.webdav_backup_ok, done.name, formatSize(done.bytes.toLong())),
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(busy = false, message = UiText.of(R.string.webdav_backup_failed, error.describe()))
                    }
                },
            )
        }
    }

    fun refresh() {
        if (!webDav.hasConfig()) return
        viewModelScope.launch {
            val result = webDav.list()
            _state.update {
                it.copy(
                    backups = result.getOrDefault(emptyList()),
                    loaded = true,
                    message = it.message ?: result.exceptionOrNull()
                        ?.let { e -> UiText.of(R.string.webdav_list_failed, e.describe()) },
                )
            }
        }
    }

    fun askRestore(target: WebDavBackup.RemoteBackup) {
        if (_state.value.busy) return
        _state.update { it.copy(restoreTarget = target) }
    }

    fun dismissRestore() = _state.update { it.copy(restoreTarget = null) }

    fun confirmRestore() {
        if (_state.value.busy) return
        val target = _state.value.restoreTarget ?: return
        _state.update { it.copy(restoreTarget = null, busy = true, message = null) }
        viewModelScope.launch {
            val result = webDav.restore(target)
            _state.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { s ->
                            UiText.of(R.string.webdav_restore_ok, s.cards, s.worldBooks, s.chats, s.skipped)
                        },
                        onFailure = { e -> UiText.of(R.string.webdav_restore_failed, e.describe()) },
                    ),
                )
            }
            refresh()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 把异常翻成一句人话：已知的内部失败给本地化文案，其余显示原始 message。 */
    private fun Throwable.describe(): String {
        val webDav = this as? WebDavException
        if (webDav != null && (webDav.status == 401 || webDav.status == 403)) {
            return context.getString(R.string.webdav_err_auth)
        }
        return when (message) {
            "backup too large" -> context.getString(R.string.webdav_err_too_large)
            "bad backup file" -> context.getString(R.string.webdav_err_bad_zip)
            "empty body", "get failed" -> context.getString(R.string.webdav_err_download)
            "webdav not configured" -> context.getString(R.string.webdav_need_config)
            else -> message?.take(120) ?: this::class.simpleName.orEmpty()
        }
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { WebDavViewModel(app, app.container.webDavBackup) }
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
