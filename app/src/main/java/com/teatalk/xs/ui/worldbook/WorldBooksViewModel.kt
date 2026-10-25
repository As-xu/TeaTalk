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
import com.teatalk.xs.data.db.WorldBookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorldBooksViewModel(
    private val repository: WorldBookRepository,
) : ViewModel() {

    val books: StateFlow<List<WorldBookEntity>?> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

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
            initializer { WorldBooksViewModel(app.container.worldBookRepository) }
        }
    }
}
