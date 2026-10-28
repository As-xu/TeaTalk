package com.teatalk.xs.data

import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.model.ActiveProvider
import com.teatalk.xs.data.model.ProviderResolution
import com.teatalk.xs.data.net.ChatClient
import com.teatalk.xs.data.net.ChatError
import com.teatalk.xs.data.net.ChatEvent
import com.teatalk.xs.data.net.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 聊天回复引擎（M1 步骤 2/3 的最小版）。
 *
 * 发送与流式生成分开，是因为「停止后保存半截」必须两次进入：
 * - [sendUserMessage] 落用户消息（幂等由 UI 的单飞行控制保证）；
 * - [streamReply] 以当前历史生成，可被 `retry`/`stop` 再次进入。
 *
 * [reply] 是两者的组合，保留给只关心「一次完整往返」的调用方。
 * prompt 预算/世界书注入是 M2 的事，这里先给「能真聊」的最小形状。
 */
class ChatEngine(
    private val characters: CharacterRepository,
    private val providers: ProviderRepository,
    private val client: ChatClient,
) {

    /**
     * 落用户消息，返回它的 id（后续回复以它为锚点落库）；返回 null 表示内容为空/会话不存在。
     *
     * 顺带做两件事：
     * - 会话还没有标题时，用这条消息的前 18 个字自动命名（UI 头部与列表都靠它区分同角色的多条会话）；
     * - 发消息即 touch 会话，列表按最近活跃排序。
     */
    suspend fun sendUserMessage(conversationId: Long, userText: String): Long? {
        val text = userText.trim()
        if (text.isEmpty()) return null
        val conversation = characters.conversation(conversationId) ?: return null
        if (characters.find(conversation.characterId) == null) return null
        TeaTalkLog.i("Chat", "send.start", "conversationId" to conversationId, "chars" to text.length)
        val messageId = characters.addMessage(conversationId, ROLE_USER, text)
        if (conversation.title.isBlank()) {
            characters.renameConversation(conversationId, text.take(AUTO_TITLE_CHARS).replace('\n', ' '))
        }
        characters.touchConversation(conversationId)
        return messageId
    }

    /**
     * 发送前的可发送性检查（不落库）。
     *
     * 为什么要单独一步：用户消息一旦先落库，provider 没配/没模型时就会留下一条
     * 「自言自语」的孤立用户消息。这里先判一次，失败时不产生任何消息。
     */
    /** 当前生效的接口（B2：落库时记录来源；未配置/无 key 返回 null）。 */
    suspend fun activeProvider(): ActiveProvider? =
        (providers.resolve() as? ProviderResolution.Ok)?.provider

    suspend fun preflightError(): ChatError? = when (val resolved = providers.resolve()) {
        is ProviderResolution.Ok ->
            if (resolved.provider.model.trim().isEmpty()) ChatError.NO_MODEL else null
        ProviderResolution.NoProvider -> ChatError.NO_PROVIDER
        is ProviderResolution.NoKey -> ChatError.NO_KEY
    }

    /**
     * 生成一条回复（不落库）：调用方在结束后用 [CharacterRepository.appendAssistantReply]
     * 落库，这样才能区分「新回复」与「追加版本」，也才能带 complete/stopped/failed 状态。
     *
     * @param excludeLastAssistant 重试时把最后一条 assistant 从历史里剔除，避免模型看到自己
     * 上一版回复而被带跑。
     */
    fun streamReply(
        conversationId: Long,
        replyToMessageId: Long,
        excludeLastAssistant: Boolean = false,
    ): Flow<ChatEvent> = flow {
        TeaTalkLog.i(
            "Chat",
            "send.retry",
            "conversationId" to conversationId,
            "anchor" to replyToMessageId,
            "excludeLast" to excludeLastAssistant,
        )
        emitAll(stream(conversationId, replyToMessageId, excludeLastAssistant))
    }

    private fun stream(
        conversationId: Long,
        replyToMessageId: Long,
        excludeLastAssistant: Boolean = false,
    ): Flow<ChatEvent> = flow {
        val conversation = characters.conversation(conversationId)
        val character = conversation?.let { characters.find(it.characterId) }
        if (conversation == null || character == null) {
            emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "conversation or character not found"))
            return@flow
        }

        val provider = when (val resolved = providers.resolve()) {
            is ProviderResolution.Ok -> resolved.provider
            ProviderResolution.NoProvider -> {
                TeaTalkLog.w("Chat", "send.no_provider", "conversationId" to conversationId)
                emit(ChatEvent.Failed(ChatError.NO_PROVIDER))
                return@flow
            }

            is ProviderResolution.NoKey -> {
                TeaTalkLog.w("Chat", "send.no_key", "provider" to resolved.provider.id)
                emit(ChatEvent.Failed(ChatError.NO_KEY))
                return@flow
            }
        }
        providers.touch(provider.id)

        // 不拿角色名兜底：模型名打错时服务端会 400/404，比「用角色名当模型」更容易发现。
        val model = provider.model.trim()
        if (model.isEmpty()) {
            TeaTalkLog.w("Chat", "send.no_model", "provider" to provider.id)
            emit(ChatEvent.Failed(ChatError.NO_MODEL))
            return@flow
        }
        emit(ChatEvent.Started(provider.id, provider.name, model))

        // 整张卡 JSON 解析 + system prompt 拼装都在后台线程：聊天路径此前在 Main 上做（审阅 P1）。
        val card = withContext(Dispatchers.Default) { CardViewParser.parse(character.cardJson) }
        val history = characters.recentMessages(conversationId, HISTORY_LIMIT)
            // 以本次用户消息为锚点：只取到它为止的历史，避免停止/并发写让「未来」的消息混进 prompt
            .filter { it.id <= replyToMessageId }
            .let { list ->
                if (excludeLastAssistant && list.lastOrNull()?.role == ROLE_ASSISTANT) list.dropLast(1) else list
            }
        val messages = withContext(Dispatchers.Default) {
            buildList {
                add(ROLE_SYSTEM to systemPrompt(card, character.name))
                history.forEach { message ->
                    val role = if (message.role == ROLE_USER) ROLE_USER else ROLE_ASSISTANT
                    add(role to message.content)
                }
            }
        }
        TeaTalkLog.i(
            "Chat",
            "prompt.ready",
            "msgs" to messages.size,
            "sysChars" to messages.first().second.length,
        )

        val builder = StringBuilder()
        var firstTokenLogged = false
        client.stream(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = model,
            messages = messages,
            temperature = provider.temperature,
        ).collect { event ->
            when (event) {
                is ChatEvent.Started -> emit(event)

                is ChatEvent.Delta -> {
                    if (!firstTokenLogged) {
                        firstTokenLogged = true
                        TeaTalkLog.i("Chat", "stream.first_token", "chars" to event.text.length)
                    }
                    builder.append(event.text)
                    emit(event)
                }

                is ChatEvent.Completed -> {
                    val reply = builder.toString().trim()
                    TeaTalkLog.i(
                        "Chat",
                        "stream.done",
                        "chars" to reply.length,
                        "truncated" to event.truncated,
                    )
                    emit(event)
                }

                is ChatEvent.Failed -> {
                    TeaTalkLog.w("Chat", "send.fail", "error" to event.error.label)
                    emit(event)
                }
            }
        }
    }

    /** 角色扮演的最小 system prompt；超出预算先截断描述（M2 再做真正的 token 预算）。 */
    private fun systemPrompt(card: CardView, fallbackName: String): String {
        val name = card.name.ifBlank { fallbackName }
        return buildString {
            append("你正在扮演「").append(name).append("」。")
            if (card.description.isNotBlank()) {
                append("\n\n[设定]\n").append(card.description.take(MAX_SYSTEM_CHARS))
            }
            if (card.personality.isNotBlank()) {
                append("\n\n[性格]\n").append(card.personality.take(MAX_SYSTEM_CHARS))
            }
            if (card.scenario.isNotBlank()) {
                append("\n\n[场景]\n").append(card.scenario.take(MAX_SYSTEM_CHARS))
            }
            if (card.systemPrompt.isNotBlank()) {
                append("\n\n[额外指示]\n").append(card.systemPrompt.take(MAX_SYSTEM_CHARS))
            }
            append("\n\n要求：始终以该角色第一人称回应，保持语气与人格，不要跳出角色、不要解释设定或输出分析过程。")
        }
    }

    companion object {
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val HISTORY_LIMIT = 20
        private const val MAX_SYSTEM_CHARS = 6000

        /** 自动命名时截取的字符数。 */
        private const val AUTO_TITLE_CHARS = 18
    }
}
