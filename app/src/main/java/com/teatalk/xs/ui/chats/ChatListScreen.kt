package com.teatalk.xs.ui.chats

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.CharacterWithLastMessage
import com.teatalk.xs.ui.components.CharacterAvatar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class ChatListViewModel(repository: CharacterRepository) : ViewModel() {

    /** null = 首批数据未到（不渲染空态，避免闪烁）。 */
    val chats: StateFlow<List<CharacterWithLastMessage>?> = repository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        fun factory(app: TeaTalkApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatListViewModel(app.container.characterRepository) }
        }
    }
}

/** 聊天 Tab：按最后一条消息倒序的会话列表（QQ/微信式），点行进聊天页。 */
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (Long) -> Unit,
    onGoCharacters: () -> Unit,
) {
    val chatsState = viewModel.chats.collectAsState()
    val chats: List<CharacterWithLastMessage>? = chatsState.value

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "聊天",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        when {
            // 首批数据未到：留白，别闪空态文案
            chats == null -> Unit
            chats.isEmpty() -> EmptyChats(onGoCharacters)
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(chats, key = { it.character.id }) { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.character.id) })
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: CharacterWithLastMessage, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            avatarPath = chat.character.avatarPath,
            name = chat.character.name,
            size = 50.dp,
            corner = 25.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(chat.lastAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = (chat.lastContent ?: "").replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyChats(onGoCharacters: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "还没有聊天记录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "先去「角色」导入一张角色卡，点开就能开始聊。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGoCharacters) { Text("去看看角色") }
        }
    }
}

/** 「今天 HH:mm，更早 M月d日」——M0 够用，做多语言时再换 DateFormat。 */
private fun formatTime(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        String.format("%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
    } else {
        "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日"
    }
}
