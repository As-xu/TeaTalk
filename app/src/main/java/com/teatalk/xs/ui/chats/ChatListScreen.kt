package com.teatalk.xs.ui.chats

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.CharacterRepository
import com.teatalk.xs.data.db.ConversationWithLastMessage
import com.teatalk.xs.data.db.MessageSearchHit
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.components.SearchPill
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.teatalk.xs.ui.theme.TypeScale

class ChatListViewModel(
    private val repository: CharacterRepository,
    /** 0.26.0：成人内容开关关闭时，隐藏带 nsfw 标签的会话。 */
    private val allowNsfw: () -> Boolean = { true },
) : ViewModel() {
    val chats: StateFlow<List<ConversationWithLastMessage>?> = repository.observeChats()
        .map { list -> if (allowNsfw()) list.filterNot { it.hasNsfw } else list }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val deleted: StateFlow<CharacterRepository.DeletedConversation?> = DeletedConversationStore.deleted

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchHits = MutableStateFlow<List<MessageSearchHit>>(emptyList())
    val searchHits: StateFlow<List<MessageSearchHit>> = _searchHits.asStateFlow()

    private var searchJob: Job? = null

    fun setSearching(on: Boolean) {
        _searching.value = on
        if (!on) {
            searchJob?.cancel()
            _searchQuery.value = ""
            _searchHits.value = emptyList()
        }
    }

    /** 输入即搜（200ms 去抖），只查消息正文；空串直接清空结果。 */
    fun onQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchHits.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(200)
            _searchHits.value = repository.searchMessages(trimmed, limit = 40, allowNsfw = allowNsfw())
        }
    }

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
            initializer {
                ChatListViewModel(
                    repository = app.container.characterRepository,
                    allowNsfw = { app.container.appPrefs.nsfwAllowed },
                )
            }
        }
    }
}

/** 会话列表：QQ 首页同构（自己头像 + 搜索 + 会话行）。 */
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat: (Long, Long?) -> Unit,
    onGoCharacters: () -> Unit,
) {
    val allChats by viewModel.chats.collectAsState()
    // 只展示真正有消息的会话（迁移会为每个角色建默认会话，空会话不占列表）
    val chats = allChats?.filter { it.lastContent != null }
    val searching by viewModel.searching.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val hits by viewModel.searchHits.collectAsState()
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
                Text("茶", fontSize = TypeScale.s20, color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chatlist_title), fontSize = TypeScale.s19, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.chatlist_subtitle), fontSize = TypeScale.s11_5, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = { viewModel.setSearching(true) }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search), tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = onGoCharacters) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.characters_import_cta), tint = cs.onSurfaceVariant, modifier = Modifier.size(23.dp))
            }
        }
        if (searching) {
            ChatSearchField(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onClose = { viewModel.setSearching(false) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            Box(Modifier.padding(horizontal = 20.dp).clickable { viewModel.setSearching(true) }) {
                SearchPill(stringResource(R.string.chatlist_search_hint))
            }
        }
        Spacer(Modifier.height(6.dp))

        val list = chats
        val trimmedQuery = query.trim()
        when {
            list == null -> Unit
            searching && trimmedQuery.isNotEmpty() -> {
                val conversationHits = list.filter { it.matchesSearch(trimmedQuery) }
                if (conversationHits.isEmpty() && hits.isEmpty()) {
                    SearchEmpty(trimmedQuery)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (conversationHits.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.chatlist_section_conversations)) }
                            items(conversationHits, key = { "c" + it.conversation.id }) { chat ->
                                ChatRow(chat, onClick = { onOpenChat(chat.conversation.id, null) })
                            }
                        }
                        if (hits.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.chatlist_section_messages)) }
                            items(hits, key = { "m" + it.messageId }) { hit ->
                                MessageHitRow(hit, onClick = { onOpenChat(hit.conversationId, hit.messageId) })
                            }
                        }
                    }
                }
            }
            searching -> SearchEmpty(trimmedQuery)
            list.isEmpty() -> EmptyChats(onGoCharacters)
            else -> {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.chatlist_section_title), fontSize = TypeScale.s13, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(pluralStringResource(R.plurals.chatlist_waiting, list.size, list.size), fontSize = TypeScale.s12, color = cs.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.conversation.id }) { chat ->
                        ChatRow(chat, onClick = { onOpenChat(chat.conversation.id, null) })
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

/** 会话是否命中关键词（标题、角色名、最后一条消息）。 */
private fun ConversationWithLastMessage.matchesSearch(query: String): Boolean =
    conversation.title.contains(query, ignoreCase = true) ||
        characterName.contains(query, ignoreCase = true) ||
        lastContent?.contains(query, ignoreCase = true) == true

@Composable
private fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(cs.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.chatlist_search_message_hint),
                    fontSize = TypeScale.s13_5,
                    color = cs.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = TypeScale.s13_5, color = cs.onBackground),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chatlist_search_close), tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = TypeScale.s13,
        fontWeight = FontWeight.Medium,
        color = cs.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun MessageHitRow(hit: MessageSearchHit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(avatarPath = hit.characterAvatar, name = hit.characterName, size = 40.dp, corner = 14.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.conversationTitle.ifBlank { hit.characterName },
                fontSize = TypeScale.s13_5,
                fontWeight = FontWeight.Medium,
                color = cs.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                previewText(hit.content, 80),
                fontSize = TypeScale.s12,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Hairline(insetStart = 72)
}

@Composable
private fun SearchEmpty(query: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (query.isEmpty()) stringResource(R.string.chatlist_search_hint)
            else stringResource(R.string.chatlist_search_empty, query),
            fontSize = TypeScale.s13,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatRow(chat: ConversationWithLastMessage, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = buildString {
        append(chat.conversation.title.ifBlank { chat.characterName })
        chat.lastContent?.let { append(", ").append(previewText(it, 40)) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
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
                        fontSize = TypeScale.s15,
                        fontWeight = FontWeight.Medium,
                        color = cs.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chat.conversation.parentConversationId != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chatlist_branch_badge),
                            fontSize = TypeScale.s10,
                            color = cs.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cs.primaryContainer)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        chat.lastAt?.let { formatChatTime(it) } ?: "",
                        fontSize = TypeScale.s11,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chat.lastContent?.let { previewText(it) } ?: stringResource(R.string.chatlist_last_placeholder),
                    fontSize = TypeScale.s12_5,
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
            Text("茶", fontSize = TypeScale.s28, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.chatlist_empty_title), fontSize = TypeScale.s17, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.chatlist_empty_body),
            fontSize = TypeScale.s13,
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
            Text(stringResource(R.string.chatlist_empty_cta), fontSize = TypeScale.s14, color = cs.onPrimary, fontWeight = FontWeight.Medium)
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

/** 列表预览：去掉 Markdown 标记，只留第一段可读文本（审阅 P1：列表不该露出 ** 和 #）。 */
private fun previewText(raw: String, maxChars: Int = 80): String {
    val firstLine = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
        ?: return raw.take(maxChars)
    val cleaned = firstLine
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^\\s*[>*+-]\\s+"), "")
        .replace(Regex("^\\s*\\d+\\.\\s+"), "")
        .replace("**", "")
        .replace("*", "")
        .replace("`", "")
        .replace("~~", "")
        .trim()
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars).trimEnd() + "…"
}
