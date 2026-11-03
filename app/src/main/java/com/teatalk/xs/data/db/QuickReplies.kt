package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 快速回复（0.19.0）：一条可复用的消息片段，聊天页点一下插进输入框。
 *
 * 与 ST 的 quick replies 对齐 —— 用户常把「旁白模板」「OOC 指令」「场景切换」存成片段，
 * 避免每次手打。只存文本，不做占位符展开（宏在出站时由 MacroEngine 处理）。
 */
@Entity(tableName = "quick_replies")
data class QuickReplyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 列表里显示的短标题；留空时列表用 content 的首行兜底。 */
    val title: String = "",
    val content: String = "",
    /** 小的先显示。 */
    val sortIndex: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    /** 列表/芯片上的显示名：标题优先，其次内容首行，都空时给占位。 */
    fun displayLabel(): String {
        val t = title.trim()
        if (t.isNotEmpty()) return t
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine
    }
}

@Dao
interface QuickReplyDao {
    @Query("SELECT * FROM quick_replies ORDER BY sortIndex ASC, id ASC")
    fun observeAll(): Flow<List<QuickReplyEntity>>

    @Query("SELECT * FROM quick_replies ORDER BY sortIndex ASC, id ASC")
    suspend fun all(): List<QuickReplyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: QuickReplyEntity): Long

    @Update
    suspend fun update(row: QuickReplyEntity)

    @Delete
    suspend fun delete(row: QuickReplyEntity)

    @Query("DELETE FROM quick_replies WHERE id = :id")
    suspend fun deleteById(id: Long)
}
