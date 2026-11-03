package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationKeyTest {

    @Test
    fun hashIsStableAndOrderSensitive() {
        val a = TranslationKey.hash("zh-Hans", "hello")
        val b = TranslationKey.hash("zh-Hans", "hello")
        assertEquals(a, b)

        val otherLang = TranslationKey.hash("en", "hello")
        assertNotEquals(a, otherLang)
        val otherText = TranslationKey.hash("zh-Hans", "hello ")
        assertNotEquals(a, otherText)
    }

    @Test
    fun hashDoesNotMixLanguageAndText() {
        // 用分隔符隔开，避免 ("zh", "en:hi") 与 ("zh:en", "hi") 撞车
        assertNotEquals(
            TranslationKey.hash("zh-Hans", "en:hi"),
            TranslationKey.hash("zh-Hans:en", "hi"),
        )
    }

    @Test
    fun targetsCoverTheThreeLanguages() {
        assertEquals(3, TranslationTarget.ALL.size)
        assertEquals("zh-Hans", TranslationTarget.ZH_HANS)
        assertEquals("zh-Hant", TranslationTarget.ZH_HANT)
        assertEquals("en", TranslationTarget.EN)
    }

    @Test
    fun unknownCodeFallsBackToSimplifiedChinese() {
        assertEquals(TranslationTarget.of(TranslationTarget.ZH_HANS), TranslationTarget.of(null))
        assertEquals(TranslationTarget.of(TranslationTarget.ZH_HANS), TranslationTarget.of(""))
        assertEquals(TranslationTarget.of(TranslationTarget.ZH_HANS), TranslationTarget.of("klingon"))
        assertEquals(TranslationTarget.of(TranslationTarget.EN), TranslationTarget.of("en"))
    }

    @Test
    fun everyTargetHasPromptNameAndLabel() {
        TranslationTarget.ALL.forEach { target ->
            assertTrue(target.promptName.isNotBlank())
            assertTrue(target.label.isNotBlank())
        }
    }
}
