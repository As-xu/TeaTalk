package com.teatalk.xs.data

import com.teatalk.xs.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4-1：聊天 `.jsonl` 读写。
 *
 * 样本 `app/src/test/resources/golden/chat_st_sample.jsonl` 直接取自 SillyTavern
 * `data/default-user/backups/`，包含头行 + 3 条消息（其中一轮带 2 个 swipes）。
 */
class ChatJsonlTest {

    private fun sample(): String =
        ChatJsonlTest::class.java.classLoader!!
            .getResourceAsStream("golden/chat_st_sample.jsonl")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun parseStSample() {
        val parsed = ChatJsonl.parse(sample())
        assertEquals(0, parsed.skippedLines)
        assertEquals(3, parsed.messages.size)

        val greeting = parsed.messages[0]
        assertEquals(ChatJsonl.ROLE_ASSISTANT, greeting.role)
        assertEquals(2, greeting.swipes.size)
        assertEquals("GREET-4 Come in, the kettle is on.", greeting.content)
        assertEquals("ALT-9 The door was already open.", greeting.swipes[1])
        assertEquals(0, greeting.swipeId)
        assertTrue(greeting.createdAt > 0L)

        val userLine = parsed.messages[1]
        assertEquals(ChatJsonl.ROLE_USER, userLine.role)
        assertEquals("Tell me about the dragon.", userLine.content)

        val reply = parsed.messages[2]
        assertEquals("mock-model", reply.model)
        assertEquals("custom", reply.api)
        assertEquals("MOCK-REPLY", reply.content)
    }

    @Test
    fun exportRoundTripKeepsVariantsAndRoles() {
        val messages = listOf(
            MessageEntity(
                characterId = 1L,
                conversationId = 2L,
                role = ChatJsonl.ROLE_USER,
                content = "hello",
                createdAt = 1_700_000_000_000L,
            ),
            MessageEntity(
                characterId = 1L,
                conversationId = 2L,
                role = ChatJsonl.ROLE_ASSISTANT,
                content = "v2",
                createdAt = 1_700_000_001_000L,
                variantsJson = MessageVariants.encode(listOf("v1", "v2")),
                selectedVariant = 1,
                model = "deepseek-flash",
            ),
        )
        val exported = ChatJsonl.export("Aria", messages)
        val parsed = ChatJsonl.parse(exported)

        assertEquals(0, parsed.skippedLines)
        assertEquals(2, parsed.messages.size)
        assertEquals(ChatJsonl.ROLE_USER, parsed.messages[0].role)
        assertEquals("hello", parsed.messages[0].content)

        val reply = parsed.messages[1]
        assertEquals("v2", reply.content)
        assertEquals(listOf("v1", "v2"), reply.swipes)
        assertEquals(1, reply.swipeId)
        assertEquals("deepseek-flash", reply.model)
        assertEquals(1_700_000_001_000L, reply.createdAt)
    }

    @Test
    fun parseToleratesBlankLinesAndForeignLines() {
        val text = """
            {"chat_metadata":{},"user_name":"unused","character_name":"unused"}

            not-json
            {"name":"A","is_user":false,"is_system":false,"send_date":"2026-01-01T00:00:00.000Z","mes":"hi","swipes":["hi"],"swipe_id":0}
        """.trimIndent()
        val parsed = ChatJsonl.parse(text)
        assertEquals(1, parsed.messages.size)
        assertEquals(0, parsed.skippedLines)
        assertNotNull(parsed.messages[0].createdAt)
    }
}
