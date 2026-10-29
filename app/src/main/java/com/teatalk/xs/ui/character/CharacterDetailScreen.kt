package com.teatalk.xs.ui.character

import com.teatalk.xs.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CardView
import com.teatalk.xs.data.CardViewParser
import com.teatalk.xs.data.CharacterRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.teatalk.xs.data.db.WorldBookEntity
import com.teatalk.xs.data.WorldBookRepository
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.heightIn
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.components.PillTag
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.teatalk.xs.ui.theme.TypeScale

data class CharacterDetailState(
    val character: CharacterEntity? = null,
    val view: CardView = CardView(),
)

class CharacterDetailViewModel(
    private val repository: CharacterRepository,
    private val worldBookRepository: WorldBookRepository,
    private val characterId: Long,
) : ViewModel() {

    val state = repository.observeCharacter(characterId)
        .map { character ->
            CharacterDetailState(
                character = character,
                view = character?.let { CardViewParser.parse(it.cardJson) } ?: CardView(),
            )
        }
        .flowOn(Dispatchers.Default) // 大卡 JSON 解析别占用主线程
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharacterDetailState())

    /** 世界书库（详情页绑定对话框的数据源）。 */
    val books: StateFlow<List<WorldBookEntity>?> = worldBookRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 角色默认绑定的世界书 id（已保存值）。 */
    private val _linkedWorldBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val linkedWorldBookIds: StateFlow<Set<Long>> = _linkedWorldBookIds

    /** 对话框内的草稿；`null` 表示对话框关闭。取消不回写 [linkedWorldBookIds]。 */
    private val _draftWorldBookIds = MutableStateFlow<Set<Long>?>(null)
    val draftWorldBookIds: StateFlow<Set<Long>?> = _draftWorldBookIds

    /** 保存回执（Snackbar 用），消费后置空。 */
    private val _worldBookMessage = MutableStateFlow<String?>(null)
    val worldBookMessage: StateFlow<String?> = _worldBookMessage

    init {
        viewModelScope.launch {
            _linkedWorldBookIds.value = repository.linkedWorldBookIds(characterId).toSet()
        }
    }

    fun openWorldBooks() {
        _draftWorldBookIds.value = _linkedWorldBookIds.value
    }

    fun cancelWorldBooks() {
        _draftWorldBookIds.value = null
    }

    fun toggleWorldBook(id: Long) {
        val current = _draftWorldBookIds.value ?: return
        _draftWorldBookIds.value = if (id in current) current - id else current + id
    }

    /** 把草稿写库；成功后同步已保存值并关对话框。 */
    fun saveWorldBooks() {
        val draft = _draftWorldBookIds.value ?: return
        viewModelScope.launch {
            repository.setCharacterWorldBooks(characterId, draft.sorted())
            _linkedWorldBookIds.value = draft
            _draftWorldBookIds.value = null
            _worldBookMessage.value = "saved"
        }
    }

    fun consumeWorldBookMessage() {
        _worldBookMessage.value = null
    }

    /** 删除角色（连同聊天记录），成功后回调让页面退出。 */
    fun delete(onDeleted: () -> Unit) {
        val character = state.value.character ?: return
        viewModelScope.launch {
            repository.delete(character)
            onDeleted()
        }
    }

    companion object {
        fun factory(app: TeaTalkApp, characterId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { CharacterDetailViewModel(app.container.characterRepository, app.container.worldBookRepository, characterId) }
        }
    }
}

