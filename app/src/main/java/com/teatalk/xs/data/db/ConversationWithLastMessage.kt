package com.teatalk.xs.data.db

import androidx.room.Embedded

/**
 * 聊天列表行：会话 + 角色名/头像 + 最后一条消息。
 *
 * 单独成文件：Room 的 KSP 处理器在 Entities.kt 同文件里解析 @Embedded POJO 时报过
 * `[MissingType] references a type that is not present`，拆文件后正常。
 */
data class ConversationWithLastMessage(
    @Embedded val conversation: ConversationEntity,
    val characterName: String,
    val characterAvatar: String?,
    val lastContent: String?,
    val lastAt: Long?,
)
