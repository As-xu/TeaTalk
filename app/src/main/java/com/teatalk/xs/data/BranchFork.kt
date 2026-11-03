package com.teatalk.xs.data

import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.db.MessageEntity

/** 一次「从此分支」的结果：待写入的新会话 + 需要复制过去的消息（id 已归零）。 */
data class ForkPlan(
    val conversation: ConversationEntity,
    val messages: List<MessageEntity>,
)

/**
 * 分支树（0.20.0）的纯规划逻辑。
 *
 * 只做「取哪段历史、新会话长什么样」的计算，不碰数据库——这样可以在 JVM 上单测，
 * 也避免把 Room 的写入失败和业务规则混在一起。
 */
object BranchFork {

    /** 分支名最长长度（超出截断，避免标题撑爆列表行）。 */
    const val MAX_LABEL = 40

    /**
     * 规划一次分叉。
     *
     * - [upToMessageId] 必须在 [messages] 里，否则返回 null（消息可能已被删除/换会话）。
     * - 只保留 `(createdAt, id)` 不晚于该消息的前缀，分叉点之后的内容不带过去。
     * - 新会话 id = 0（由 Room 重新分配）；消息 id 与 conversationId 也归零，其余字段原样保留
     *   （多版本候选、状态、说话者都跟着走）。
     * - 标题优先用用户输入的分支名，否则沿用源会话标题。
     */
    fun plan(
        source: ConversationEntity,
        messages: List<MessageEntity>,
        upToMessageId: Long,
        label: String?,
        now: Long,
    ): ForkPlan? {
        val ordered = messages.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val index = ordered.indexOfFirst { it.id == upToMessageId }
        if (index < 0) return null
        val cut = ordered[index]
        val prefix = ordered.filter {
            it.createdAt < cut.createdAt || (it.createdAt == cut.createdAt && it.id <= cut.id)
        }
        val cleanLabel = label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)
        val fork = source.copy(
            id = 0L,
            title = cleanLabel ?: source.title,
            createdAt = now,
            updatedAt = now,
            parentConversationId = source.id,
            forkMessageId = upToMessageId,
            forkLabel = cleanLabel,
        )
        return ForkPlan(
            conversation = fork,
            messages = prefix.map { it.copy(id = 0L, conversationId = 0L) },
        )
    }
}
