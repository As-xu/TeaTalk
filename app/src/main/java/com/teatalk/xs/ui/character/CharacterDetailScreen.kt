package com.teatalk.xs.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CardView
import com.teatalk.xs.data.CardViewParser
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.components.CharacterAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CharacterDetailState(
    val character: CharacterEntity? = null,
    val view: CardView = CardView(),
)

class CharacterDetailViewModel(
    private val repository: CharacterRepository,
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
            initializer { CharacterDetailViewModel(app.container.characterRepository, characterId) }
        }
    }
}

/** 角色详情页：头像 + 基本信息 + 卡片正文分段 + 世界书概览。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterDetailScreen(
    viewModel: CharacterDetailViewModel,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showWorldBook by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("角色详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除角色")
                    }
                },
            )
        },
    ) { padding ->
        val character = state.character
        if (character == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("角色不存在或已删除") }
            return@Scaffold
        }
        val view = state.view
        val sections = listOf(
            "设定" to view.description,
            "人设" to view.personality,
            "场景" to view.scenario,
            "开场白" to view.firstMes,
            "示例对话" to view.mesExample,
            "系统提示" to view.systemPrompt,
            "创作者注释" to view.creatorNotes,
        ).filter { it.second.isNotBlank() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(
                        avatarPath = character.avatarPath,
                        name = view.name.ifBlank { character.name },
                        size = 84.dp,
                        corner = 24.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = view.name.ifBlank { character.name },
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val byline = listOfNotNull(
                            view.creator.takeIf { it.isNotBlank() }?.let { "作者 $it" },
                            view.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            character.cardSource,
                        ).joinToString(" · ")
                        Text(
                            text = byline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (view.tags.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        view.tags.take(12).forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onStartChat, modifier = Modifier.weight(1f)) {
                        Text("开始聊天")
                    }
                    OutlinedButton(
                        onClick = { showWorldBook = true },
                        enabled = view.worldBook.isNotEmpty(),
                    ) {
                        Text("世界书 ${view.worldBook.size}")
                    }
                }
            }

            items(sections, key = { it.first }) { (title, body) ->
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showWorldBook) {
        WorldBookDialog(entries = state.view.worldBook, onDismiss = { showWorldBook = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除角色卡") },
            text = { Text("确定删除「${state.character?.name ?: ""}」？聊天记录也会一并删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete { onBack() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorldBookDialog(entries: List<WorldInfoEntry>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("世界书（${entries.size} 条）") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.uid }) { entry ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = entry.comment.ifBlank { entry.keys.joinToString("、").ifBlank { "（常量）" } },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = entry.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