/** 角色详情：大封面 + 简介/开场白/设定分区（QQ 资料卡风格）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    viewModel: CharacterDetailViewModel,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
    onOpenWorldBooks: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val books by viewModel.books.collectAsState()
    val linkedIds by viewModel.linkedWorldBookIds.collectAsState()
    val draftIds by viewModel.draftWorldBookIds.collectAsState()
    val worldBookMessage by viewModel.worldBookMessage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val worldBookSavedText = stringResource(R.string.detail_world_book_saved)
    // 展示的是「生效条目数」：卡内嵌 + 已绑定世界书的条目数之和。
    val linkedEntryCount = books.orEmpty().filter { it.id in linkedIds }.sumOf { it.entryCount }
    var confirmDelete by remember { mutableStateOf(false) }
    val character = state.character
    val view = state.view
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val avatarFile = character?.avatarPath?.let { File(context.filesDir, it) }

    LaunchedEffect(worldBookMessage) {
        if (worldBookMessage != null) {
            snackbar.showSnackbar(worldBookSavedText)
            viewModel.consumeWorldBookMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().background(cs.background)) {
        item {
            Box(Modifier.fillMaxWidth().height(380.dp).background(cs.surfaceContainerHigh)) {
                if (avatarFile != null && avatarFile.exists()) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = character?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 底部渐变压暗，保证名字/标签可读
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xE614100C),
                        ),
                    ),
                )
                // 顶部压暗一层，保证状态栏图标在亮色封面上也看得清
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                1f to Color.Transparent,
                            ),
                        ),
                )
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    HeroIconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_delete_character), tint = Color.White)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                    Text(
                        character?.name ?: view.name.ifBlank { stringResource(R.string.character_fallback) },
                        fontSize = TypeScale.s26,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (view.creator.isNotBlank()) append(stringResource(R.string.detail_by_author, view.creator))
                            if (view.version.isNotBlank()) append("v${view.version} · ")
                            append(character?.cardSource ?: "")
                        }.trimEnd(' ', '·'),
                        fontSize = TypeScale.s12,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    if (view.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            view.tags.take(4).forEach { tag ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(tag, fontSize = TypeScale.s11, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(cs.primary)
                        .clickable(onClick = onStartChat),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.detail_start_chat),
                        fontSize = TypeScale.s15,
                        fontWeight = FontWeight.Medium,
                        color = cs.onPrimary,
                        maxLines = 1,
                    )
                }
                OutlinedPill(
                    text = pluralStringResource(
                        R.plurals.detail_world_book_entries,
                        view.worldBook.size + linkedEntryCount,
                        view.worldBook.size + linkedEntryCount,
                    ),
                    enabled = true,
                    onClick = { viewModel.openWorldBooks() },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
                if (view.description.isNotBlank()) {
                    SectionCard(stringResource(R.string.detail_section_desc)) { BodyText(view.description) }
                }
                if (view.firstMes.isNotBlank()) {
                    SectionCard(stringResource(R.string.detail_section_first_mes)) { BodyText(view.firstMes) }
                }
                if (view.personality.isNotBlank() || view.scenario.isNotBlank()) {
                    SectionCard(stringResource(R.string.detail_section_details)) {
                        if (view.personality.isNotBlank()) {
                            FieldLine(stringResource(R.string.detail_field_personality), view.personality)
                        }
                        if (view.scenario.isNotBlank()) {
                            FieldLine(stringResource(R.string.detail_field_scenario), view.scenario)
                        }
                    }
                }
                if (view.creatorNotes.isNotBlank()) {
                    SectionCard(stringResource(R.string.detail_section_notes)) { BodyText(view.creatorNotes) }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
    )
    }

    val draft = draftIds
    if (draft != null) {
        val booksList = books.orEmpty()
        val savedIds = linkedIds
        val dirty = draft != savedIds
        val boundEntries = booksList.filter { it.id in draft }.sumOf { it.entryCount }
        ModalBottomSheet(
            onDismissRequest = { viewModel.cancelWorldBooks() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.detail_world_book_summary,
                        view.worldBook.size + boundEntries,
                        view.worldBook.size + boundEntries,
                        pluralStringResource(R.plurals.detail_books_bound, draft.size, draft.size),
                    ),
                    fontSize = TypeScale.s15,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                // 空库是「第一优先级出口」：放在滚动区之外，避免被内嵌条目挤到下面。
                if (booksList.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.detail_world_book_empty),
                            fontSize = TypeScale.s12,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.cancelWorldBooks()
                            onOpenWorldBooks()
                        }) { Text(stringResource(R.string.detail_world_book_import)) }
                    }
                }
                // weight(1f, fill=false)：横屏 / 矮视口时列表让出空间，操作行始终可见。
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (view.worldBook.isNotEmpty()) {
                        item {
                            Text(
                                pluralStringResource(R.plurals.detail_world_book_builtin, view.worldBook.size, view.worldBook.size),
                                fontSize = TypeScale.s12,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        itemsIndexed(view.worldBook) { _, entry ->
                            Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                                Text(
                                    entry.comment.ifBlank { entry.keys.joinToString(", ") },
                                    fontSize = TypeScale.s13,
                                    color = cs.onSurface,
                                )
                                if (entry.content.isNotBlank()) {
                                    Text(
                                        entry.content.take(80),
                                        fontSize = TypeScale.s11,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        Text(
                            stringResource(
                                R.string.detail_world_book_library,
                                pluralStringResource(R.plurals.detail_books_total, booksList.size, booksList.size),
                                pluralStringResource(R.plurals.detail_books_selected, draft.size, draft.size),
                            ),
                            fontSize = TypeScale.s12,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    items(booksList, key = { it.id }) { book ->
                        val checked = book.id in draft
                        val entryCountText = pluralStringResource(
                            R.plurals.worldbook_entry_count,
                            book.entryCount,
                            book.entryCount,
                        )
                        // 整行可点 + 单一「复选框」语义节点（Checkbox 自身清空语义，避免 TalkBack 双节点）。
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { viewModel.toggleWorldBook(book.id) },
                                )
                                // 合并子节点语义：TalkBack 读「书名 + 条目数 + 勾选状态」，而不是空白复选框。
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${'$'}{book.name}，${'$'}entryCountText"
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                            )
                            Text(
                                book.name,
                                fontSize = TypeScale.s14,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                            Text(
                                pluralStringResource(R.plurals.worldbook_entry_count, book.entryCount, book.entryCount),
                                fontSize = TypeScale.s11,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.cancelWorldBooks() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.saveWorldBooks() },
                        enabled = dirty,
                    ) { Text(stringResource(R.string.edit_save)) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.characters_delete_title)) },
            text = { Text(stringResource(R.string.characters_delete_confirm, character?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HeroIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

@Composable
private fun OutlinedPill(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .border(1.dp, cs.outline, RoundedCornerShape(23.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = TypeScale.s14,
            color = if (enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = TypeScale.s13_5, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        fontSize = TypeScale.s13_5,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FieldLine(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 2.dp)) {
        Text("$label：", fontSize = TypeScale.s13, color = cs.onSurfaceVariant)
        Text(value, fontSize = TypeScale.s13_5, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}

