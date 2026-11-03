package com.teatalk.xs.ui.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.db.QuickReplyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 0.19.0 快速回复管理：列表 + 增删改 + 启停。 */
class QuickRepliesViewModel(private val repository: CharacterRepository) : ViewModel() {

    val replies: StateFlow<List<QuickReplyEntity>?> = repository.observeQuickReplies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun save(row: QuickReplyEntity) {
        if (row.content.isBlank()) {
            _message.value = UiText.of(R.string.quick_reply_content_required)
            return
        }
        viewModelScope.launch {
            try {
                repository.saveQuickReply(row)
                _message.value = UiText.of(R.string.quick_reply_saved)
            } catch (t: Throwable) {
                _message.value = UiText.of(R.string.quick_reply_save_failed)
            }
        }
    }

    fun toggle(row: QuickReplyEntity, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.saveQuickReply(row.copy(enabled = enabled))
            } catch (t: Throwable) {
                _message.value = UiText.of(R.string.quick_reply_save_failed)
            }
        }
    }

    fun delete(row: QuickReplyEntity) {
        viewModelScope.launch {
            try {
                repository.deleteQuickReply(row.id)
                _message.value = UiText.of(R.string.quick_reply_deleted)
            } catch (t: Throwable) {
                _message.value = UiText.of(R.string.quick_reply_delete_failed)
            }
        }
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return QuickRepliesViewModel(app.container.characterRepository) as T
            }
        }
    }
}
