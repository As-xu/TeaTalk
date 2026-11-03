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
 * 正则脚本（V1 正则/变量）：对齐 SillyTavern 的「Regex Extension」基本用法。
 *
 * 只覆盖最常用的三件事：出站改写（prompt）、入站显示改写（display）、两者都改（both）。
 * 刻意不做 ST 的「只改用户输入 / 只改模型输出 / 只改命令」这些细分，避免设置项爆炸。
 */
@Entity(tableName = "regex_rules")
data class RegexRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** Java 正则；允许使用 `$1` 之类的捕获组引用（Replacement 走 String.replaceAll 语义）。 */
    val pattern: String,
    val replacement: String,
    /** g / i / m / s 的组合（如 "gi"）；不含 g 时只替换第一处。 */
    val flags: String = "g",
    /** prompt / display / both，见 [SCOPE_PROMPT] 等常量。 */
    val scope: String = SCOPE_PROMPT,
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
    val createdAt: Long = 0L,
) {
    companion object {
        /** 出站：对发给模型的 prompt 文本生效（不改库）。 */
        const val SCOPE_PROMPT = "prompt"

        /** 入站：只影响气泡显示（库里的原文不变，导出/搜索仍是原文）。 */
        const val SCOPE_DISPLAY = "display"

        const val SCOPE_BOTH = "both"

        val SCOPES = listOf(SCOPE_PROMPT, SCOPE_DISPLAY, SCOPE_BOTH)
    }
}

/**
 * 会话变量（V1 变量宏）：`{{getvar::名}}` / `{{setvar::名::值}}` 的持久化落点。
 *
 * 按会话隔离：同一张角色卡开两条会话，变量互不干扰（ST 的 chat 变量语义）。
 */
@Entity(tableName = "chat_variables", primaryKeys = ["conversationId", "key"])
data class ChatVariableEntity(
    val conversationId: Long,
    val key: String,
    val value: String,
    val updatedAt: Long = 0L,
)

@Dao
interface RegexRuleDao {
    @Query("SELECT * FROM regex_rules ORDER BY sortIndex ASC, id ASC")
    fun observeAll(): Flow<List<RegexRuleEntity>>

    @Query("SELECT * FROM regex_rules ORDER BY sortIndex ASC, id ASC")
    suspend fun all(): List<RegexRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RegexRuleEntity): Long

    @Update
    suspend fun update(rule: RegexRuleEntity)

    @Delete
    suspend fun delete(rule: RegexRuleEntity)

    @Query("DELETE FROM regex_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChatVariableDao {
    @Query("SELECT * FROM chat_variables WHERE conversationId = :conversationId")
    suspend fun all(conversationId: Long): List<ChatVariableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ChatVariableEntity>)

    @Query("DELETE FROM chat_variables WHERE conversationId = :conversationId AND `key` = :key")
    suspend fun delete(conversationId: Long, key: String)

    @Query("SELECT COUNT(*) FROM chat_variables WHERE conversationId = :conversationId")
    suspend fun count(conversationId: Long): Int
}
