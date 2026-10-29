package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /** M3：角色默认世界书绑定（JSON 数组字符串）。 */
    @Query("UPDATE characters SET linkedWorldBookIdsJson = :json, updatedAt = :at WHERE id = :id")
    suspend fun setLinkedWorldBooks(id: Long, json: String, at: Long)

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

    // —— 消息（M2 起按会话归属） ——

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    /** 一次性取最近 N 条（组装 prompt 用），按时间正序返回。 */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages WHERE conversationId = :conversationId
            ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt ASC, id ASC
        """,
    )
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countMessages(conversationId: Long): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * 原子地"没有消息才插开场白"。
     * @return true=插入成功；false=已有消息，未插入。
     * 用 @Transaction 消除 count→insert 竞态（详情页连点两次「开始聊天」不会再插两条）。
     */
    @Transaction
    suspend fun insertGreetingIfEmpty(
        conversationId: Long,
        characterId: Long,
        content: String,
        createdAt: Long,
    ): Boolean {
        if (countMessages(conversationId) > 0L) return false
        insertMessage(
            MessageEntity(
                characterId = characterId,
                conversationId = conversationId,
                role = "assistant",
                content = content,
                createdAt = createdAt,
            ),
        )
        return true
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)

    /** 会话的全部消息（删除会话前做快照，用于撤销）。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun allMessages(conversationId: Long): List<MessageEntity>

    /** 按原 id 批量写回（撤销删除）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessagesExact(messages: List<MessageEntity>)

    /** 删角色时连带清空其所有会话的消息（会话行由 ConversationDao 删）。 */
    @Query("DELETE FROM messages WHERE characterId = :characterId")
    suspend fun deleteMessagesForCharacter(characterId: Long)

    // —— M2 多版本 / 重试 / 删除 ——

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findMessage(id: Long): MessageEntity?

    /** 某会话最后一条消息（按时间倒序），用于「重试上一条回复」。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMessage(conversationId: Long): MessageEntity?

    @Query(
        "UPDATE messages SET content = :content, variantsJson = :variantsJson, variantStatusJson = :variantStatusJson, selectedVariant = :selectedVariant, status = :status, providerId = :providerId, providerName = :providerName, model = :model WHERE id = :id",
    )
    suspend fun updateVariants(
        id: Long,
        content: String,
        variantsJson: String,
        variantStatusJson: String,
        selectedVariant: Int,
        status: String,
        providerId: String?,
        providerName: String?,
        model: String?,
    )

    @Query("UPDATE messages SET content = :content, selectedVariant = :selectedVariant, status = :status WHERE id = :id")
    suspend fun selectVariant(id: Long, content: String, selectedVariant: Int, status: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)
/** 是否存在任何消息（列表口径 = 至少一条消息），用于启动落点，避免扫全表。 */
    @Query("SELECT EXISTS(SELECT 1 FROM messages LIMIT 1)")
    suspend fun anyMessageExists(): Boolean
}
