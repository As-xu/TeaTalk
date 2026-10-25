package com.teatalk.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseTest {

    @Test
    fun keepsDataPayload() {
        assertEquals("{\"a\":1}", Sse.dataOf("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", Sse.dataOf("data:{\"a\":1}"))
    }

    @Test
    fun ignoresCommentsAndOtherFields() {
        assertNull(Sse.dataOf(": ping"))
        assertNull(Sse.dataOf("event: message"))
        assertNull(Sse.dataOf("id: 42"))
        assertNull(Sse.dataOf(""))
        assertNull(Sse.dataOf("   "))
    }

    @Test
    fun detectsDone() {
        assertEquals(Sse.DONE, Sse.dataOf("data: [DONE]"))
        assertTrue(Sse.isDone(Sse.dataOf("data: [DONE]")!!))
        assertFalse(Sse.isDone("{}"))
    }
}
