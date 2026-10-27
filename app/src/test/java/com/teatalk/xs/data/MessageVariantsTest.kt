package com.teatalk.xs.data

import com.teatalk.xs.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVariantsTest {

    private fun message(variantsJson: String, content: String, selected: Int = 0) = MessageEntity(
        id = 1L,
        characterId = 1L,
        role = "assistant",
        content = content,
        createdAt = 0L,
        variantsJson = variantsJson,
        selectedVariant = selected,
        status = MessageEntity.STATUS_COMPLETE,
    )

    @Test
    fun legacyEmptyJsonFallsBackToContent() {
        val m = message(variantsJson = "[]", content = "only one")
        assertEquals(listOf("only one"), MessageVariants.of(m))
    }

    @Test
    fun blankContentStillProducesSingleVariant() {
        val m = message(variantsJson = "", content = "")
        assertEquals(listOf(""), MessageVariants.of(m))
    }

    @Test
    fun malformedJsonFallsBackInsteadOfCrashing() {
        val m = message(variantsJson = "[not json", content = "fallback")
        assertEquals(listOf("fallback"), MessageVariants.of(m))
    }

    @Test
    fun roundTripPreservesTextWithSpecialChars() {
        val variants = listOf("第一版：他说\"走吧\"", "第二版\n含换行与 emoji 🍵", "third")
        val json = MessageVariants.encode(variants)
        val m = message(variantsJson = json, content = "second", selected = 1)
        assertEquals(variants, MessageVariants.of(m))
    }

    @Test
    fun appendKeepsOrderAndSelectsLast() {
        val first = "v1"
        val json = MessageVariants.encode(listOf(first) + "v2")
        val m = message(variantsJson = json, content = "v2", selected = 1)
        val variants = MessageVariants.of(m)
        assertEquals(listOf("v1", "v2"), variants)
        assertEquals(1, m.selectedVariant)
        assertTrue(MessageVariants.encode(variants).startsWith("["))
    }
}
