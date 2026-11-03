package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 翻译缓存（DB v14）。
 *
 * 只缓存「给他看」的译文：同一段原文 + 目标语言只翻一次，重新生成/翻历史/重进会话都不重复计费。
 * [hash] 由 `TranslationKey.hash(targetLang, text)` 生成，所以同一段文本翻成不同语言互不覆盖。
 */
@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey val hash: String,
    val targetLang: String,
    /** 译文。原文永远在消息表里，不复制进来。 */
    val text: String,
    /** 产出译文的 provider id，便于排查「这段是哪个接口翻的」。 */
    val engine: String,
    val createdAt: Long = 0L,
)

@Dao
interface TranslationDao {

    @Query("SELECT * FROM translations WHERE hash = :hash")
    suspend fun find(hash: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TranslationEntity)

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun count(): Int

    @Query("DELETE FROM translations")
    suspend fun clear()
}
