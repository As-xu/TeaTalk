package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** `characters.linkedWorldBookIdsJson` 的四态：空白 / 空数组 / 合法 / 畸形。 */
class WorldBookIdsTest {

    @Test
    fun encode_sorts_and_dedups() {
        assertEquals("[1,2,5]", WorldBookIds.encode(listOf(5, 1, 2, 1)))
    }

    @Test
    fun parse_handles_blank_and_nulls() {
        assertEquals(emptyList<Long>(), WorldBookIds.parse(null))
        assertEquals(emptyList<Long>(), WorldBookIds.parse(""))
        assertEquals(emptyList<Long>(), WorldBookIds.parse("   "))
    }

    @Test
    fun parse_empty_array() {
        assertEquals(emptyList<Long>(), WorldBookIds.parse("[]"))
    }

    @Test
    fun parse_keeps_order_and_drops_duplicates() {
        assertEquals(listOf(3L, 1L), WorldBookIds.parse("[3, 1, 3]"))
    }

    @Test
    fun parse_malformed_values_fall_back_to_empty() {
        assertEquals(emptyList<Long>(), WorldBookIds.parse("not-json"))
        assertEquals(emptyList<Long>(), WorldBookIds.parse("{}"))
        assertEquals(emptyList<Long>(), WorldBookIds.parse("[\"x\", null]"))
    }
}
