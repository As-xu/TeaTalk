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
        ConversationEntity::class,
        ConversationMemberEntity::class,
        RegexRuleEntity::class,
        ChatVariableEntity::class,
        QuickReplyEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class TeaTalkDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun regexRuleDao(): RegexRuleDao
    abstract fun chatVariableDao(): ChatVariableDao
    abstract fun quickReplyDao(): QuickReplyDao

    companion object {
        const val VERSION = 12

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

        /**
         * v3 → v4（M2 多版本）：`messages` 增加三列（纯增量）。
         * 存量消息 variantsJson=[]、selectedVariant=0、status=complete，语义不变。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE messages ADD COLUMN variantsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE messages ADD COLUMN selectedVariant INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'complete'")
                TeaTalkLog.i("Db", "db.migrate", "from" to 3, "to" to 4, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v4 → v5（M2 变体级状态）：`messages` 增加 `variantStatusJson`（纯增量）。
         * 存量消息为空数组：读取时按 complete 补齐与 variantsJson 等长。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE messages ADD COLUMN variantStatusJson TEXT NOT NULL DEFAULT '[]'")
                TeaTalkLog.i("Db", "db.migrate", "from" to 4, "to" to 5, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v5 → v6（M2 B2 Provider 切换兜底）：记录每条回复由哪个接口/模型生成。
         * 可空列，老数据为 null（UI 不显示来源提示）。
         */

        /**
         * v6 → v7（M2 多会话）：会话成为消息的归属单位。
         *
         * 迁移策略（不丢历史）：
         * 1. 每个角色建一条默认会话（标题留空，UI 回退角色名）；
         * 2. 旧消息先加 `conversationId` 列（默认 0），再回填到该角色的默认会话；
         * 3. `characterId` 保留在消息上作为冗余外键，列表查询不 join 会话表。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_characterId` ON `conversations` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_characterId_updatedAt` ON `conversations` (`characterId`, `updatedAt`)")
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
                db.execSQL(
                    """
                    INSERT INTO conversations (characterId, title, createdAt, updatedAt)
                    SELECT id, '', createdAt, CASE WHEN updatedAt > 0 THEN updatedAt ELSE createdAt END
                    FROM characters
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE messages SET conversationId = (
                        SELECT cv.id FROM conversations cv
                        WHERE cv.characterId = messages.characterId
                        ORDER BY cv.id ASC LIMIT 1
                    ) WHERE conversationId = 0
                    """.trimIndent(),
                )
                TeaTalkLog.i("Db", "db.migrate", "from" to 6, "to" to 7, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v7（多会话）→ v8（M3 世界书绑定）：
         * - `characters` 增加 `linkedWorldBookIdsJson`（角色默认绑定的世界书 id 数组，默认 `[]`）
         * - `conversations` 增加 `worldBookIdsJson`（会话级覆盖；NULL = 继承角色）
         *
         * 纯增量列，不改旧数据；已有会话的 NULL 表示继续继承，行为与 v7 完全一致。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE characters ADD COLUMN linkedWorldBookIdsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN worldBookIdsJson TEXT")
                TeaTalkLog.i("Db", "db.migrate", "from" to 7, "to" to 8, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v9 → v10（M4 group chats）：
         * - conversations.kind：single/group；characterId 对群聊记第一个成员。
         * - conversation_members：群聊成员（主键 conversationId+characterId，sortIndex 决定 prompt 顺序）。
         * - messages.speakerId：群聊里这条 assistant 消息由谁说出（单人会话为 NULL）。
         *
         * 存量数据：每条既有会话补一条「成员 = 其 role characterId」的记录，保证单人会话行为不变。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE conversations ADD COLUMN kind TEXT NOT NULL DEFAULT 'single'")
                db.execSQL("ALTER TABLE messages ADD COLUMN speakerId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_members (" +
                        "conversationId INTEGER NOT NULL, " +
                        "characterId INTEGER NOT NULL, " +
                        "sortIndex INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(conversationId, characterId))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_members_conversationId ON conversation_members(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_members_characterId ON conversation_members(characterId)")
                db.execSQL(
                    "INSERT OR IGNORE INTO conversation_members(conversationId, characterId, sortIndex) " +
                        "SELECT id, characterId, 0 FROM conversations",
                )
                TeaTalkLog.i("Db", "db.migrate", "from" to 9, "to" to 10, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v10 → v11（V1 正则/变量）：
         * - `regex_rules`：出站/入站正则脚本（纯新增表）
         * - `chat_variables`：会话级变量（`{{getvar}}`/`{{setvar}}` 的落点，按会话隔离）
         *
         * 两张表都是新增，不改既有列，迁移是纯增量。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS regex_rules (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "pattern TEXT NOT NULL, " +
                        "replacement TEXT NOT NULL, " +
                        "flags TEXT NOT NULL, " +
                        "scope TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "sortIndex INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chat_variables (" +
                        "conversationId INTEGER NOT NULL, " +
                        "`key` TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(conversationId, `key`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_variables_conversationId " +
                        "ON chat_variables(conversationId)",
                )
                TeaTalkLog.i("Db", "db.migrate", "from" to 10, "to" to 11, "ms" to (System.currentTimeMillis() - started))
            }
        }

        /**
         * v11（0.18.0 正则/变量）→ v12（0.19.0 快速回复）：纯新增表。
         *
         * 建表 SQL 不写 DEFAULT、列全部 NOT NULL，与 `QuickReplyEntity` 对齐
         * —— 0.18.0 的 `Migration didn't properly handle` 就是这么踩的。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quick_replies (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "sortIndex INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
                TeaTalkLog.i("Db", "db.migrate", "from" to 11, "to" to 12, "ms" to (System.currentTimeMillis() - started))
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE model_providers ADD COLUMN topP REAL")
                db.execSQL("ALTER TABLE model_providers ADD COLUMN maxTokens INTEGER")
                db.execSQL("ALTER TABLE model_providers ADD COLUMN frequencyPenalty REAL")
                db.execSQL("ALTER TABLE model_providers ADD COLUMN presencePenalty REAL")
                TeaTalkLog.i("Db", "db.migrate", "from" to 8, "to" to 9, "ms" to (System.currentTimeMillis() - started))
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val started = System.currentTimeMillis()
                db.execSQL("ALTER TABLE messages ADD COLUMN providerId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN providerName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
                TeaTalkLog.i("Db", "db.migrate", "from" to 5, "to" to 6, "ms" to (System.currentTimeMillis() - started))
            }
        }
    }
}
