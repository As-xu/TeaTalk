package com.teatalk.xs.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teatalk.xs.data.UiText
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.TranslationRepository
import com.teatalk.xs.data.TranslationTarget
import com.teatalk.xs.data.db.ModelProviderEntity
import com.teatalk.xs.data.model.AppPrefs
import com.teatalk.xs.data.model.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 翻译设置：目标语言、自动翻译开关、翻译接口、缓存管理。
 *
 * 翻译接口为空表示「跟随聊天接口」——多数人一个接口就够，配置只在需要省钱时才拆开。
 */
class TranslationViewModel(
    private val repository: TranslationRepository,
    private val providers: ProviderRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    val providerRows: StateFlow<List<ModelProviderEntity>> = providers.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _targetLang = MutableStateFlow(prefs.translationTargetLang)
    val targetLang: StateFlow<String> = _targetLang

    private val _autoTranslate = MutableStateFlow(prefs.autoTranslateReplies)
    val autoTranslate: StateFlow<Boolean> = _autoTranslate

    private val _providerId = MutableStateFlow(prefs.translationProviderId)
    val providerId: StateFlow<String?> = _providerId

    private val _cacheSize = MutableStateFlow(0)
    val cacheSize: StateFlow<Int> = _cacheSize

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message

    init {
        refreshCacheSize()
    }

    fun setTargetLang(code: String) {
        prefs.translationTargetLang = code
        _targetLang.value = code
    }

    fun setAutoTranslate(enabled: Boolean) {
        prefs.autoTranslateReplies = enabled
        _autoTranslate.value = enabled
    }

    /** null = 跟随聊天接口。 */
    fun setProvider(id: String?) {
        prefs.translationProviderId = id
        _providerId.value = id
    }

    fun providerLabel(): String = providers.activeProviderId()
        ?.let { id -> providerRows.value.firstOrNull { it.id == id }?.name }
        ?: ""

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refreshCacheSize()
            _message.value = UiText.of(R.string.translate_cache_cleared)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun targetTarget(): TranslationTarget = TranslationTarget.of(_targetLang.value)

    private fun refreshCacheSize() {
        viewModelScope.launch { _cacheSize.value = repository.cacheSize() }
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TranslationViewModel(
                    app.container.translationRepository,
                    app.container.providerRepository,
                    app.container.appPrefs,
                ) as T
        }
    }
}
