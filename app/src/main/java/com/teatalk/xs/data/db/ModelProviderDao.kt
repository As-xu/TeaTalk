package com.teatalk.xs.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelProviderDao {

    @Query("SELECT * FROM model_providers ORDER BY lastUsedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<ModelProviderEntity>>

    @Query("SELECT * FROM model_providers WHERE id = :id")
    suspend fun find(id: String): ModelProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ModelProviderEntity)

    @Query("DELETE FROM model_providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE model_providers SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)
}
