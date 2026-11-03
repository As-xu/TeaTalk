package com.teatalk.xs.data

import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroEngineTest {

    private fun context(
        vars: Map<String, String> = emptyMap(),
        userName: String = "Aria",
        characterName: String? = "Seraphina",
    ): MacroContext = MacroContext(
        variables = vars.toMutableMap(),
        userName = userName,
        characterName = characterName,
        now = LocalDateTime.of(2026, 11, 3, 21, 45, 7),
        random = Random(7),
    )

    @Test
    fun replacesUserAndChar() {
        val out = MacroEngine.resolve("{{user}} meets {{char}}", context())
        assertEquals("Aria meets Seraphina", out)
    }

    @Test
    fun timeAndDateUseInjectedClock() {
        val ctx = context()
        assertEquals("21:45", MacroEngine.resolve("{{time}}", ctx))
        assertEquals("2026-11-03", MacroEngine.resolve("{{date}}", ctx))
        assertEquals("2026-11-03 21:45", MacroEngine.resolve("{{datetime}}", ctx))
        assertTrue(MacroEngine.resolve("{{weekday}}", ctx).isNotEmpty())
    }

    @Test
    fun getvarReadsValueAndMissingKeyStaysEmpty() {
        val ctx = context(mapOf("mood" to "calm"))
        assertEquals("calm", MacroEngine.resolve("{{getvar::mood}}", ctx))
        assertEquals("", MacroEngine.resolve("{{getvar::missing}}", ctx))
    }

    @Test
    fun setvarStoresValueAndRendersEmpty() {
        val ctx = context()
        val out = MacroEngine.resolve("before{{setvar::mood::happy}}after", ctx)
        assertEquals("beforeafter", out)
        assertEquals("happy", ctx.variables["mood"])
    }

    @Test
    fun addvarAddsNumbersWhenBothSidesAreNumeric() {
        val ctx = context(mapOf("trust" to "3"))
        MacroEngine.resolve("{{addvar::trust::4}}", ctx)
        assertEquals("7", ctx.variables["trust"])
    }

    @Test
    fun addvarFallsBackToConcatForNonNumeric() {
        val ctx = context(mapOf("title" to "Sir "))
        MacroEngine.resolve("{{addvar::title::Alden}}", ctx)
        assertEquals("Sir Alden", ctx.variables["title"])
    }

    @Test
    fun randomPicksOneOfTheOptions() {
        val ctx = context()
        val picked = MacroEngine.resolve("{{random::red::green::blue}}", ctx)
        assertTrue(picked in setOf("red", "green", "blue"))
    }

    @Test
    fun unknownMacroIsLeftUntouched() {
        val text = "{{unknown::x}} and {{user}}"
        assertEquals("{{unknown::x}} and Aria", MacroEngine.resolve(text, context()))
    }

    @Test
    fun setvarThenGetvarInSameTextWorks() {
        val ctx = context()
        val out = MacroEngine.resolve("{{setvar::k::v}}={{getvar::k}}", ctx)
        assertEquals("=v", out)
    }

    @Test
    fun plainTextReturnedUnchanged() {
        assertEquals("no macros here", MacroEngine.resolve("no macros here", context()))
    }
}
