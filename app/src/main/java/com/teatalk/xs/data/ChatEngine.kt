package com.teatalk.xs.data

import com.teatalk.core.log.TeaTalkLog
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.core.worldinfo.WorldInfoInjector
import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.model.SamplingPreset
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
    /** M3 步骤 4：ST 预设（可选采样参数）。返回 null 表示未导入。 */
    private val samplingPreset: () -> SamplingPreset? = { null },
    /** 个人资料名（替换 `{{user}}`）；M3 golden 对齐项。 */
    private val userName: () -> String = { "User" },
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
        val exists = if (conversation.kind == ConversationEntity.KIND_GROUP) {
            characters.groupMembers(conversationId).isNotEmpty()
        } else {
            characters.find(conversation.characterId) != null
        }
        if (!exists) return null
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
        if (conversation == null) {
            emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "conversation not found"))
            return@flow
        }
        val isGroup = conversation.kind == ConversationEntity.KIND_GROUP
        val members = if (isGroup) characters.groupMembers(conversationId) else emptyList()
        val character = if (isGroup) null else characters.find(conversation.characterId)
        if (isGroup && members.isEmpty()) {
            emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "group has no members"))
            return@flow
        }
        if (!isGroup && character == null) {
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

        val history = characters.recentMessages(conversationId, HISTORY_LIMIT)
            // 以本次用户消息为锚点：只取到它为止的历史，避免停止/并发写让「未来」的消息混进 prompt
            .filter { it.id <= replyToMessageId }
            .let { list ->
                if (excludeLastAssistant && list.lastOrNull()?.role == ROLE_ASSISTANT) list.dropLast(1) else list
            }

        // 群聊：按历史里已有的 assistant 条数轮转发言者（确定性，便于测试与复现）。
        val groupSpeaker = if (isGroup) {
            val assistantCount = history.count { it.role == ROLE_ASSISTANT }
            members[assistantCount % members.size]
        } else {
            null
        }
        emit(
            ChatEvent.Started(
                providerId = provider.id,
                providerName = provider.name,
                model = model,
                speakerId = groupSpeaker?.characterId,
                speakerName = groupSpeaker?.name,
            ),
        )

        // 整张卡 JSON 解析 + system prompt 拼装都在后台线程：聊天路径此前在 Main 上做（审阅 P1）。
        val parsedGroup = if (isGroup) {
            withContext(Dispatchers.Default) { members.map { it to CardViewParser.parse(it.cardJson) } }
        } else {
            emptyList()
        }
        val card = if (isGroup) null else withContext(Dispatchers.Default) { CardViewParser.parse(character!!.cardJson) }
        val primaryName = if (isGroup) members.joinToString("、") { it.name } else character!!.name

        // M3：世界书注入。单人：卡内嵌 + 角色默认 + 会话覆盖；群聊：全体成员卡内嵌 + 群绑定（会话覆盖优先，否则成员默认并集）。
        val boundIds = if (isGroup) {
            characters.effectiveGroupWorldBookIds(conversationId, members.map { it.characterId })
        } else {
            characters.effectiveWorldBookIds(character!!.id, conversationId)
        }
        val externalEntries = characters.worldBookEntries(boundIds)
        val allEntries: List<WorldInfoEntry> = if (isGroup) {
            parsedGroup.flatMap { it.second.worldBook } + externalEntries.map { it.entry }
        } else {
            WorldBookMerger.merge(card!!.worldBook, externalEntries)
        }
        // 扫描文本：本次用户消息 + 最近两条，兼顾 ST 的「最近 N 条」默认行为。
        val scanText = history.takeLast(3).joinToString("\n") { it.content }
        val activated = withContext(Dispatchers.Default) {
            WorldInfoInjector.select(allEntries, scanText, MAX_WORLD_BOOK_CHARS)
        }
        val beforeWorld = WorldInfoInjector.beforeCharacter(activated)
        val afterWorld = WorldInfoInjector.afterCharacter(activated)
        val historyInsertions = WorldInfoInjector.historyInsertions(activated)

        val beforeWorldText = beforeWorld.map { it.content }
        val afterWorldText = afterWorld.map { it.content }
        val messages = withContext(Dispatchers.Default) {
            val base = history.map { message ->
                val role = if (message.role == ROLE_USER) PromptAssembler.ROLE_USER else PromptAssembler.ROLE_ASSISTANT
                val content = if (message.role == ROLE_USER) {
                    message.content
                } else {
                    val speaker = members.firstOrNull { it.characterId == message.speakerId }?.name
                    if (isGroup && speaker != null) "$speaker: ${message.content}" else message.content
                }
                role to content
            }
            val withWorld = WorldInfoInjector.injectHistory(base, historyInsertions)
            if (isGroup) {
                PromptAssembler.assembleGroup(
                    members = parsedGroup.map { (member, groupCard) -> PromptAssembler.GroupMember(member.name, groupCard) },
                    userName = userName(),
                    history = withWorld.map { PromptAssembler.HistoryMessage(it.first, it.second) },
                    beforeWorld = beforeWorldText,
                    afterWorld = afterWorldText,
                    speaker = groupSpeaker?.name,
                )
            } else {
                PromptAssembler.assemble(
                    card = card!!,
                    characterName = character!!.name,
                    userName = userName(),
                    history = withWorld.map { PromptAssembler.HistoryMessage(it.first, it.second) },
                    beforeWorld = beforeWorldText,
                    afterWorld = afterWorldText,
                )
            }
        }
        TeaTalkLog.i(
            "Prompt",
            "prompt.order",
            "group" to isGroup,
            "members" to members.size,
            "phi" to (if (isGroup) parsedGroup.any { it.second.postHistoryInstructions.isNotBlank() } else card!!.postHistoryInstructions.isNotBlank()),
        )
        TeaTalkLog.i(
            "Prompt",
            "worldbook.inject",
            "entries" to allEntries.size,
            "activated" to activated.size,
            "chars" to WorldInfoInjector.cost(activated),
            "unsupported" to WorldInfoInjector.unsupportedCount(allEntries),
            "bound" to boundIds.size,
        )
        TeaTalkLog.i(
            "Chat",
            "prompt.ready",
            "msgs" to messages.size,
            "sysChars" to messages.first().second.length,
        )
        // M3 步骤 2：把这次请求的可读快照放到内存里，供「查看本次请求」面板展示（不落盘、不导出）。
        // 预设需在快照之前解析，面板才能显示本次请求的真实采样值。
        val preset = samplingPreset()
        // 优先级：ST 预设 > Provider 级配置 > 内置默认。两者都为空时保持客户端默认。
        val effectiveTopP = preset?.topP ?: provider.topP
        val effectiveMaxTokens = preset?.maxTokens ?: provider.maxTokens
        val effectiveFreq = preset?.frequencyPenalty ?: provider.frequencyPenalty
        val effectivePres = preset?.presencePenalty ?: provider.presencePenalty
        val effectiveTemperature = preset?.temperature?.toDouble() ?: provider.temperature
        val samplingSource = when {
            preset != null -> "preset"
            provider.topP != null || provider.maxTokens != null ||
                provider.frequencyPenalty != null || provider.presencePenalty != null -> "provider"
            else -> "built-in"
        }
        val presetLabel = buildString {
            append("temp ").append(effectiveTemperature)
            effectiveTopP?.let { append(" · top_p ").append(it) }
            effectiveMaxTokens?.let { append(" · max ").append(it) }
            effectiveFreq?.let { append(" · freq ").append(it) }
            effectivePres?.let { append(" · pres ").append(it) }
            append(" · source ").append(samplingSource)
        }
        PromptDebugStore.publish(
            conversationId = conversationId,
            providerName = provider.name,
            model = model,
            sampling = presetLabel,
            system = messages.first().second,
            messages = messages.drop(1),
            activated = activated,
            worldChars = WorldInfoInjector.cost(activated),
            characterName = primaryName,
            userName = userName(),
        )

        val builder = StringBuilder()
        var firstTokenLogged = false
        TeaTalkLog.i(
            "Prompt",
            "sampling.preset",
            "present" to (preset != null),
            "source" to samplingSource,
            "temperature" to effectiveTemperature,
            "topP" to (effectiveTopP ?: -1.0),
            "maxTokens" to (effectiveMaxTokens ?: -1),
            "freqPenalty" to (effectiveFreq ?: -1.0),
            "presPenalty" to (effectivePres ?: -1.0),
        )
        client.stream(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = model,
            messages = messages,
            // 预设优先于接口自带的 temperature（用户显式导入的意图更强）。
            temperature = effectiveTemperature,
            topP = effectiveTopP?.toDouble(),
            maxTokens = effectiveMaxTokens,
            frequencyPenalty = effectiveFreq?.toDouble(),
            presencePenalty = effectivePres?.toDouble(),
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

    companion object {
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val HISTORY_LIMIT = 20
        /** 角色卡各段系统提示的单段上限（世界书另用 [MAX_WORLD_BOOK_CHARS]）。 */
private const val MAX_SYSTEM_CHARS = 6000

/** 世界书字符预算：`select` 内单点裁决，system prompt 里不再二次截断。 */
private const val MAX_WORLD_BOOK_CHARS = 8000

        /** 自动命名时截取的字符数。 */
        private const val AUTO_TITLE_CHARS = 18
    }
}
