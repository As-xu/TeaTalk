package com.teatalk.xs.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

/**
 * 群聊成员（M4 group chats）。
 *
 * 主键 `(conversationId, characterId)`：同一角色在同一条群聊里只出现一次。
 * `sortIndex` 决定 prompt 里的成员顺序与成员面板的展示顺序。
 */
@Entity(
    tableName = "conversation_members",
    primaryKeys = ["conversationId", "characterId"],
    indices = [Index("conversationId"), Index("characterId")],
)
data class ConversationMemberEntity(
    val conversationId: Long,
    val characterId: Long,
    val sortIndex: Int = 0,
)

/** 成员 + 角色展示信息（成员面板、气泡头像）。 */
data class MemberWithCharacter(
    @Embedded val member: ConversationMemberEntity,
    val name: String,
    val avatarPath: String?,
    val cardJson: String,
) {
    val characterId: Long get() = member.characterId
    val sortIndex: Int get() = member.sortIndex
}
