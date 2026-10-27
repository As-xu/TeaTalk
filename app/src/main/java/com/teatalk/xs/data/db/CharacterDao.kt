package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
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

    // —— D6 卡库：收藏 / 置顶 / 卡片编辑 ——

    @Query("UPDATE characters SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, updatedAt: Long)

    @Query("UPDATE characters SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    /** 编辑保存：只覆盖 name/tags/firstMes/cardJson 四个列，头像与来源不变。 */
    @Query(
        """
        UPDATE characters
        SET name = :name, tagsJson = :tagsJson, firstMes = :firstMes,
            cardJson = :cardJson, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateCard(id: Long, name: String, tagsJson: String, firstMes: String, cardJson: String, updatedAt: Long)

    /** 迁移后的标签回填：v1 存量卡的 tagsJson 可能是空数组，用 cardJson 重新抽。 */
    @Query("SELECT * FROM characters WHERE tagsJson = '[]' OR tagsJson = ''")
    suspend fun charactersWithoutTags(): List<CharacterEntity>

    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(characterId: Long): Flow<List<MessageEntity>>

    /** 一次性取最近 N 条（组装 prompt 用），按时间正序返回。 */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages WHERE characterId = :characterId
            ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt ASC, id ASC
        """,
    )
    suspend fun recentMessages(characterId: Long, limit: Int): List<MessageEntity>

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

    // —— M2 多版本 / 重试 / 删除 ——

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findMessage(id: Long): MessageEntity?

    /** 某角色最后一条消息（按时间倒序），用于「重试上一条回复」。 */
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMessage(characterId: Long): MessageEntity?

    @Query("UPDATE messages SET content = :content, variantsJson = :variantsJson, selectedVariant = :selectedVariant, status = :status WHERE id = :id")
    suspend fun updateVariants(id: Long, content: String, variantsJson: String, selectedVariant: Int, status: String)

    @Query("UPDATE messages SET content = :content, selectedVariant = :selectedVariant WHERE id = :id")
    suspend fun selectVariant(id: Long, content: String, selectedVariant: Int)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

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
