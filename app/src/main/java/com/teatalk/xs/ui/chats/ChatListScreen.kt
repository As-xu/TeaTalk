package com.teatalk.xs.ui.chats

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
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.CharacterWithLastMessage
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.components.SearchPill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatListViewModel(repository: CharacterRepository) : ViewModel() {
    val chats: StateFlow<List<CharacterWithLastMessage>?> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    val chats by viewModel.chats.collectAsState()
    val cs = MaterialTheme.colorScheme

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
                Text("茶话", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text("本地模式 · 无服务端", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = onGoCharacters) {
                Icon(Icons.Filled.Add, contentDescription = "导入角色卡", tint = cs.onSurfaceVariant, modifier = Modifier.size(23.dp))
            }
        }
        SearchPill("搜索角色 / 会话", Modifier.padding(horizontal = 20.dp))
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
                    Text("会话", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("${list.size} 位角色在等你", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.character.id }) { chat ->
                        ChatRow(chat, onClick = { onOpenChat(chat.character.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: CharacterWithLastMessage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterAvatar(
                avatarPath = chat.character.avatarPath,
                name = chat.character.name,
                size = 52.dp,
                corner = 16.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chat.character.name,
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
                    chat.lastContent ?: "还没有消息，点开聊一句吧",
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
        Text("还没有会话", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            "去「角色」里挑一张卡，点「开始聊天」",
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
            Text("去挑角色", fontSize = 14.sp, color = cs.onPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

/** 今天显示 HH:mm，昨天显示「昨天」，更早显示 MM-dd。 */
private fun formatChatTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val now = Instant.now().atZone(zone)
    return when {
        t.toLocalDate() == now.toLocalDate() -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
        t.toLocalDate() == now.toLocalDate().minusDays(1) -> "昨天"
        else -> t.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
}



