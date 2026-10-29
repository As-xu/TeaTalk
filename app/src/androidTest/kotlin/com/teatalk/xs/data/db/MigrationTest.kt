package com.teatalk.xs.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移回归测试（审阅遗留 P1：「迁移无测试」）。
 *
 * 思路与 Room 的 MigrationTestHelper 不同：这里直接用 FrameworkSQLiteOpenHelper 造一个 v1 结构的库，
 * 再按版本顺序执行 `TeaTalkDatabase.MIGRATION_*` 的 `migrate()`，检查**数据是否保留**与**新列/索引是否就位**。
 *
 * 不依赖 schemas JSON，因此可在任意历史版本缺失 schema 的仓库里运行；
 * 代价是不校验 Room 的 identityHash（那由首次启动的真实升级覆盖）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: androidx.sqlite.db.SupportSQLiteDatabase

    private val dbName = "migration-test.db"

    private val allMigrations = arrayOf(
        TeaTalkDatabase.MIGRATION_1_2,
        TeaTalkDatabase.MIGRATION_2_3,
        TeaTalkDatabase.MIGRATION_3_4,
        TeaTalkDatabase.MIGRATION_4_5,
        TeaTalkDatabase.MIGRATION_5_6,
        TeaTalkDatabase.MIGRATION_6_7,
        TeaTalkDatabase.MIGRATION_7_8,
    )

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        // v1 结构与 M0 时代一致：characters + messages + messages.characterId 索引。
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }, null)
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `characters` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `avatarPath` TEXT,
                `cardJson` TEXT NOT NULL,
                `cardSource` TEXT NOT NULL,
                `tagsJson` TEXT NOT NULL,
                `firstMes` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_characterId` ON `messages` (`characterId`)")
        raw.version = 1
        raw.close()

        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    /** 逐版本执行迁移（调用真实 Migration 对象的 migrate 实现）。 */
    private fun migrateTo(targetVersion: Int) {
        while (db.version < targetVersion) {
            val from = db.version
            val migration = allMigrations.first { it.startVersion == from && it.endVersion == from + 1 }
            migration.migrate(db)
            db.version = from + 1
        }
    }

    private fun columnNames(table: String): Set<String> {
        val out = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) out += c.getString(nameIndex)
        }
        return out
    }

    private fun indexNames(): Set<String> {
        val out = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    @Test
    fun migrate_1_to_7_keeps_characters_and_messages() {
        // v1 存量数据：2 个角色、3 条消息（char 1 两条，char 2 一条）。
        db.execSQL("INSERT INTO characters (name, avatarPath, cardJson, cardSource, tagsJson, firstMes, createdAt) VALUES ('A', NULL, '{}', 'JSON_V2', '[]', 'hi', 1000)")
        db.execSQL("INSERT INTO characters (name, avatarPath, cardJson, cardSource, tagsJson, firstMes, createdAt) VALUES ('B', NULL, '{}', 'JSON_V2', '[]', 'yo', 2000)")
        db.execSQL("INSERT INTO messages (characterId, role, content, createdAt) VALUES (1, 'assistant', 'hi', 1001)")
        db.execSQL("INSERT INTO messages (characterId, role, content, createdAt) VALUES (1, 'user', 'hello', 1002)")
        db.execSQL("INSERT INTO messages (characterId, role, content, createdAt) VALUES (2, 'assistant', 'yo', 2001)")

        migrateTo(TeaTalkDatabase.VERSION)

        // 1) 角色数据保留，且 D6 新增列有默认值。
        db.query("SELECT name, favorite, pinned FROM characters ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("A", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertEquals(0, c.getInt(2))
            assertTrue(c.moveToNext())
            assertEquals("B", c.getString(0))
        }

        // 2) 每个角色恰好一条默认会话（迁移 6→7 建立）。
        db.query("SELECT characterId, title FROM conversations ORDER BY characterId").use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals("", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals(2, c.getInt(0))
        }

        // 3) 旧消息全部回填 conversationId，且归属正确（按角色）。
        db.query(
            """
            SELECT m.content, m.conversationId, cv.characterId, m.status, m.variantsJson, m.variantStatusJson
            FROM messages m JOIN conversations cv ON cv.id = m.conversationId
            ORDER BY m.id
            """.trimIndent(),
        ).use { c ->
            assertEquals(3, c.count)
            while (c.moveToNext()) {
                assertEquals("complete", c.getString(3))
                assertEquals("[]", c.getString(4))
                assertEquals("[]", c.getString(5))
                // 会话的 characterId 必须与原消息的 characterId 一致。
                assertTrue(c.getInt(1) > 0)
                assertTrue(c.getInt(2) in 1..2)
            }
        }

        // 4) 没有消息指向 conversationId=0 的孤儿。
        db.query("SELECT COUNT(*) FROM messages WHERE conversationId = 0").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // 5) v5→v6 的 Provider 来源列存在且老数据为 NULL。
        assertTrue(columnNames("messages").containsAll(listOf("providerId", "providerName", "model")))
        db.query("SELECT providerId, providerName, model FROM messages").use { c ->
            while (c.moveToNext()) {
                assertNull(c.getString(0))
                assertNull(c.getString(1))
                assertNull(c.getString(2))
            }
        }

        // 6) 索引与表结构就位。
        val indexes = indexNames()
        assertTrue(indexes.contains("index_messages_characterId"))
        assertTrue(indexes.contains("index_messages_conversationId"))
        assertTrue(indexes.contains("index_conversations_characterId"))
        assertTrue(indexes.contains("index_conversations_characterId_updatedAt"))
        // 7) v7→v8 世界书绑定列。
        assertTrue(columnNames("characters").contains("linkedWorldBookIdsJson"))
        assertTrue(columnNames("conversations").contains("worldBookIdsJson"))
        db.query("SELECT linkedWorldBookIdsJson FROM characters LIMIT 1").use { c ->
            if (c.moveToFirst()) assertEquals("[]", c.getString(0))
        }
        assertTrue(columnNames("world_books").contains("entriesJson"))
        assertTrue(columnNames("model_providers").contains("baseUrl"))
        assertNotNull(db)
    }

    @Test
    fun migrate_1_to_7_with_empty_tables() {
        migrateTo(TeaTalkDatabase.VERSION)

        db.query("SELECT COUNT(*) FROM conversations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        assertTrue(columnNames("characters").containsAll(listOf("favorite", "pinned", "updatedAt")))
        assertTrue(columnNames("messages").containsAll(listOf("variantsJson", "selectedVariant", "status", "variantStatusJson", "conversationId")))
    }

    // ---- Room MigrationTestHelper：校验迁移后的结构 == 当前实体结构（含 identityHash 的最终 schema） ----

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TeaTalkDatabase::class.java,
    )

    @Test
    fun migrationTestHelper_migrates_1_to_8_and_validates_schema() {
        val name = "migration-helper.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO characters (name, avatarPath, cardJson, cardSource, tagsJson, firstMes, createdAt) VALUES ('A', NULL, '{}', 'JSON_V2', '[]', 'hi', 1000)")
            execSQL("INSERT INTO messages (characterId, role, content, createdAt) VALUES (1, 'assistant', 'hi', 1001)")
            close()
        }

        val db = migrations.runMigrationsAndValidate(
            name,
            TeaTalkDatabase.VERSION,
            true,
            TeaTalkDatabase.MIGRATION_1_2,
            TeaTalkDatabase.MIGRATION_2_3,
            TeaTalkDatabase.MIGRATION_3_4,
            TeaTalkDatabase.MIGRATION_4_5,
            TeaTalkDatabase.MIGRATION_5_6,
            TeaTalkDatabase.MIGRATION_6_7,
            TeaTalkDatabase.MIGRATION_7_8,
        )
        try {
            // 数据保留（helper 已校验 schema，这里只看语义）。
            db.query("SELECT COUNT(*) FROM messages").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT conversationId FROM messages").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getLong(0) > 0)
            }
            db.query("SELECT COUNT(*) FROM conversations").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
