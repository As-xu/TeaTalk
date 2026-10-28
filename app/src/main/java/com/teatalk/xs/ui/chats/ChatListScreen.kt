package com.teatalk.xs.ui.chats

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import com.teatalk.xs.data.DeletedConversationStore
import kotlinx.coroutines.launch
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.ConversationWithLastMessage
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.components.SearchPill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatListViewModel(private val repository: CharacterRepository) : ViewModel() {
    val chats: StateFlow<List<ConversationWithLastMessage>?> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val deleted: StateFlow<CharacterRepository.DeletedConversation?> = DeletedConversationStore.deleted

    /** 列表页撤销删除（聊天页删当前会话后会跳回这里）。 */
    fun undoDelete() {
        val snapshot = DeletedConversationStore.deleted.value ?: return
        viewModelScope.launch {
            repository.restoreConversation(snapshot)
            DeletedConversationStore.clear()
        }
    }

    fun clearDeleted() = DeletedConversationStore.clear()

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatListViewModel(app.container.characterRepository) }
        }
    }
}

/** 会话列表：QQ 首页同构（自己头像 + 搜索 + 会话行）。 */
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (Long) -> Unit,
    onGoCharacters: () -> Unit,
) {
    val allChats by viewModel.chats.collectAsState()
    // 只展示真正有消息的会话（迁移会为每个角色建默认会话，空会话不占列表）
    val chats = allChats?.filter { it.lastContent != null }
    val cs = MaterialTheme.colorScheme
    val deleted by viewModel.deleted.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(deleted) {
        val snapshot = deleted ?: return@LaunchedEffect
        val title = snapshot.conversation.title.ifBlank {
            context.getString(R.string.chat_conversation_untitled)
        }
        val message = if (snapshot.messages.size == 1) {
            context.getString(R.string.chat_conversation_deleted_one, title)
        } else {
            context.getString(R.string.chat_conversation_deleted_count, title, snapshot.messages.size)
        }
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = context.getString(R.string.chat_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearDeleted()
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // 顶部：同 QQ 的一行身份区（M0 用 App 身份，M1 接用户设置）
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("茶", fontSize = 20.sp, color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chatlist_title), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.chatlist_subtitle), fontSize = 11.5.sp, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search), tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = onGoCharacters) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.characters_import_cta), tint = cs.onSurfaceVariant, modifier = Modifier.size(23.dp))
            }
        }
        SearchPill(stringResource(R.string.chatlist_search_hint), Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))

        val list = chats
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyChats(onGoCharacters)
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.chatlist_section_title), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(pluralStringResource(R.plurals.chatlist_waiting, list.size, list.size), fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.conversation.id }) { chat ->
                        ChatRow(chat, onClick = { onOpenChat(chat.conversation.id) })
                    }
                }
            }
        }
    }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        )
    }
}

@Composable
private fun ChatRow(chat: ConversationWithLastMessage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterAvatar(
                avatarPath = chat.characterAvatar,
                name = chat.characterName,
                size = 52.dp,
                corner = 16.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.conversation.title.ifBlank { chat.characterName },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        chat.lastAt?.let { formatChatTime(it) } ?: "",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chat.lastContent ?: stringResource(R.string.chatlist_last_placeholder),
                    fontSize = 12.5.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Hairline(insetStart = 84)
    }
}

@Composable
private fun EmptyChats(onGoCharacters: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("茶", fontSize = 28.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.chatlist_empty_title), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.chatlist_empty_body),
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(cs.primary)
                .clickable(onClick = onGoCharacters)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text(stringResource(R.string.chatlist_empty_cta), fontSize = 14.sp, color = cs.onPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

/** 今天显示 HH:mm，昨天显示「昨天」，更早显示 MM-dd。 */
@Composable
private fun formatChatTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val now = Instant.now().atZone(zone)
    return when {
        t.toLocalDate() == now.toLocalDate() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        t.toLocalDate() == now.toLocalDate().minusDays(1) -> stringResource(R.string.time_yesterday)
        else -> t.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
}



