package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 会话表；消息本体仍在 `messages`，这里只负责会话的生命周期与列表。 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC, id DESC")
    fun observeByCharacter(characterId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE characterId = :characterId ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun latest(characterId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun find(id: Long): ConversationEntity?

    /** 订阅单条会话（聊天页始终显示角色名/头像，需要会话→角色）。 */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    /** 撤销删除时按原 id 写回（REPLACE 防止并发下撞主键）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExact(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, title: String, at: Long)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** M3：会话级世界书覆盖；null 表示继承角色绑定。 */
    @Query("UPDATE conversations SET worldBookIdsJson = :json, updatedAt = :at WHERE id = :id")
    suspend fun setWorldBooks(id: Long, json: String?, at: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countAll(): Int

    /**
     * 聊天列表：每条会话一行，带角色名/头像（用于气泡左侧）与最后一条消息预览。
     *
     * 最后一条消息的取法沿用 D6 的子查询：`MAX(createdAt)` 后再兜 `id`，避免同一毫秒插入时顺序抖动。
     */
    @Query(
        """
        SELECT c.*, ch.name AS characterName, ch.avatarPath AS characterAvatar,
            (SELECT m.content FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastContent,
            (SELECT m.createdAt FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastAt
        FROM conversations c
        INNER JOIN characters ch ON ch.id = c.characterId
        ORDER BY lastAt DESC, c.updatedAt DESC, c.id DESC
        """
    )
    fun observeChats(): Flow<List<ConversationWithLastMessage>>
}
