package com.teatalk.xs.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一段会话（M2 多会话）。
 *
 * 一个角色可以有多个会话：同一个角色卡，开一条「正经线」和一条「平行世界线」是常见用法。
 * 消息通过 `messages.conversationId` 归属到会话；角色本身不再直接持有消息。
 *
 * `updatedAt` 在每次收发消息时刷新，用于「聊天列表」排序与会话切换面板排序。
 */
@Entity(
    tableName = "conversations",
    indices = [Index("characterId"), Index(value = ["characterId", "updatedAt"])],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val characterId: Long,
    /** 会话标题；为空时 UI 回退到角色名。 */
    val title: String = "",
    /**
     * M3：会话级世界书覆盖（JSON 数组字符串）。
     * null = 继承角色默认绑定；`[]` = 显式不启用；`[1,2]` = 只用这两本。
     */
    val worldBookIdsJson: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
