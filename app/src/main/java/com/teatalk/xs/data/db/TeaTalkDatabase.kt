package com.teatalk.xs.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CharacterEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TeaTalkDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
}
