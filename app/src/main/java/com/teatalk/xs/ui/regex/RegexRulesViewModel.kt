package com.teatalk.xs.ui.regex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.AppContainer
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.RegexEngine
import com.teatalk.xs.data.RegexRule
import com.teatalk.xs.data.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** V1 正则脚本管理：列表 + 增删改 + 即时预览。 */
class RegexRulesViewModel(private val repository: CharacterRepository) : ViewModel() {

    val rules: StateFlow<List<RegexRule>?> = repository.observeRegexRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun save(rule: RegexRule) {
        if (rule.pattern.isBlank()) {
            _message.value = UiText.of(R.string.regex_pattern_required)
            return
        }
        viewModelScope.launch {
            val error = RegexEngine.validate(rule.pattern, rule.flags)
            if (error != null) {
                _message.value = UiText.of(R.string.regex_invalid, rule.name.ifBlank { rule.pattern }, error)
                return@launch
            }
            runCatching { repository.saveRegexRule(rule) }
                .onSuccess { _message.value = UiText.of(R.string.regex_saved) }
                .onFailure { _message.value = UiText.of(R.string.regex_save_failed) }
        }
    }

    fun toggle(rule: RegexRule, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.saveRegexRule(rule.copy(enabled = enabled)) }
        }
    }

    fun delete(rule: RegexRule) {
        viewModelScope.launch {
            runCatching { repository.deleteRegexRule(rule.id) }
                .onSuccess { _message.value = UiText.of(R.string.regex_deleted) }
                .onFailure { _message.value = UiText.of(R.string.regex_delete_failed) }
        }
    }

    fun preview(rule: RegexRule, sample: String): String =
        if (sample.isBlank()) "" else RegexEngine.applyOne(sample, rule.copy(enabled = true))

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RegexRulesViewModel(app.container.characterRepository) as T
            }
        }
    }
}
