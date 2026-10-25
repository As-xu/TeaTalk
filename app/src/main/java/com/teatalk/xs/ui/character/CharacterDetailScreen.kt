package com.teatalk.xs.ui.character

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CardView
import com.teatalk.xs.data.CardViewParser
import com.teatalk.xs.data.CharacterRepository
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

/** 角色详情：大封面 + 简介/开场白/设定分区（QQ 资料卡风格）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    viewModel: CharacterDetailViewModel,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showWorldBook by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val character = state.character
    val view = state.view
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val avatarFile = character?.avatarPath?.let { File(context.filesDir, it) }

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    HeroIconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除角色", tint = Color.White)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                    Text(
                        character?.name ?: view.name.ifBlank { "角色" },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (view.creator.isNotBlank()) append("作者 ${view.creator} · ")
                            if (view.version.isNotBlank()) append("v${view.version} · ")
                            append(character?.cardSource ?: "")
                        }.trimEnd(' ', '·'),
                        fontSize = 12.sp,
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
                                    Text(tag, fontSize = 11.sp, color = Color.White)
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
                        "开始聊天",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onPrimary,
                    )
                }
                OutlinedPill(
                    text = "世界书 ${view.worldBook.size}",
                    enabled = view.worldBook.isNotEmpty(),
                    onClick = { showWorldBook = true },
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp)) {
                if (view.description.isNotBlank()) {
                    SectionCard("简介") { BodyText(view.description) }
                }
                if (view.firstMes.isNotBlank()) {
                    SectionCard("开场白") { BodyText(view.firstMes) }
                }
                if (view.personality.isNotBlank() || view.scenario.isNotBlank()) {
                    SectionCard("设定") {
                        if (view.personality.isNotBlank()) {
                            FieldLine("性格", view.personality)
                        }
                        if (view.scenario.isNotBlank()) {
                            FieldLine("场景", view.scenario)
                        }
                    }
                }
                if (view.creatorNotes.isNotBlank()) {
                    SectionCard("创作者注释") { BodyText(view.creatorNotes) }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (showWorldBook) {
        AlertDialog(
            onDismissRequest = { showWorldBook = false },
            title = { Text("世界书（${view.worldBook.size} 条）") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(view.worldBook) { _, entry ->
                        Column {
                            Text(
                                entry.comment.ifBlank { entry.keys.joinToString("、") },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface,
                            )
                            if (entry.keys.isNotEmpty()) {
                                Text(
                                    entry.keys.joinToString("、"),
                                    fontSize = 11.sp,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            Text(
                                entry.content.take(120),
                                fontSize = 12.sp,
                                color = cs.onSurfaceVariant.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWorldBook = false }) { Text("关闭") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除角色卡") },
            text = { Text("确定删除「${character?.name}」？聊天记录也会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
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
private fun OutlinedPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(23.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
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
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        fontSize = 13.5.sp,
        lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FieldLine(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 2.dp)) {
        Text("$label：", fontSize = 13.sp, color = cs.onSurfaceVariant)
        Text(value, fontSize = 13.5.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}

