package com.teatalk.xs.ui.worldbook

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.WorldBookImportOutcome
import com.teatalk.xs.data.WorldBookRepository
import com.teatalk.xs.data.TranslationRepository
import com.teatalk.xs.data.TranslationResult
import com.teatalk.xs.data.toUiText
import com.teatalk.xs.data.db.WorldBookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorldBooksViewModel(
    private val repository: WorldBookRepository,
    private val translation: TranslationRepository,
) : ViewModel() {

    val books: StateFlow<List<WorldBookEntity>?> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** 条目译文，key = "$bookId:$uid"。 */
    private val _entryTranslations = MutableStateFlow<Map<String, String>>(emptyMap())
    val entryTranslations: StateFlow<Map<String, String>> = _entryTranslations

    /** 正在翻译的条目 key。 */
    private val _entryTranslating = MutableStateFlow<Set<String>>(emptySet())
    val entryTranslating: StateFlow<Set<String>> = _entryTranslating

    private val _translateMessage = MutableStateFlow<UiText?>(null)
    val translateMessage: StateFlow<UiText?> = _translateMessage

    /** 翻译一个世界书条目的正文；只做展示，磁盘缓存交给 TranslationRepository。 */
    fun translateEntry(bookId: Long, entry: WorldInfoEntry, force: Boolean = false) {
        val key = entryKey(bookId, entry.uid)
        if (entry.content.isBlank() || key in _entryTranslating.value) return
        if (!force && _entryTranslations.value.containsKey(key)) return
        _entryTranslating.value = _entryTranslating.value + key
        viewModelScope.launch {
            when (val result = translation.translate(entry.content)) {
                is TranslationResult.Ok -> _entryTranslations.value = _entryTranslations.value + (key to result.text)
                is TranslationResult.Failed -> _translateMessage.value = result.failure.toUiText()
            }
            _entryTranslating.value = _entryTranslating.value - key
        }
    }

    fun showOriginalEntry(bookId: Long, entry: WorldInfoEntry) {
        _entryTranslations.value = _entryTranslations.value - entryKey(bookId, entry.uid)
    }

    fun consumeTranslateMessage() {
        _translateMessage.value = null
    }

    private fun entryKey(bookId: Long, uid: Int): String = "$bookId:$uid"

    /** 条目解析按需做（列表不解析，点开才解析全文）。 */
    fun entriesOf(book: WorldBookEntity): List<WorldInfoEntry> = repository.entriesOf(book)

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                _message.value = when (val outcome = repository.import(uri)) {
                    is WorldBookImportOutcome.Success -> UiText.worldBookImported(outcome.name, outcome.entryCount)
                    is WorldBookImportOutcome.Failure -> outcome.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    /** M4-2：保存一条条目的编辑结果。 */
    fun saveEntry(bookId: Long, entry: WorldInfoEntry) {
        viewModelScope.launch {
            val ok = repository.updateEntry(bookId, entry)
            _message.value = UiText.of(
                if (ok) com.teatalk.xs.R.string.worldbook_entry_saved else com.teatalk.xs.R.string.worldbook_entry_save_failed,
            )
        }
    }

    fun delete(book: WorldBookEntity) {
        viewModelScope.launch {
            repository.delete(book)
            _message.value = UiText.worldBookDeleted(book.name)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorldBooksViewModel(app.container.worldBookRepository, app.container.translationRepository)
            }
        }
    }
}
