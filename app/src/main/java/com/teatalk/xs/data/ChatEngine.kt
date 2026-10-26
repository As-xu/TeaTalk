package com.teatalk.xs.data

import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.net.ChatClient
import com.teatalk.xs.data.net.ChatError
import com.teatalk.xs.data.net.ChatEvent
import com.teatalk.xs.data.net.label
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 聊天回复引擎（M1 步骤 2/3 的最小版）。
 *
 * - [reply]：落用户消息后流式生成；
 * - [retry]：直接以当前历史重试（不重复落用户消息）；
 * - 正常结束时落 assistant 消息；被取消时由 UI/VM 决定是否保存半截。
 *
 * prompt 预算/世界书注入是 M2 的事，这里先给「能真聊」的最小形状。
 */
class ChatEngine(
    private val characters: CharacterRepository,
    private val providers: ProviderRepository,
    private val client: ChatClient,
) {

    fun reply(characterId: Long, userText: String): Flow<ChatEvent> = flow {
        val text = userText.trim()
        if (text.isEmpty()) return@flow
        if (characters.find(characterId) == null) {
            emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "character not found"))
            return@flow
        }
        TeaTalkLog.i("Chat", "send.start", "characterId" to characterId, "chars" to text.length)
        characters.addMessage(characterId, ROLE_USER, text)
        emitAll(stream(characterId))
    }

    fun retry(characterId: Long): Flow<ChatEvent> = flow {
        TeaTalkLog.i("Chat", "send.retry", "characterId" to characterId)
        emitAll(stream(characterId))
    }

    private fun stream(characterId: Long): Flow<ChatEvent> = flow {
        val character = characters.find(characterId)
        if (character == null) {
            emit(ChatEvent.Failed(ChatError.BAD_RESPONSE, "character not found"))
            return@flow
        }

        val provider = providers.active()
        if (provider == null) {
            TeaTalkLog.w("Chat", "send.no_provider", "characterId" to characterId)
            emit(ChatEvent.Failed(ChatError.NO_PROVIDER))
            return@flow
        }
        providers.touch(provider.id)

        val card = CardViewParser.parse(character.cardJson)
        val messages = buildList {
            add(ROLE_SYSTEM to systemPrompt(card, character.name))
            characters.recentMessages(characterId, HISTORY_LIMIT).forEach { message ->
                val role = if (message.role == ROLE_USER) ROLE_USER else ROLE_ASSISTANT
                add(role to message.content)
            }
        }
        TeaTalkLog.i(
            "Chat",
            "prompt.ready",
            "msgs" to messages.size,
            "sysChars" to messages.first().second.length,
        )

        val model = provider.model.ifBlank { card.name }
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
                is ChatEvent.Delta -> {
                    if (!firstTokenLogged) {
                        firstTokenLogged = true
                        TeaTalkLog.i("Chat", "stream.first_token", "chars" to event.text.length)
                    }
                    builder.append(event.text)
                    emit(event)
                }

                ChatEvent.Completed -> {
                    val reply = builder.toString().trim()
                    if (reply.isNotEmpty()) {
                        characters.addMessage(characterId, ROLE_ASSISTANT, reply)
                        TeaTalkLog.i("Chat", "stream.done", "chars" to reply.length)
                    }
                    emit(ChatEvent.Completed)
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
    }
}
