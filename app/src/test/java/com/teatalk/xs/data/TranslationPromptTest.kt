package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptTest {

    @Test
    fun systemPromptNamesTheTargetLanguage() {
        val zh = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.ZH_HANS))
        assertTrue(zh.contains("Simplified Chinese"))

        val en = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.EN))
        assertTrue(en.contains("English"))
        assertTrue(en.contains("Output only the translation"))
    }

    @Test
    fun systemPromptKeepsRoleplayMarkup() {
        val prompt = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.ZH_HANS))
        // 这些是角色卡/聊天文本里会被模型误改的东西，必须在提示里点名保留
        listOf("*action*", "{{user}}", "{{char}}", "emoji").forEach {
            assertTrue("missing $it", prompt.contains(it))
        }
        assertTrue(prompt.contains("Do not explain"))
    }

    @Test
    fun userPromptCarriesRawText() {
        val text = "Hello there.\n\n*She waves.*"
        val prompt = TranslationPrompt.user(text)
        assertTrue(prompt.contains(text))
        assertTrue(prompt.startsWith("Translate this:"))
    }

    @Test
    fun systemPromptDiffersPerTarget() {
        val zh = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.ZH_HANS))
        val hant = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.ZH_HANT))
        val en = TranslationPrompt.system(TranslationTarget.of(TranslationTarget.EN))
        assertEquals(3, setOf(zh, hant, en).size)
    }
}
