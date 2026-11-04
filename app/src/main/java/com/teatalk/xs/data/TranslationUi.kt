package com.teatalk.xs.data

import com.teatalk.xs.R

/**
 * 翻译失败原因 → 可展示文案。
 *
 * 聊天、角色卡详情、世界书共用同一套映射，避免三处 `when` 各自漂移。
 */
fun TranslationFailure.toUiText(): UiText = when (this) {
    TranslationFailure.NO_PROVIDER -> UiText.of(R.string.translate_no_provider)
    TranslationFailure.NO_KEY -> UiText.of(R.string.translate_no_key)
    TranslationFailure.NO_MODEL -> UiText.of(R.string.translate_no_model)
    TranslationFailure.EMPTY_RESULT -> UiText.of(R.string.translate_empty_result)
    TranslationFailure.NETWORK -> UiText.of(R.string.translate_network)
    TranslationFailure.EMPTY_TEXT, TranslationFailure.UNKNOWN -> UiText.of(R.string.translate_failed)
}
