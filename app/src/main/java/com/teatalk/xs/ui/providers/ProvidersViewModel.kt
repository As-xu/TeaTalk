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
import kotlinx.coroutines.launch

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

    fun maskedKey(id: String): String? = repository.maskedKey(id)

    fun apiKeyOf(id: String): String? = repository.apiKey(id)

    fun consumeMessage() {
        _message.value = null
    }

    fun save(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        models: List<String>,
    ) {
        if (name.isBlank()) {
            _message.value = UiText.of(R.string.providers_name_required)
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            _message.value = UiText.of(R.string.providers_invalid_url)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val providerId = repository.save(
                id = id,
                name = name,
                baseUrl = baseUrl,
                apiFormat = "openai",
                defaultModel = model,
                models = models,
                apiKey = apiKey,
            )
            if (_activeId.value == null) _activeId.value = providerId
            _busy.value = false
            _message.value = UiText.providerSaved(name)
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

    fun testConnection(baseUrl: String, apiKey: String, onResult: (UiText) -> Unit) {
        if (apiKey.isBlank()) {
            onResult(UiText.of(R.string.providers_no_key))
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = client.listModels(baseUrl, apiKey)
            _busy.value = false
            onResult(
                result.fold(
                    onSuccess = { UiText.providerTestOk(it.size) },
                    onFailure = { UiText.providerTestFail(it.message ?: "error") },
                ),
            )
        }
    }

    fun fetchModels(
        baseUrl: String,
        apiKey: String,
        onDone: (List<String>) -> Unit,
        onError: (UiText) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            onError(UiText.of(R.string.providers_no_key))
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val result = client.listModels(baseUrl, apiKey)
            _busy.value = false
            result.fold(
                onSuccess = { onDone(it) },
                onFailure = { onError(UiText.providerTestFail(it.message ?: "error")) },
            )
        }
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProvidersViewModel(app.container.providerRepository, app.container.modelListClient)
            }
        }
    }
}
