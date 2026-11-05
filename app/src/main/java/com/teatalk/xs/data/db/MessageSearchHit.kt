package com.teatalk.xs.data.db

/**
 * 全库消息搜索命中的一行。
 *
 * 与 [ConversationWithLastMessage] 同理单独成文件：Room 的 KSP 对同文件 POJO 解析不稳。
 */
data class MessageSearchHit(
    val conversationId: Long,
    val messageId: Long,
    val conversationTitle: String,
    val characterName: String,
    val characterAvatar: String?,
    val content: String,
    val createdAt: Long,
    /** 会话或其成员角色带 nsfw 标签（成人内容关闭时过滤掉）。 */
    val hasNsfw: Boolean = false,
)
