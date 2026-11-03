package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("UPDATE conversations SET characterId = :characterId WHERE id = :id")
    suspend fun setCharacterId(id: Long, characterId: Long)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** M3：会话级世界书覆盖；null 表示继承角色绑定。 */
    @Query("SELECT * FROM conversations")
    suspend fun allOnce(): List<ConversationEntity>

    /** 级联清理绑定：不触碰 updatedAt，避免把会话顶到列表最前。 */
    @Query("UPDATE conversations SET worldBookIdsJson = :json WHERE id = :id")
    suspend fun setWorldBooksQuiet(id: Long, json: String?)

    @Query("UPDATE conversations SET worldBookIdsJson = :json, updatedAt = :at WHERE id = :id")
    suspend fun setWorldBooks(id: Long, json: String?, at: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: Long)

    // ---- group chats（M4）----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ConversationMemberEntity>)

    @Query(
        """SELECT m.*, ch.name AS name, ch.avatarPath AS avatarPath, ch.cardJson AS cardJson
           FROM conversation_members m
           INNER JOIN characters ch ON ch.id = m.characterId
           WHERE m.conversationId = :conversationId
           ORDER BY m.sortIndex ASC, m.characterId ASC"""
    )
    fun observeMembers(conversationId: Long): Flow<List<MemberWithCharacter>>

    @Query(
        """SELECT m.*, ch.name AS name, ch.avatarPath AS avatarPath, ch.cardJson AS cardJson
           FROM conversation_members m
           INNER JOIN characters ch ON ch.id = m.characterId
           WHERE m.conversationId = :conversationId
           ORDER BY m.sortIndex ASC, m.characterId ASC"""
    )
    suspend fun membersOnce(conversationId: Long): List<MemberWithCharacter>

    @Query("SELECT MAX(sortIndex) FROM conversation_members WHERE conversationId = :conversationId")
    suspend fun maxSortIndex(conversationId: Long): Int?

    @Query("DELETE FROM conversation_members WHERE conversationId = :conversationId AND characterId = :characterId")
    suspend fun deleteMember(conversationId: Long, characterId: Long)

    @Query("DELETE FROM conversation_members WHERE conversationId = :conversationId")
    suspend fun deleteMembers(conversationId: Long)

    /** 分支复制用：原始成员行（membersOnce 是 join 投影，不能回写）。 */
    @Query("SELECT * FROM conversation_members WHERE conversationId = :conversationId")
    suspend fun membersOnly(conversationId: Long): List<ConversationMemberEntity>

    @Query("SELECT COUNT(*) FROM conversation_members WHERE conversationId = :conversationId")
    suspend fun memberCount(conversationId: Long): Int

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countAll(): Int

    @Insert
    suspend fun insertMessagesExact(messages: List<MessageEntity>)

    /** 会话与消息必须同成同败：失败时不能留下一条空会话。 */
    @Transaction
    suspend fun insertWithMessages(conversation: ConversationEntity, messages: List<MessageEntity>): Long {
        val id = insert(conversation)
        insertMessagesExact(messages.map { it.copy(conversationId = id) })
        return id
    }

    /**
     * 聊天列表：每条会话一行，带角色名/头像（用于气泡左侧）与最后一条消息预览。
     *
     * 最后一条消息的取法沿用 D6 的子查询：`MAX(createdAt)` 后再兜 `id`，避免同一毫秒插入时顺序抖动。
     */
    @Query(
        """
        SELECT c.*, ch.name AS characterName, ch.avatarPath AS characterAvatar,
            (SELECT COUNT(*) FROM conversation_members cm WHERE cm.conversationId = c.id) AS memberCount,
            (SELECT m.content FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastContent,
            (SELECT m.createdAt FROM messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastAt
        FROM conversations c
        INNER JOIN characters ch ON ch.id = c.characterId
        ORDER BY lastAt DESC, c.updatedAt DESC, c.id DESC
        """
    )
    fun observeChats(): Flow<List<ConversationWithLastMessage>>

    /** 全库消息搜索（按时间倒序，限制条数，避免大库全表扫描后一次取回）。 */
    @Query(
        """
        SELECT m.conversationId AS conversationId, m.id AS messageId,
            c.title AS conversationTitle, ch.name AS characterName,
            ch.avatarPath AS characterAvatar, m.content AS content, m.createdAt AS createdAt
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        INNER JOIN characters ch ON ch.id = c.characterId
        WHERE m.content LIKE '%' || :query || '%'
        ORDER BY m.createdAt DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessages(query: String, limit: Int): List<MessageSearchHit>
}
