package com.teatalk.xs.ui.characters

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CardEdit
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.ImportOutcome
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.db.CharacterEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 卡库筛选分段（对应顶部三个 chip）。 */
enum class CharacterFilter { RECENT, ALL, FAVORITES }

class CharactersViewModel(
    private val repository: CharacterRepository,
) : ViewModel() {

    /** null = 还在从磁盘读第一批数据（不渲染空态，避免"还没有角色卡"闪一下）。 */
    private val characters: StateFlow<List<CharacterEntity>?> = repository.observeCharacters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(CharacterFilter.RECENT)
    val filter: StateFlow<CharacterFilter> = _filter.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /** 出现过的标签，按使用次数排序，最多 12 个（QQ 的「标签筛选」行）。 */
    val tags: StateFlow<List<String>> = characters
        .map { list ->
            list.orEmpty()
                .flatMap { parseTags(it.tagsJson) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(12)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 搜索 + 筛选 + 标签三个条件组合后的列表（null 仍表示加载中）。 */
    val cards: StateFlow<List<CharacterEntity>?> =
        combine(characters, _query, _filter, _selectedTag) { list, query, filter, tag ->
            list?.let { applyFilters(it, query, filter, tag) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 一次性提示（snackbar）。消费后置空。 */
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** 最近一次导入的兼容提示（snackbar 的「明细」），空表示没有。 */
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    init {
        // v1 → v2 迁移后给老卡回填标签（幂等，只处理空 tags 的行）
        viewModelScope.launch { repository.backfillTags() }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: CharacterFilter) {
        _filter.value = value
    }

    fun selectTag(tag: String?) {
        _selectedTag.value = tag
    }

    fun importCard(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                when (val outcome = repository.importCard(uri)) {
                    is ImportOutcome.Success -> {
                        _message.value = UiText.imported(outcome.name, outcome.warnings.size)
                        _warnings.value = outcome.warnings
                    }
                    is ImportOutcome.Failure -> {
                        _message.value = outcome.message
                        _warnings.value = emptyList()
                    }
                }
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(character: CharacterEntity) {
        viewModelScope.launch {
            repository.delete(character)
            _message.value = UiText.deleted(character.name)
        }
    }

    fun toggleFavorite(character: CharacterEntity) {
        viewModelScope.launch {
            val next = !character.favorite
            repository.setFavorite(character, next)
            _message.value = UiText.favorited(character.name, next)
        }
    }

    fun togglePinned(character: CharacterEntity) {
        viewModelScope.launch {
            val next = !character.pinned
            repository.setPinned(character, next)
            _message.value = UiText.pinned(character.name, next)
        }
    }

    fun saveCard(character: CharacterEntity, edit: CardEdit) {
        viewModelScope.launch {
            repository.updateCard(character, edit)
            _message.value = UiText.cardSaved(edit.name)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeWarnings() {
        _warnings.value = emptyList()
    }

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { CharactersViewModel(app.container.characterRepository) }
        }
    }
}

/** D6：把「最近/全部/收藏 + 关键词 + 标签」应用到列表；置顶永远排在最前。 */
internal fun applyFilters(
    all: List<CharacterEntity>,
    query: String,
    filter: CharacterFilter,
    tag: String?,
): List<CharacterEntity> {
    val keyword = query.trim()
    val filtered = all.asSequence()
        .filter { character ->
            when (filter) {
                CharacterFilter.FAVORITES -> character.favorite
                CharacterFilter.RECENT, CharacterFilter.ALL -> true
            }
        }
        .filter { character ->
            tag == null || tag in parseTags(character.tagsJson)
        }
        .filter { character ->
            keyword.isEmpty() ||
                character.name.contains(keyword, ignoreCase = true) ||
                parseTags(character.tagsJson).any { it.contains(keyword, ignoreCase = true) } ||
                character.cardJson.contains(keyword, ignoreCase = true)
        }
        .toList()

    val ordered = when (filter) {
        CharacterFilter.RECENT -> filtered.sortedByDescending { it.updatedAt.takeIf { t -> t > 0 } ?: it.createdAt }
        CharacterFilter.ALL -> filtered.sortedBy { it.name.lowercase() }
        CharacterFilter.FAVORITES -> filtered.sortedByDescending { it.updatedAt.takeIf { t -> t > 0 } ?: it.createdAt }
    }
    // 置顶置前，保持上面各分段的相对顺序
    return ordered.sortedByDescending { it.pinned }
}

/** `tagsJson` → 标签列表（坏数据返回空表，不炸 UI）。 */
internal fun parseTags(tagsJson: String): List<String> = runCatching {
    (Json.parseToJsonElement(tagsJson) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()
}.getOrDefault(emptyList())
