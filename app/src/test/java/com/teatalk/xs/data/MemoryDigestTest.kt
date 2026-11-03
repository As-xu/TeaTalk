package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDigestTest {

    @Test
    fun emptyInputProducesNoDigest() {
        assertNull(MemoryDigest.build(emptyList()))
    }

    @Test
    fun blankMessagesAreIgnored() {
        assertNull(MemoryDigest.build(listOf("user" to "  ", "assistant" to "\n")))
    }

    @Test
    fun keepsHeaderAndLabels() {
        val digest = MemoryDigest.build(
            listOf("user" to "hello", "assistant" to "hi there"),
        )
        assertTrue(digest!!.startsWith(MemoryDigest.HEADER))
        assertTrue(digest.contains("- User: hello"))
        assertTrue(digest.contains("- Assistant: hi there"))
    }

    @Test
    fun samplingNeverExceedsMaxLines() {
        val older = (1..200).map { "user" to "message $it" }
        val digest = MemoryDigest.build(older)!!
        val lines = digest.split('\n').drop(1)
        assertTrue("lines=${lines.size}", lines.size <= MemoryDigest.MAX_LINES)
    }

    @Test
    fun longMessageIsTruncatedToOneLine() {
        val long = "a".repeat(400) + "\nsecond line"
        val digest = MemoryDigest.build(listOf("assistant" to long))!!
        val body = digest.split('\n')[1]
        assertTrue(body.endsWith("…"))
        assertTrue(body.length <= MemoryDigest.MAX_LINE_CHARS + 20)
    }

    @Test
    fun newestMessagesAreKeptWhenSampling() {
        val older = (1..20).map { "user" to "m$it" }
        val digest = MemoryDigest.build(older)!!
        assertTrue(digest.contains("m20"))
    }

    @Test
    fun totalLengthIsBounded() {
        val older = (1..50).map { "assistant" to "x".repeat(500) }
        val digest = MemoryDigest.build(older)!!
        assertTrue("len=${digest.length}", digest.length <= MemoryDigest.MAX_TOTAL_CHARS + 40)
    }
}
