package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldBookDao {

    @Query("SELECT * FROM world_books ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<WorldBookEntity>>

    @Query("SELECT * FROM world_books WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): WorldBookEntity?

    @Insert
    suspend fun insert(book: WorldBookEntity): Long

    @Query("DELETE FROM world_books WHERE id = :id")
    suspend fun deleteById(id: Long)
}
