package com.teatalk.xs.data

import com.teatalk.xs.data.db.ConversationEntity
import com.teatalk.xs.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.20.0 分支树：纯规划逻辑的单测（不碰 Room）。 */
class BranchForkTest {

    private val source = ConversationEntity(
        id = 7L,
        characterId = 3L,
        title = "主线",
        createdAt = 100L,
        updatedAt = 200L,
    )

    private fun message(id: Long, at: Long, text: String = "m$id", role: String = "user") =
        MessageEntity(
            id = id,
            characterId = 3L,
            conversationId = 7L,
            role = role,
            content = text,
            createdAt = at,
        )

    @Test
    fun keepsPrefixUpToForkPointOnly() {
        val messages = listOf(message(1, 110), message(2, 120), message(3, 130), message(4, 140))
        val plan = BranchFork.plan(source, messages, upToMessageId = 2, label = null, now = 999L)
        assertEquals(listOf("m1", "m2"), plan!!.messages.map { it.content })
        assertEquals(2L, plan.conversation.forkMessageId)
        assertEquals(7L, plan.conversation.parentConversationId)
        assertEquals(0L, plan.conversation.id)
        assertEquals(999L, plan.conversation.createdAt)
    }

    @Test
    fun missingTargetReturnsNull() {
        val plan = BranchFork.plan(source, listOf(message(1, 110)), upToMessageId = 99, label = null, now = 1L)
        assertNull(plan)
    }

    @Test
    fun labelBecomesTitleAndIsTrimmed() {
        val plan = BranchFork.plan(source, listOf(message(1, 110)), upToMessageId = 1, label = "  温泉线  ", now = 1L)
        assertEquals("温泉线", plan!!.conversation.title)
        assertEquals("温泉线", plan.conversation.forkLabel)
    }

    @Test
    fun blankLabelFallsBackToSourceTitle() {
        val plan = BranchFork.plan(source, listOf(message(1, 110)), upToMessageId = 1, label = "   ", now = 1L)
        assertEquals("主线", plan!!.conversation.title)
        assertNull(plan.conversation.forkLabel)
    }

    @Test
    fun overlongLabelIsTruncated() {
        val long = "x".repeat(BranchFork.MAX_LABEL + 10)
        val plan = BranchFork.plan(source, listOf(message(1, 110)), upToMessageId = 1, label = long, now = 1L)
        assertEquals(BranchFork.MAX_LABEL, plan!!.conversation.forkLabel!!.length)
    }

    @Test
    fun copiedMessagesGetFreshIdsAndKeepSpeaker() {
        val messages = listOf(
            message(1, 110),
            message(2, 120, role = "assistant").copy(speakerId = 5L),
        )
        val plan = BranchFork.plan(source, messages, upToMessageId = 2, label = null, now = 1L)
        assertTrue(plan!!.messages.all { it.id == 0L && it.conversationId == 0L })
        assertEquals(5L, plan.messages.last().speakerId)
    }

    @Test
    fun sameTimestampUsesIdOrder() {
        // 同一毫秒内的多条消息按 id 递增；乱序传入也应只留 id <= 2 的两条。
        val messages = listOf(message(3, 110), message(1, 110), message(2, 110))
        val plan = BranchFork.plan(source, messages, upToMessageId = 2, label = null, now = 1L)
        assertEquals(listOf("m1", "m2"), plan!!.messages.map { it.content })
    }
}
