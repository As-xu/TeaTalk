package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): CharacterEntity?

    /** 详情页/聊天页订阅单个角色（不存在或已被删除时为 null）。 */
    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<CharacterEntity?>

    @Insert
    suspend fun insert(character: CharacterEntity): Long

    @Delete
    suspend fun delete(character: CharacterEntity)

    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(characterId: Long): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE characterId = :characterId")
    suspend fun countMessages(characterId: Long): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * 原子地"没有消息才插开场白"。
     * @return true=插入成功；false=已有消息，未插入。
     * 用 @Transaction 消除 count→insert 竞态（详情页连点两次「开始聊天」不会再插两条）。
     */
    @Transaction
    suspend fun insertGreetingIfEmpty(characterId: Long, content: String, createdAt: Long): Boolean {
        if (countMessages(characterId) > 0L) return false
        insertMessage(
            MessageEntity(characterId = characterId, role = "assistant", content = content, createdAt = createdAt),
        )
        return true
    }

    @Query("DELETE FROM messages WHERE characterId = :characterId")
    suspend fun deleteMessages(characterId: Long)

    /** 是否有任何会话（决定首屏进「聊天」还是「角色」）。 */
    @Query("SELECT COUNT(DISTINCT characterId) FROM messages")
    suspend fun countChats(): Int

    /** 聊天列表：只列有过消息的角色，按最后一条消息时间倒序。 */
    @Query(
        """
        SELECT c.*,
            (SELECT m.content FROM messages m WHERE m.characterId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastContent,
            (SELECT m.createdAt FROM messages m WHERE m.characterId = c.id ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastAt
        FROM characters c
        WHERE EXISTS (SELECT 1 FROM messages m2 WHERE m2.characterId = c.id)
        ORDER BY lastAt DESC
        """,
    )
    fun observeChats(): Flow<List<CharacterWithLastMessage>>
}
