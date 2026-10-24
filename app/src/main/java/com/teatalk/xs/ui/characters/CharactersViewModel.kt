package com.teatalk.xs.ui.characters

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.ImportOutcome
import com.teatalk.xs.data.db.CharacterEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CharactersViewModel(
    private val repository: CharacterRepository,
) : ViewModel() {

    val characters: StateFlow<List<CharacterEntity>> = repository.observeCharacters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 一次性提示（snackbar）。消费后置空。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importCard(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                _message.value = when (val outcome = repository.importCard(uri)) {
                    is ImportOutcome.Success -> buildString {
                        append("已导入「${outcome.name}」")
                        if (outcome.warnings.isNotEmpty()) {
                            append("（${outcome.warnings.size} 条兼容提示）")
                        }
                    }
                    is ImportOutcome.Failure -> outcome.message
                }
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(character: CharacterEntity) {
        viewModelScope.launch {
            repository.delete(character)
            _message.value = "已删除「${character.name}」"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { CharactersViewModel(app.container.characterRepository) }
        }
    }
}
