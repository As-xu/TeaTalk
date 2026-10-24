package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): CharacterEntity?

    @Insert
    suspend fun insert(character: CharacterEntity): Long

    @Delete
    suspend fun delete(character: CharacterEntity)
}
