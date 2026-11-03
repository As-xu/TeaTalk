package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegexEngineTest {

    @Before
    fun setUp() = RegexEngine.clearCache()

    private fun rule(
        pattern: String,
        replacement: String,
        flags: String = "g",
        scope: String = "prompt",
        enabled: Boolean = true,
        sortIndex: Int = 0,
        name: String = "r",
    ) = RegexRule(
        id = 0L,
        name = name,
        pattern = pattern,
        replacement = replacement,
        flags = flags,
        scope = scope,
        enabled = enabled,
        sortIndex = sortIndex,
    )

    @Test
    fun appliesSingleRuleWithCaseInsensitiveFlag() {
        val out = RegexEngine.apply("Hello WORLD", listOf(rule("world", "there", flags = "gi")))
        assertEquals("Hello there", out)
    }

    @Test
    fun withoutGlobalFlagOnlyFirstMatchIsReplaced() {
        val out = RegexEngine.apply("a a a", listOf(rule("a", "b", flags = "")))
        assertEquals("b a a", out)
    }

    @Test
    fun rulesChainInSortOrder() {
        val rules = listOf(
            rule("one", "two", sortIndex = 1),
            rule("two", "three", sortIndex = 2),
        )
        assertEquals("three", RegexEngine.apply("one", rules))
    }

    @Test
    fun disabledRuleIsSkipped() {
        val out = RegexEngine.apply("keep", listOf(rule("keep", "gone", enabled = false)))
        assertEquals("keep", out)
    }

    @Test
    fun displayOnlyRuleDoesNotRunForPromptScope() {
        val rules = listOf(rule("tea", "coffee", scope = "display"))
        assertEquals("tea", RegexEngine.apply("tea", rules, promptScope = true))
        assertEquals("coffee", RegexEngine.apply("tea", rules, promptScope = false))
    }

    @Test
    fun bothScopeRunsInEitherScope() {
        val rules = listOf(rule("tea", "coffee", scope = "both"))
        assertEquals("coffee", RegexEngine.apply("tea", rules, promptScope = true))
        assertEquals("coffee", RegexEngine.apply("tea", rules, promptScope = false))
    }

    @Test
    fun brokenPatternIsSkippedAndTextSurvives() {
        val rules = listOf(
            rule("(", "x"),
            rule("tea", "coffee"),
        )
        assertEquals("coffee", RegexEngine.apply("tea", rules))
    }

    @Test
    fun dollarGroupsAreExpanded() {
        val out = RegexEngine.apply("name: Ada", listOf(rule("name: (\\w+)", "user=$1")))
        assertEquals("user=Ada", out)
    }

    @Test
    fun validateReportsNullForValidPattern() {
        assertNull(RegexEngine.validate("\\d+", "g"))
    }

    @Test
    fun validateRejectsEmptyPattern() {
        assertEquals("empty pattern", RegexEngine.validate("", "g"))
    }

    @Test
    fun validateReportsReasonForBrokenPattern() {
        val error = RegexEngine.validate("(", "g")
        assertNotNull(error)
        assertTrue(error!!.isNotBlank())
    }
}
