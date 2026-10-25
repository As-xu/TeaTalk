package com.teatalk.xs.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.MessageEntity
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.Hairline
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatViewModel(
    private val repository: CharacterRepository,
    private val characterId: Long,
) : ViewModel() {

    val character: StateFlow<CharacterEntity?> = repository.observeCharacter(characterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(characterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.ensureGreeting(characterId) }
    }

    /** @param onSent 落库成功后才回调；UI 用它决定何时清空输入框。 */
    fun send(text: String, onSent: () -> Unit = {}) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val character = repository.find(characterId) ?: return@launch
            repository.sendMessage(character, text)
            onSent()
        }
    }

    companion object {
        fun factory(app: TeaTalkApp, characterId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(app.container.characterRepository, characterId) }
        }
    }
}

/** 聊天页：QQ 风（头像行头 + 左右气泡 + 底部输入条）。回复仍是本地占位。 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val character by viewModel.character.collectAsState()
    val messages by viewModel.messages.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        ChatHeader(character, onBack)
        Hairline()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        messages.firstOrNull()?.let { "今天 " + timeOf(it.createdAt) } ?: "开始一段角色扮演",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                if (message.role == "user") {
                    UserBubble(message.content)
                } else {
                    AssistantBubble(message.content, character)
                }
            }
        }

        // 输入条：+ / 输入框 / 表情 / 发送
        Column(Modifier.fillMaxWidth().imePadding()) {
            Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Add, contentDescription = "更多", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainerHigh)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 14.5.sp, color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        maxLines = 4,
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text("说点什么…", fontSize = 14.5.sp, color = cs.onSurfaceVariant)
                            }
                            inner()
                        },
                    )
                    Icon(
                        Icons.Outlined.SentimentSatisfied,
                        contentDescription = "表情",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank()
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (canSend) cs.primary else cs.surfaceContainerHigh)
                        .clickable(enabled = canSend) {
                            val text = input
                            input = ""
                            viewModel.send(text)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(character: CharacterEntity?, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = cs.onBackground)
        }
        CharacterAvatar(avatarPath = character?.avatarPath, name = character?.name ?: "", size = 36.dp, corner = 12.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                character?.name ?: "角色",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                maxLines = 1,
            )
            Text("在线 · 角色扮演模式", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssistantBubble(text: String, character: CharacterEntity?) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        CharacterAvatar(avatarPath = character?.avatarPath, name = character?.name ?: "", size = 32.dp, corner = 10.dp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .widthIn(max = 268.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Text(text, fontSize = 14.5.sp, lineHeight = 22.sp, color = cs.onSurface)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 268.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp))
                .background(cs.primary)
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Text(text, fontSize = 14.5.sp, lineHeight = 22.sp, color = cs.onPrimary)
        }
    }
}

private fun timeOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
