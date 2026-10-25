package com.teatalk.xs.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teatalk.core.log.TeaTalkLog

@Database(
    entities = [
        CharacterEntity::class,
        MessageEntity::class,
        WorldBookEntity::class,
        ModelProviderEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class TeaTalkDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun modelProviderDao(): ModelProviderDao

    companion object {
        const val VERSION = 3

        /**
         * v1（M0）→ v2（D6 卡库）：
         * - `characters` 增加 favorite / pinned / updatedAt（存量数据 `updatedAt=0`，排序回退到 createdAt）
         * - 新建 `world_books`
         *
         * 注意：不改已有列、不重写 cardJson，迁移是纯增量，风险低。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE characters ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `world_books` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `entriesJson` TEXT NOT NULL,
                        `entryCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                TeaTalkLog.i(
                    "Db",
                    "db.migrate",
                    "from" to 1,
                    "to" to 2,
                    "ms" to (System.currentTimeMillis() - started),
                )
            }
        }

        /**
         * v2（D6）→ v3（M1 多 Provider）：
         * - 新建 `model_providers`（key 不在库里，存 EncryptedSharedPreferences）
         * - 纯增量，不触碰既有表
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `model_providers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `apiFormat` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `defaultModel` TEXT NOT NULL,
                        `modelsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                TeaTalkLog.i("Db", "db.migrate", "from" to 2, "to" to 3, "ms" to (System.currentTimeMillis() - started))
            }
        }
    }
}
