package com.teatalk.xs.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.teatalk.xs.data.ChatEngine
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.MessageEntity
import com.teatalk.xs.data.net.ChatEvent
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.Hairline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: CharacterRepository,
    private val engine: ChatEngine,
    private val characterId: Long,
) : ViewModel() {

    val character: StateFlow<CharacterEntity?> = repository.observeCharacter(characterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(characterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    /** 正在流式生成中的文本（不含已落库消息）。 */
    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<UiText?>(null)
    val notice: StateFlow<UiText?> = _notice.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch { repository.ensureGreeting(characterId) }
    }

    fun send(text: String) {
        if (_streaming.value || text.isBlank()) return
        _error.value = null
        job = launchStream { engine.reply(characterId, text) }
    }

    fun retry() {
        if (_streaming.value) return
        _error.value = null
        job = launchStream { engine.retry(characterId) }
    }

    /** 停止生成：把已经收到的半截文本落库，避免用户白等。 */
    fun stop() {
        val partial = _streamText.value.trim()
        job?.cancel()
        job = null
        viewModelScope.launch {
            if (partial.isNotEmpty()) {
                repository.addMessage(characterId, "assistant", partial)
            }
            _streaming.value = false
            _streamText.value = ""
            _notice.value = UiText.chatStopped()
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun launchStream(block: suspend () -> kotlinx.coroutines.flow.Flow<ChatEvent>): Job =
        viewModelScope.launch {
            _streaming.value = true
            _streamText.value = ""
            try {
                block().collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> _streamText.value += event.text
                        ChatEvent.Completed -> Unit
                        is ChatEvent.Failed -> _error.value = UiText.chatError(event.error)
                    }
                }
            } finally {
                _streaming.value = false
                _streamText.value = ""
            }
        }

    companion object {
        fun factory(app: TeaTalkApp, characterId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = app.container.characterRepository,
                    engine = app.container.chatEngine,
                    characterId = characterId,
                )
            }
        }
    }
}

/** 聊天页：QQ 风（头像行头 + 左右气泡 + 底部输入条）；M1 起接真实模型流式回复。 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val character by viewModel.character.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val streamText by viewModel.streamText.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(messages.size, streamText.length, streaming) {
        val count = messages.size + if (streaming) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    notice?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text.resolve(context))
            viewModel.consumeNotice()
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ChatHeader(character, onBack)
            Hairline()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            messages.firstOrNull()?.let {
                                stringResource(R.string.chat_today_header, timeOf(it.createdAt))
                            } ?: stringResource(R.string.chat_intro_default),
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
                if (streaming) {
                    item(key = "streaming") {
                        AssistantBubble(streamText, character, thinking = streamText.isEmpty())
                    }
                }
            }

            error?.let { err ->
                ErrorBanner(text = err.resolve(context), onRetry = viewModel::retry)
            }

            // 输入条：+ / 输入框 / 表情 / 发送·停止
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
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.more),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
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
                                    Text(
                                        stringResource(R.string.chat_input_hint),
                                        fontSize = 14.5.sp,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                                inner()
                            },
                        )
                        Icon(
                            Icons.Outlined.SentimentSatisfied,
                            contentDescription = stringResource(R.string.emoji),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (streaming) {
                        val stopLabel = stringResource(R.string.chat_stop)
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(cs.primary)
                                .clickable { viewModel.stop() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stopLabel,
                                tint = cs.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
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
                                contentDescription = stringResource(R.string.send),
                                tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
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
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = cs.onBackground,
            )
        }
        CharacterAvatar(avatarPath = character?.avatarPath, name = character?.name ?: "", size = 36.dp, corner = 12.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                character?.name ?: stringResource(R.string.character_fallback),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.chat_online_status), fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        IconButton(onClick = {}) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.5.sp, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.chat_retry),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onErrorContainer,
            modifier = Modifier.clickable { onRetry() },
        )
    }
}

@Composable
private fun AssistantBubble(text: String, character: CharacterEntity?, thinking: Boolean = false) {
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
            if (thinking) {
                Text(stringResource(R.string.chat_thinking), fontSize = 14.5.sp, color = cs.onSurfaceVariant)
            } else {
                Text(text, fontSize = 14.5.sp, lineHeight = 22.sp, color = cs.onSurface)
            }
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
