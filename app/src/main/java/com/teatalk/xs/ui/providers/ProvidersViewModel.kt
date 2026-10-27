package com.teatalk.xs.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.db.ModelProviderEntity
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.net.ModelListClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 编辑弹层自己的状态：busy/note/models 都由 VM 持有，避免回调把成功失败塞回 Composable。 */
data class ProvidersDialogState(
    val busy: Boolean = false,
    val note: UiText? = null,
    val noteIsError: Boolean = false,
    val models: List<String> = emptyList(),
    /** 自增计数：Screen 观察它来关闭弹层（保存成功）。 */
    val savedTick: Int = 0,
)

/** 我的 → 模型与接口：多 Provider 的增删改、默认项、测试连接与拉模型。 */
class ProvidersViewModel(
    private val repository: ProviderRepository,
    private val client: ModelListClient,
) : ViewModel() {

    val providers: StateFlow<List<ModelProviderEntity>> = repository.providers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _activeId = MutableStateFlow(repository.activeProviderId())
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    private val _dialog = MutableStateFlow(ProvidersDialogState())
    val dialog: StateFlow<ProvidersDialogState> = _dialog.asStateFlow()

    fun maskedKey(id: String): String? = repository.maskedKey(id)

    fun consumeMessage() {
        _message.value = null
    }

    /** 打开/切换编辑弹层时清掉上一次的提示与模型列表。 */
    fun resetDialog() {
        _dialog.value = ProvidersDialogState()
    }

    fun save(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ) {
        if (_dialog.value.busy) return
        val trimmedName = name.trim()
        val trimmedUrl = baseUrl.trim().trimEnd('/')
        when {
            trimmedName.isEmpty() -> {
                _dialog.update { it.copy(note = UiText.of(R.string.providers_name_required), noteIsError = true) }
                return
            }

            !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://") -> {
                _dialog.update { it.copy(note = UiText.of(R.string.providers_invalid_url), noteIsError = true) }
                return
            }
        }
        viewModelScope.launch {
            _dialog.update { it.copy(busy = true, note = null) }
            val providerId = repository.save(
                id = id,
                name = trimmedName,
                baseUrl = trimmedUrl,
                apiFormat = "openai",
                defaultModel = model.trim(),
                models = _dialog.value.models,
                apiKey = apiKey.trim().ifBlank { null },
            )
            if (_activeId.value == null) _activeId.value = providerId
            _dialog.update { it.copy(busy = false, savedTick = it.savedTick + 1) }
            _message.value = UiText.providerSaved(trimmedName)
        }
    }

    fun delete(provider: ModelProviderEntity) {
        viewModelScope.launch {
            repository.delete(provider.id)
            _activeId.value = repository.activeProviderId()
            _message.value = UiText.providerDeleted(provider.name)
        }
    }

    fun setActive(provider: ModelProviderEntity) {
        repository.setActive(provider.id)
        _activeId.value = provider.id
        _message.value = UiText.providerActivated(provider.name)
    }

    fun testConnection(providerId: String?, baseUrl: String, enteredKey: String) {
        val key = resolveKey(providerId, enteredKey)
        if (key == null) {
            _dialog.update { it.copy(note = UiText.of(R.string.providers_no_key), noteIsError = true) }
            return
        }
        if (_dialog.value.busy) return
        viewModelScope.launch {
            _dialog.update { it.copy(busy = true, note = null) }
            val result = client.listModels(baseUrl.trim(), key)
            _dialog.update {
                it.copy(
                    busy = false,
                    note = result.fold(
                        onSuccess = { models -> UiText.providerTestOk(models.size) },
                        onFailure = { error -> UiText.providerTestFail(error.message ?: "error") },
                    ),
                    noteIsError = result.isFailure,
                )
            }
        }
    }

    fun fetchModels(providerId: String?, baseUrl: String, enteredKey: String) {
        val key = resolveKey(providerId, enteredKey)
        if (key == null) {
            _dialog.update { it.copy(note = UiText.of(R.string.providers_no_key), noteIsError = true) }
            return
        }
        if (_dialog.value.busy) return
        viewModelScope.launch {
            _dialog.update { it.copy(busy = true, note = null) }
            val result = client.listModels(baseUrl.trim(), key)
            _dialog.update {
                result.fold(
                    onSuccess = { models ->
                        it.copy(
                            busy = false,
                            models = models,
                            note = UiText.of(R.string.providers_models_loaded, models.size),
                            noteIsError = false,
                        )
                    },
                    onFailure = { error ->
                        it.copy(
                            busy = false,
                            note = UiText.providerTestFail(error.message ?: "error"),
                            noteIsError = true,
                        )
                    },
                )
            }
        }
    }

    /**
     * 用哪个 key 去测试：优先用户刚输入的；否则回落到已存的。
     *
     * 这里只把解密后的 key 放在 VM 局部变量里传给网络层，
     * 不再像之前那样通过 `apiKeyOf()` 把明文 key 交给 Composable。
     */
    private fun resolveKey(providerId: String?, enteredKey: String): String? {
        val typed = enteredKey.trim()
        if (typed.isNotEmpty()) return typed
        val id = providerId ?: return null
        return repository.apiKey(id)?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProvidersViewModel(app.container.providerRepository, app.container.modelListClient)
            }
        }
    }
}
