package com.teatalk.xs.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import com.teatalk.xs.data.MessageVariants
import com.teatalk.xs.data.ChatEngine
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.model.ActiveProvider
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.db.MessageEntity
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.teatalk.xs.data.net.ChatEvent
import com.teatalk.xs.ui.components.CharacterAvatar
import com.teatalk.xs.ui.components.MarkdownText
import com.teatalk.xs.ui.components.Hairline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: CharacterRepository,
    private val engine: ChatEngine,
    private val conversationId: Long,
) : ViewModel() {

    /** 当前会话（用于拿 characterId 与标题）。 */
    val conversation: StateFlow<ConversationEntity?> = repository.observeConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val character: StateFlow<CharacterEntity?> = conversation
        .map { it?.characterId }
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeCharacter(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 该角色下的全部会话（切换面板）。 */
    val conversations: StateFlow<List<ConversationEntity>> = conversation
        .map { it?.characterId }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeConversations(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(conversationId)
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

    /** 发送失败时要还给输入框的原文（NO_PROVIDER/NO_MODEL 等前置失败）。 */
    private val _restoreText = MutableStateFlow<String?>(null)
    val restoreText: StateFlow<String?> = _restoreText.asStateFlow()

    /**
     * 单飞行控制：同一时刻只允许一条生成任务。
     *
     * 之前用 `_streaming.value` 做守卫，但它是在协程里才置 true（赋值不即时），
     * 快速双击发送会启动两条流、各落一条用户消息；停止也因为没有 join 而与
     * 取消中的协程抢写库。这里用「代次号 + 同步置位」收口：
     * - [generation] 递增后，旧协程的收尾不再改新状态；
     * - [send] 同步把 [_streaming] 置 true 再启动；
     * - [stop] 先取消，再 join，最后才落半截文本。
     */
    private var job: Job? = null
    /** 本次生成使用的接口（B2）：完成/停止/失败落库时写入来源。 */
    private var pendingProvider: ActiveProvider? = null

    private val _activeProviderId = MutableStateFlow<String?>(null)
    val activeProviderId: StateFlow<String?> = _activeProviderId.asStateFlow()

    init {
        viewModelScope.launch { _activeProviderId.value = engine.activeProvider()?.id }
    }
    private var generation = 0

    init {
        viewModelScope.launch { repository.ensureGreeting(conversationId) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || job != null) return
        _error.value = null
        _streamText.value = ""
        _streaming.value = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                val blocked = engine.preflightError()
                if (blocked != null) {
                    _error.value = UiText.chatError(blocked)
                    _restoreText.value = trimmed
                } else {
                    pendingProvider = engine.activeProvider()
                    _activeProviderId.value = pendingProvider?.id
                    if (!engine.sendUserMessage(conversationId, trimmed)) return@launch
                    collectStream(engine.streamReply(conversationId), gen)
                }
            } finally {
                finishIfCurrent(gen)
            }
        }
    }

    fun retry() {
        if (job != null) {
            // 生成中不静默吞掉点击：给一条提示，避免「点了没反应」
            _notice.value = UiText.of(R.string.chat_busy_generating)
            return
        }
        _error.value = null
        _streamText.value = ""
        _streaming.value = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                // 先做和发送同样的可发送性检查，避免「错误条重试」在没配模型时又空跑一次
                val blocked = engine.preflightError()
                if (blocked != null) {
                    _error.value = UiText.chatError(blocked)
                    return@launch
                }
                // 只有「最后一条是 assistant」才谈得上重试；否则（例如只有开场白）直接提示
                if (repository.lastAssistantMessageId(conversationId) == null) {
                    _notice.value = UiText.of(R.string.chat_nothing_to_retry)
                    return@launch
                }
                pendingProvider = engine.activeProvider()
                _activeProviderId.value = pendingProvider?.id
                // 重试 = 重新生成最后一条回复：把它从 prompt 历史里剔除，结果作为新版本追加
                collectStream(engine.streamReply(conversationId, excludeLastAssistant = true), gen)
            } finally {
                finishIfCurrent(gen)
            }
        }
    }

    /** 切换某条消息的版本（M2）。 */
    fun selectVariant(messageId: Long, index: Int) {
        viewModelScope.launch { repository.selectVariant(messageId, index) }
    }

    /** 删除一条消息（M2）。 */
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _notice.value = UiText.of(R.string.chat_deleted)
        }
    }

    /**
     * 停止生成：取消后 join，再把已经收到的半截文本落库。
     *
     * join 必须在落库之前：否则被取消的协程可能还在写库，造成顺序错乱/重复插入。
     */
    fun stop() {
        val current = job ?: return
        val partial = _streamText.value.trim()
        generation++ // 旧协程的 finally 不再碰状态
        job = null
        current.cancel()
        _streamText.value = ""
        viewModelScope.launch {
            // 取消不保证立刻完成（网络线程可能还在返回），等它真正结束再写库
            runCatching { current.join() }
            if (partial.isNotEmpty()) {
                appendReply(partial, MessageEntity.STATUS_STOPPED)
            }
            _streaming.value = false
            _notice.value = UiText.chatStopped()
        }
    }

    /** 落一条回复并带上来源接口（B2）。 */
    private suspend fun appendReply(text: String, status: String) {
        val provider = pendingProvider
        repository.appendAssistantReply(
            conversationId = conversationId,
            text = text,
            status = status,
            providerId = provider?.id,
            providerName = provider?.name,
            model = provider?.model,
        )
    }

    /** 新建会话；成功后由 UI 导航到新会话。 */
    fun createConversation(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val characterId = character.value?.id ?: conversation.value?.characterId ?: return@launch
            val id = repository.createConversation(characterId) ?: return@launch
            _notice.value = UiText.of(R.string.chat_conversation_created)
            onCreated(id)
        }
    }

    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    /** 删除会话；当前会话被删时回调 onDeleted 让页面退出。 */
    fun deleteConversation(id: Long, isCurrent: Boolean, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            _notice.value = UiText.of(R.string.chat_conversation_deleted)
            if (isCurrent) onDeleted()
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun consumeRestoreText() {
        _restoreText.value = null
    }

    private suspend fun collectStream(flow: Flow<ChatEvent>, gen: Int) {
        flow.collect { event ->
            if (gen != generation) return@collect
            when (event) {
                is ChatEvent.Delta -> _streamText.value += event.text

                is ChatEvent.Completed -> {
                    // 引擎不落库（M2）：由这里落，才能区分新回复 / 追加版本 + 状态
                    val reply = _streamText.value.trim()
                    if (reply.isNotEmpty()) {
                        appendReply(reply, MessageEntity.STATUS_COMPLETE)
                    }
                    if (event.truncated) _notice.value = UiText.chatTruncated()
                }

                is ChatEvent.Failed -> {
                    // 失败也把已收到的半截留下（标 failed），别让用户白等
                    val partial = _streamText.value.trim()
                    if (partial.isNotEmpty()) {
                        appendReply(partial, MessageEntity.STATUS_FAILED)
                    }
                    _error.value = UiText.chatError(event.error)
                }
            }
        }
    }

    private fun finishIfCurrent(gen: Int) {
        if (gen != generation) return
        job = null
        _streaming.value = false
        _streamText.value = ""
    }

    companion object {
        fun factory(app: TeaTalkApp, conversationId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = app.container.characterRepository,
                    engine = app.container.chatEngine,
                    conversationId = conversationId,
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
    onOpenConversation: (Long) -> Unit = {},
) {
    val character by viewModel.character.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val activeProviderId by viewModel.activeProviderId.collectAsState()
    val streamText by viewModel.streamText.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val restoreText by viewModel.restoreText.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // 长按气泡：弹层看全文 + 复制 / 重试 / 删除（气泡 max 268dp，长回复会被截在可视区里）
    var preview by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MessageEntity?>(null) }
    // 会话管理（M2 多会话）：菜单 → 新建 / 切换面板（面板内可重命名、删除）
    var menuOpen by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteConversationTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    val lastAssistantId = remember(messages) {
        messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id
    }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // 只有「用户本来就在底部」时才自动跟随；向上翻看历史时不打断。
    val stickToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(messages.size, streamText.length, streaming) {
        // 列表第 0 项是「今天」分隔行，所以最后一项下标 = 消息数 + 流式占位 - 1 + 1
        val count = messages.size + if (streaming) 1 else 0
        if (count > 0 && stickToBottom) {
            listState.scrollToItem(count)
        }
    }

    LaunchedEffect(restoreText) {
        restoreText?.let {
            input = it
            viewModel.consumeRestoreText()
        }
    }

    notice?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text.resolve(context))
            viewModel.consumeNotice()
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ChatHeader(
                character = character,
                onBack = onBack,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onNewConversation = {
                    menuOpen = false
                    viewModel.createConversation { onOpenConversation(it) }
                },
                onShowConversations = {
                    menuOpen = false
                    showConversations = true
                },
            )
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
                        UserBubble(message.content, onLongPress = { preview = message })
                    } else {
                        val variants = remember(message) { MessageVariants.of(message) }
                        AssistantBubble(
                            text = message.content,
                            character = character,
                            variants = variants,
                            variantStatuses = remember(message) { MessageVariants.statuses(message) },
                            providerHint = message.providerId
                                ?.takeIf { it != activeProviderId }
                                ?.let {
                                    stringResource(
                                        R.string.chat_generated_by,
                                        message.providerName ?: "?",
                                        message.model ?: "?",
                                    )
                                },
                            selected = message.selectedVariant.coerceIn(0, (variants.size - 1).coerceAtLeast(0)),
                            onSelectVariant = { viewModel.selectVariant(message.id, it) },
                            onLongPress = { preview = message },
                        )
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
            // 键盘与手势条取并集，避免 IME 打开时输入条浮在键盘上方约 13dp。
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
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
        preview?.let { message ->
            val clipboard = LocalClipboardManager.current
            val copied = stringResource(R.string.common_copied)
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text(stringResource(R.string.chat_preview_title), fontSize = 16.sp) },
                text = {
                    Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(message.content, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        preview = null
                        scope.launch { snackbar.showSnackbar(copied) }
                    }) { Text(stringResource(R.string.common_copy)) }
                },
                dismissButton = {
                    Row {
                        if (message.role == "assistant" && message.id == lastAssistantId) {
                            TextButton(onClick = {
                                preview = null
                                viewModel.retry()
                            }) { Text(stringResource(R.string.chat_action_retry)) }
                        }
                        TextButton(onClick = {
                            preview = null
                            deleteTarget = message
                        }) { Text(stringResource(R.string.chat_action_delete), color = cs.error) }
                        TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) }
                    }
                },
            )
        }
        deleteTarget?.let { message ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.chat_delete_title), fontSize = 16.sp) },
                text = {
                    val variantCount = remember(message) { MessageVariants.of(message).size }
                    Text(
                        if (variantCount > 1) {
                            stringResource(R.string.chat_delete_message_variants, variantCount)
                        } else {
                            stringResource(R.string.chat_delete_message)
                        },
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        viewModel.deleteMessage(message.id)
                    }) { Text(stringResource(R.string.chat_action_delete), color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        if (showConversations) {
            ConversationSheet(
                conversations = conversations,
                currentId = viewModel.conversation.value?.id ?: 0L,
                onDismiss = { showConversations = false },
                onSelect = { id ->
                    showConversations = false
                    if (id != (viewModel.conversation.value?.id ?: 0L)) onOpenConversation(id)
                },
                onRename = { renameTarget = it },
                onDelete = { deleteConversationTarget = it },
            )
        }
        renameTarget?.let { target ->
            var title by remember(target) { mutableStateOf(target.title) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameConversation(target.id, title)
                        renameTarget = null
                    }) { Text(stringResource(R.string.edit_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
                },
                title = { Text(stringResource(R.string.chat_conversation_rename)) },
                text = {
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        textStyle = TextStyle(fontSize = 14.5.sp, color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (title.isEmpty()) {
                                    Text(stringResource(R.string.chat_conversation_title_hint), fontSize = 14.5.sp, color = cs.onSurfaceVariant)
                                }
                                inner()
                            }
                        },
                    )
                },
            )
        }
        deleteConversationTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteConversationTarget = null },
                confirmButton = {
                    TextButton(onClick = {
                        deleteConversationTarget = null
                        viewModel.deleteConversation(target.id, isCurrent = target.id == (viewModel.conversation.value?.id ?: 0L)) {
                            onBack()
                        }
                        showConversations = false
                    }) { Text(stringResource(R.string.delete), color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConversationTarget = null }) { Text(stringResource(R.string.cancel)) }
                },
                title = { Text(stringResource(R.string.chat_conversation_delete)) },
                text = {
                    Text(
                        stringResource(
                            R.string.chat_conversation_delete_message,
                            target.title.ifBlank { stringResource(R.string.chat_conversation_untitled) },
                        ),
                    )
                },
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp).imePadding(),
        )
    }
}

