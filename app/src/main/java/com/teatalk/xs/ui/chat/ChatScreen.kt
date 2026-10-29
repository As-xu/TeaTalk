package com.teatalk.xs.ui.chat

import android.os.SystemClock

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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
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
import com.teatalk.xs.data.DeletedConversationStore
import com.teatalk.xs.data.MessageVariants
import com.teatalk.xs.data.ChatEngine
import com.teatalk.xs.data.UiText
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.teatalk.xs.ui.theme.TypeScale

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
    /** 停止中：生成协程的 finally 在此期间不碰 UI 状态（收尾由 stop() 负责）。 */
    private var stopping = false
    /** 本轮回复的锚点（用户消息 id / 被重试的 assistant id），落库时校验，防错位。 */
    private var replyAnchorId: Long? = null
    /** 本轮实际使用的接口（来自 `ChatEvent.Started`，不跨轮共享）。 */
    private var replyProvider: ChatEvent.Started? = null
    /** 本轮是否已落过库（同一轮最多一条）。 */
    private var committed = false
    private val commitMutex = Mutex()
    private val accumulated = StringBuilder()
    private val _busy = MutableStateFlow(false)
    /** 生成或停止中（UI 用来禁用会「离开当前会话」的动作）。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

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
        if (trimmed.isEmpty()) return
        if (job != null || stopping) {
            // 生成/停止过程中不静默吞掉：给一条提示，避免「点了没反应」
            _notice.value = UiText.of(R.string.chat_busy_generating)
            return
        }
        _error.value = null
        _streamText.value = ""
        accumulated.setLength(0)
        replyProvider = null
        committed = false
        replyAnchorId = null
        _streaming.value = true
        _busy.value = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                val blocked = engine.preflightError()
                if (blocked != null) {
                    _error.value = UiText.chatError(blocked)
                    _restoreText.value = trimmed
                    return@launch
                }
                val anchor = engine.sendUserMessage(conversationId, trimmed) ?: return@launch
                replyAnchorId = anchor
                collectStream(engine.streamReply(conversationId, anchor), gen)
            } finally {
                finishIfCurrent(gen)
            }
        }
    }

    fun retry() {
        if (job != null || stopping) {
            _notice.value = UiText.of(R.string.chat_busy_generating)
            return
        }
        _error.value = null
        _streamText.value = ""
        accumulated.setLength(0)
        replyProvider = null
        committed = false
        _streaming.value = true
        _busy.value = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                val blocked = engine.preflightError()
                if (blocked != null) {
                    _error.value = UiText.chatError(blocked)
                    return@launch
                }
                // 只有「最后一条是 assistant」才谈得上重试；否则（例如只有开场白）直接提示
                val lastAssistant = repository.lastAssistantMessageId(conversationId)
                if (lastAssistant == null) {
                    _notice.value = UiText.of(R.string.chat_nothing_to_retry)
                    return@launch
                }
                // 重试 = 重新生成最后一条回复：它本身是锚点（结果作为新版本追加），从 prompt 历史里剔除
                replyAnchorId = lastAssistant
                collectStream(
                    engine.streamReply(conversationId, lastAssistant, excludeLastAssistant = true),
                    gen,
                )
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
     * 停止生成。
     *
     * 顺序很重要：先 cancel，再 join，最后落半截文本；且**落库与状态收尾都由 stop 这条
     * 协程负责**，生成协程的 finally 在 stopping 期间不动状态。否则会出现：
     * 「完整回复刚落库，停止又把半截追加成同一消息的第二个版本」或「停止后立刻发送，
     * 半截回复被插到新问题下面」。
     */
    fun stop() {
        val current = job ?: return
        val gen = generation
        val partial = accumulated.toString()
        stopping = true
        current.cancel()
        viewModelScope.launch {
            // 取消不保证立刻完成（网络线程可能还在返回），等它真正结束再写库
            runCatching { current.join() }
            commitOnce(gen, MessageEntity.STATUS_STOPPED, partial)
            if (gen == generation) {
                job = null
                stopping = false
                _streaming.value = false
                _streamText.value = ""
                _busy.value = false
                _notice.value = UiText.chatStopped()
            }
        }
    }

    /** 新建会话；成功后由 UI 导航到新会话。生成中先停止，避免流把内容写进旧会话。 */
    fun createConversation(onCreated: (Long) -> Unit) {
        if (guardStreaming()) return
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

    /** 删除确认所需的信息（会话 + 消息条数）。 */
    data class ConversationDeleteInfo(val conversation: ConversationEntity, val messageCount: Long)

    private val _deleteInfo = MutableStateFlow<ConversationDeleteInfo?>(null)
    val deleteInfo: StateFlow<ConversationDeleteInfo?> = _deleteInfo.asStateFlow()

    /** 最近删除的会话（5s 内可撤销）。 */
    private val _deletedConversation = MutableStateFlow<CharacterRepository.DeletedConversation?>(null)
    val deletedConversation: StateFlow<CharacterRepository.DeletedConversation?> = _deletedConversation.asStateFlow()

    /** 删除前先取条数：确认框写清「几条消息」，避免误删长会话。 */
    fun requestDeleteConversation(conversation: ConversationEntity) {
        if (guardStreaming()) return
        viewModelScope.launch {
            val count = repository.countMessages(conversation.id)
            _deleteInfo.value = ConversationDeleteInfo(conversation, count)
        }
    }

    fun cancelDeleteConversation() {
        _deleteInfo.value = null
    }

    /** 删除会话；当前会话被删时回调 onDeleted 让页面退出。生成中先拒绝。 */
    fun confirmDeleteConversation(isCurrent: Boolean, onDeleted: () -> Unit) {
        val info = _deleteInfo.value ?: return
        _deleteInfo.value = null
        viewModelScope.launch {
            val snapshot = repository.deleteConversationWithSnapshot(info.conversation.id)
            if (snapshot == null) return@launch
            val title = info.conversation.title.ifBlank {
                character.value?.name ?: info.conversation.title
            }
            if (isCurrent) {
                // 当前会话删掉后本页会立刻退出，撤销入口交给列表页
                DeletedConversationStore.put(snapshot)
                onDeleted()
            } else {
                _deletedConversation.value = snapshot
                _notice.value = UiText.conversationDeleted(title, info.messageCount)
            }
        }
    }

    /** 撤销删除（Snackbar 上的「撤销」）。 */
    fun undoDeleteConversation() {
        val snapshot = _deletedConversation.value ?: return
        _deletedConversation.value = null
        viewModelScope.launch {
            repository.restoreConversation(snapshot)
            val title = snapshot.conversation.title.ifBlank {
                character.value?.name ?: snapshot.conversation.title
            }
            _notice.value = UiText.conversationRestored(title)
        }
    }

    fun consumeDeletedConversation() {
        _deletedConversation.value = null
    }

    /**
     * 打开/切换会话前的守卫（P0）：流式生成时不允许直接切走。
     *
     * 不替用户自动停止——切会话后「已停止」的回执会落在另一个会话上，容易误判。
     */
    fun guardStreaming(): Boolean {
        if (job != null || stopping) {
            _notice.value = UiText.of(R.string.chat_busy_generating)
            return true
        }
        return false
    }

    fun dismissError() {
        _error.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun consumeRestoreText() {
        _restoreText.value = null
    }

    private suspend fun collectStream(flow: Flow<ChatEvent>, gen: Int) {
        // 流式节流：token 可能每几十毫秒来一个，逐 token 触发重组会让 Markdown 反复全量解析（O(n²) 字符拷贝）。
        // 这里最多每 50ms 刷一次 UI，收尾时用 commitOnce 的完整文本，显示不受影响。
        var lastUiEmit = 0L
        flow.collect { event ->
            if (gen != generation) return@collect
            when (event) {
                is ChatEvent.Started -> {
                    replyProvider = event
                    _activeProviderId.value = event.providerId
                }

                is ChatEvent.Delta -> {
                    accumulated.append(event.text)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUiEmit >= STREAM_UI_INTERVAL_MS) {
                        lastUiEmit = now
                        _streamText.value = accumulated.toString()
                    }
                }

                is ChatEvent.Completed -> {
                    // 引擎不落库（M2）：由这里落，才能区分新回复 / 追加版本 + 状态
                    _streamText.value = accumulated.toString()
                    commitOnce(gen, MessageEntity.STATUS_COMPLETE, accumulated.toString())
                    if (event.truncated) _notice.value = UiText.chatTruncated()
                }

                is ChatEvent.Failed -> {
                    // 失败也把已收到的半截留下（标 failed），别让用户白等
                    _streamText.value = accumulated.toString()
                    commitOnce(gen, MessageEntity.STATUS_FAILED, accumulated.toString())
                    _error.value = UiText.chatError(event.error)
                }
            }
        }
    }

    /**
     * 只落一次库。
     *
     * 完成与停止可能同时到达（取消点落在 Completed 的落库中间），用「代次 + 已提交标记 + Mutex」
     * 保证同一轮生成最多写一条：先到者为准，后到者静默丢弃。
     */
    private suspend fun commitOnce(gen: Int, status: String, raw: String) {
        val body = raw.trim()
        if (body.isEmpty()) return
        commitMutex.withLock {
            if (gen != generation || committed) return
            committed = true
            try {
                repository.appendAssistantReply(
                    conversationId = conversationId,
                    text = body,
                    status = status,
                    providerId = replyProvider?.providerId,
                    providerName = replyProvider?.providerName,
                    model = replyProvider?.model,
                    replyToMessageId = replyAnchorId,
                )
            } catch (cancelled: CancellationException) {
                // 写库被取消：可能已回滚，放开标记让停止路径重试
                committed = false
                throw cancelled
            }
        }
    }

    private fun finishIfCurrent(gen: Int) {
        if (gen != generation) return
        if (stopping) return // 收尾交给 stop() 的协程
        job = null
        _streaming.value = false
        _streamText.value = ""
        _busy.value = false
    }

    companion object {
        /** UI 刷新节流窗口：约 20fps，足够顺滑又不至于逐 token 重组。 */
        const val STREAM_UI_INTERVAL_MS = 50L

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
    val deleteInfo by viewModel.deleteInfo.collectAsState()
    val deletedConversation by viewModel.deletedConversation.collectAsState()
    val lastAssistantId = remember(messages) {
        messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id
    }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 只有「用户本来就在底部」时才自动跟随；向上翻看历史时不打断。
    val stickToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }

    // 生成中按返回：先停止并留在页面（回执可见），再按一次才真正返回。
    BackHandler(enabled = streaming) { viewModel.stop() }

    LaunchedEffect(messages.size, streamText.length, streaming) {
        // 列表第 0 项是「今天」分隔行，所以最后一项下标 = 消息数 + 流式占位 - 1 + 1
        val count = messages.size + if (streaming) 1 else 0
        if (count > 0 && stickToBottom) {
            listState.scrollToItem(count)
        }
    }

    // IME 弹起会把输入条抬高、可视区变矮：此时强制滚到底，避免刚打的字被键盘顶出视野。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size + if (streaming) 1 else 0)
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

    // 删除会话后的 5s 撤销入口（Snackbar 带 action）
    deletedConversation?.let { snapshot ->
        LaunchedEffect(snapshot) {
            val title = snapshot.conversation.title.ifBlank {
                character?.name ?: snapshot.conversation.title
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
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteConversation()
            viewModel.consumeDeletedConversation()
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ChatHeader(
                character = character,
                conversationTitle = conversation?.title.orEmpty(),
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
                            fontSize = TypeScale.s11,
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
                            onOpenPreview = { preview = message },
                            onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                            onRetry = if (message.id == lastAssistantId) viewModel::retry else null,
                            onDelete = if (message.id == lastAssistantId) ({ deleteTarget = message }) else null,
                        )
                    }
                }
                if (streaming) {
                    item(key = "streaming") {
                        AssistantBubble(streamText, character, thinking = streamText.isEmpty(), streaming = true)
                    }
                }
            }

            error?.let { err ->
                // 配置类错误（没 Provider/没 key/没 model）重试必然再失败：只给「关闭」。
                val retryable = err.res !in setOf(
                    R.string.chat_error_no_provider,
                    R.string.chat_error_no_key,
                    R.string.chat_error_no_model,
                )
                ErrorBanner(
                    text = err.resolve(context),
                    showRetry = retryable,
                    onRetry = viewModel::retry,
                    onDismiss = viewModel::dismissError,
                )
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
                            textStyle = TextStyle(fontSize = TypeScale.s14_5, color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            maxLines = 4,
                            decorationBox = { inner ->
                                if (input.isEmpty()) {
                                    Text(
                                        stringResource(R.string.chat_input_hint),
                                        fontSize = TypeScale.s14_5,
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
                title = { Text(stringResource(R.string.chat_preview_title), fontSize = TypeScale.s16) },
                text = {
                    Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(message.content, fontSize = TypeScale.s14, lineHeight = 21.sp)
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
                    TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) }
                },
            )
        }
        deleteTarget?.let { message ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.chat_delete_title), fontSize = TypeScale.s16) },
                text = {
                    val variantCount = remember(message) { MessageVariants.of(message).size }
                    Text(
                        if (variantCount > 1) {
                            stringResource(R.string.chat_delete_message_variants, variantCount)
                        } else {
                            stringResource(R.string.chat_delete_message)
                        },
                        fontSize = TypeScale.s14,
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
                    // 生成中不直接切走：切走后「已停止」的回执会落在另一个会话上
                    if (!viewModel.guardStreaming()) {
                        showConversations = false
                        if (id != (viewModel.conversation.value?.id ?: 0L)) onOpenConversation(id)
                    }
                },
                onRename = { renameTarget = it },
                onDelete = { viewModel.requestDeleteConversation(it) },
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
                        textStyle = TextStyle(fontSize = TypeScale.s14_5, color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (title.isEmpty()) {
                                    Text(stringResource(R.string.chat_conversation_title_hint), fontSize = TypeScale.s14_5, color = cs.onSurfaceVariant)
                                }
                                inner()
                            }
                        },
                    )
                },
            )
        }
        deleteInfo?.let { info ->
            val target = info.conversation
            AlertDialog(
                onDismissRequest = { viewModel.cancelDeleteConversation() },
                confirmButton = {
                    TextButton(onClick = {
                        val isCurrent = target.id == (viewModel.conversation.value?.id ?: 0L)
                        viewModel.confirmDeleteConversation(isCurrent = isCurrent) { onBack() }
                        showConversations = false
                    }) { Text(stringResource(R.string.delete), color = cs.error) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelDeleteConversation() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = { Text(stringResource(R.string.chat_conversation_delete)) },
                text = {
                    Text(
                        if (info.messageCount == 1L) {
                            stringResource(
                                R.string.chat_conversation_delete_message_one,
                                target.title.ifBlank { stringResource(R.string.chat_conversation_untitled) },
                            )
                        } else {
                            stringResource(
                                R.string.chat_conversation_delete_message_count,
                                target.title.ifBlank { stringResource(R.string.chat_conversation_untitled) },
                                info.messageCount,
                            )
                        },
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
    conversationTitle: String,
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
            // 标题优先显示「会话名」：同一角色的多条会话在头部就能区分（否则只能靠时间猜）
            Text(
                conversationTitle.ifBlank { character?.name ?: stringResource(R.string.character_fallback) },
                fontSize = TypeScale.s15_5,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 会话没有标题时副标题不再重复角色名
                if (conversationTitle.isBlank()) stringResource(R.string.chat_online_status)
                else stringResource(R.string.chat_header_subtitle, character?.name ?: stringResource(R.string.character_fallback)),
                fontSize = TypeScale.s11,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                fontSize = TypeScale.s16,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            // 会话可能很多：用 LazyColumn 而不是 forEach，面板自身可滚动（审阅 P1）。
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(conversations, key = { it.id }) { item ->
                val current = item.id == currentId
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(item.id) }
                        .padding(start = 20.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title.ifBlank { stringResource(R.string.chat_conversation_untitled) },
                            fontSize = TypeScale.s14_5,
                            color = cs.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.chat_conversation_updated, timeOf(item.updatedAt)),
                            fontSize = TypeScale.s11_5,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (current) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { onRename(item) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.chat_conversation_rename),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.chat_conversation_delete),
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Hairline(insetStart = 20)
                }
            }
        }
    }
}

/** 错误条：可关闭；配置类错误不提供「重试」，避免点了必然再失败。 */
@Composable
private fun ErrorBanner(
    text: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            fontSize = TypeScale.s12_5,
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
                fontSize = TypeScale.s12_5,
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
    streaming: Boolean = false,
    variants: List<String> = emptyList(),
    variantStatuses: List<String> = emptyList(),
    providerHint: String? = null,
    selected: Int = 0,
    onSelectVariant: (Int) -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onCopy: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        CharacterAvatar(avatarPath = character?.avatarPath, name = character?.name ?: "", size = 32.dp, corner = 10.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            var actionsOpen by remember { mutableStateOf(false) }
            Box {
            Box(
                Modifier
                    .widthIn(max = 268.dp)
                    .pointerInput(text) { detectTapGestures(onLongPress = { actionsOpen = true }) }
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (thinking) {
                    Text(stringResource(R.string.chat_thinking), fontSize = TypeScale.s14_5, color = cs.onSurfaceVariant)
                } else if (streaming) {
                    // 流式期间用纯文本：半截的 ** / ` 会闪，且整段 Markdown 反复解析是 O(n²) 热点。
                    // 生成结束落库后由历史气泡按 Markdown 渲染。
                    Text(
                        text = text,
                        fontSize = TypeScale.s14_5,
                        lineHeight = 22.sp,
                        color = cs.onSurface,
                    )
                } else {
                    MarkdownText(text = text, color = cs.onSurface)
                }
            }
            // 长按动作：锚定在气泡上的下拉菜单（不再用居中弹窗遮挡上下文）
            DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_preview_title)) },
                    onClick = { actionsOpen = false; onOpenPreview() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_copy)) },
                    onClick = { actionsOpen = false; onCopy() },
                )
                onRetry?.let { retry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_action_retry)) },
                        onClick = { actionsOpen = false; retry() },
                    )
                }
                onDelete?.let { delete ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = cs.error) },
                        onClick = { actionsOpen = false; delete() },
                    )
                }
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
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.chat_variant_prev),
                                tint = if (selected > 0) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        label,
                        fontSize = TypeScale.s11_5,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurfaceVariant,
                    )
                    if (multiVariant) {
                        IconButton(
                            onClick = { onSelectVariant(selected + 1) },
                            enabled = selected < variants.lastIndex,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.chat_variant_next),
                                tint = if (selected < variants.lastIndex) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            providerHint?.let { hint ->
                Text(
                    hint,
                    fontSize = TypeScale.s11,
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text, fontSize = TypeScale.s14_5, lineHeight = 22.sp, color = cs.onPrimary)
        }
    }
}

private fun timeOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