@Composable
private fun ChatHeader(
    character: CharacterEntity?,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onNewConversation: () -> Unit,
    onShowConversations: () -> Unit,
) {
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
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more),
                    tint = cs.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_conversation_new)) },
                    onClick = onNewConversation,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_conversations_title)) },
                    onClick = onShowConversations,
                )
            }
        }
    }
}

/** 会话切换面板：列出该角色的全部会话，点击切换；行尾可重命名/删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSheet(
    conversations: List<ConversationEntity>,
    currentId: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.chat_conversations_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            conversations.forEach { item ->
                val current = item.id == currentId
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(item.id) }
                        .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title.ifBlank { stringResource(R.string.chat_conversation_untitled) },
                            fontSize = 14.5.sp,
                            color = cs.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.chat_conversation_updated, timeOf(item.updatedAt)),
                            fontSize = 11.5.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (current) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { onRename(item) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.chat_conversation_rename),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.chat_conversation_delete),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Hairline(insetStart = 20)
            }
        }
    }
}

/** 错误条：「重试」用 TextButton（含最小点击区），文字用权重收缩，长文案不会把按钮挤成 14dp。 */
@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.errorContainer)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = cs.onErrorContainer,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        TextButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.chat_retry),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onErrorContainer,
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    character: CharacterEntity?,
    thinking: Boolean = false,
    variants: List<String> = emptyList(),
    variantStatuses: List<String> = emptyList(),
    providerHint: String? = null,
    selected: Int = 0,
    onSelectVariant: (Int) -> Unit = {},
    onLongPress: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        CharacterAvatar(avatarPath = character?.avatarPath, name = character?.name ?: "", size = 32.dp, corner = 10.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Box(
                Modifier
                    .widthIn(max = 268.dp)
                    .pointerInput(text) { detectTapGestures(onLongPress = { onLongPress(text) }) }
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                if (thinking) {
                    Text(stringResource(R.string.chat_thinking), fontSize = 14.5.sp, color = cs.onSurfaceVariant)
                } else {
                    MarkdownText(text = text, color = cs.onSurface)
                }
            }
            val status = variantStatuses.getOrNull(selected) ?: MessageEntity.STATUS_COMPLETE
            val statusText = when (status) {
                MessageEntity.STATUS_STOPPED -> stringResource(R.string.chat_variant_status_stopped)
                MessageEntity.STATUS_FAILED -> stringResource(R.string.chat_variant_status_failed)
                else -> null
            }
            val multiVariant = variants.size > 1
            if (multiVariant || statusText != null) {
                val versionLabel = if (multiVariant) {
                    stringResource(R.string.chat_variant_label, selected + 1, variants.size)
                } else {
                    null
                }
                val label = listOfNotNull(versionLabel, statusText).joinToString(" · ")
                Row(
                    Modifier
                        .padding(top = 1.dp)
                        .semantics(mergeDescendants = true) { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (multiVariant) {
                        IconButton(
                            onClick = { onSelectVariant(selected - 1) },
                            enabled = selected > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.chat_variant_prev),
                                tint = if (selected > 0) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        label,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurfaceVariant,
                    )
                    if (multiVariant) {
                        IconButton(
                            onClick = { onSelectVariant(selected + 1) },
                            enabled = selected < variants.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.chat_variant_next),
                                tint = if (selected < variants.lastIndex) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            providerHint?.let { hint ->
                Text(
                    hint,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, onLongPress: (String) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 268.dp)
                .pointerInput(text) { detectTapGestures(onLongPress = { onLongPress(text) }) }
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
