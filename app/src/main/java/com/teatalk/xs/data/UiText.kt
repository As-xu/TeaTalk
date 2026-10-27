package com.teatalk.xs.data

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

import com.teatalk.xs.R

/**
 * 可翻译文案句柄：资源 id + 格式化参数。
 *
 * 数据层/ViewModel 不应该持有已经拼好的中文串（否则切语言不会变），
 * 一律传 [UiText]，由 UI 层用 `stringResource(res, *args)` / [resolve] 解析。
 */
data class UiText(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) context.getString(res) else context.getString(res, *args.toTypedArray())

    companion object {
        fun of(@StringRes res: Int, vararg args: Any): UiText = UiText(res, args.toList())

        fun ofPlural(@PluralsRes res: Int, quantity: Int, vararg args: Any): UiText =
            UiText(res, listOf(quantity, *args))

        /** 导入成功提示（有兼容提示时带条数）。 */
        fun imported(name: String, warningCount: Int): UiText =
            if (warningCount > 0) {
                of(R.string.import_imported_with_warnings, name, warningCount)
            } else {
                of(R.string.import_imported, name)
            }

        fun deleted(name: String): UiText = of(R.string.import_deleted, name)

        /** 卡库：收藏/置顶等轻提示。 */
        fun favorited(name: String, on: Boolean): UiText =
            of(if (on) R.string.toast_favorited else R.string.toast_unfavorited, name)

        fun pinned(name: String, on: Boolean): UiText =
            of(if (on) R.string.toast_pinned else R.string.toast_unpinned, name)

        fun cardSaved(name: String): UiText = of(R.string.toast_card_saved, name)

        /** 世界书：导入/删除提示。 */
        fun worldBookImported(name: String, entryCount: Int): UiText =
            of(R.string.worldbook_imported, name, entryCount)

        fun worldBookDeleted(name: String): UiText = of(R.string.worldbook_deleted, name)

        /** 模型 Provider。 */
        fun providerSaved(name: String): UiText = of(R.string.toast_provider_saved, name)

        fun providerDeleted(name: String): UiText = of(R.string.toast_provider_deleted, name)

        fun providerActivated(name: String): UiText = of(R.string.toast_provider_activated, name)

        fun providerTestOk(modelCount: Int): UiText = of(R.string.providers_test_ok, modelCount)

        fun providerTestFail(reason: String): UiText = of(R.string.providers_test_fail, reason)

        /** 聊天错误：把网络错误枚举翻成可读文案。 */
        fun chatError(error: com.teatalk.xs.data.net.ChatError): UiText = when (error) {
            com.teatalk.xs.data.net.ChatError.NO_PROVIDER -> of(R.string.chat_error_no_provider)
            com.teatalk.xs.data.net.ChatError.NO_KEY -> of(R.string.chat_error_no_key)
            com.teatalk.xs.data.net.ChatError.NO_MODEL -> of(R.string.chat_error_no_model)
            com.teatalk.xs.data.net.ChatError.UNAUTHORIZED -> of(R.string.chat_error_unauthorized)
            com.teatalk.xs.data.net.ChatError.RATE_LIMITED -> of(R.string.chat_error_rate_limited)
            com.teatalk.xs.data.net.ChatError.TIMEOUT -> of(R.string.chat_error_timeout)
            com.teatalk.xs.data.net.ChatError.NETWORK -> of(R.string.chat_error_network)
            is com.teatalk.xs.data.net.ChatError.HTTP -> of(R.string.chat_error_http, error.code)
            com.teatalk.xs.data.net.ChatError.BAD_RESPONSE -> of(R.string.chat_error_bad_response)
        }

        fun chatStopped(): UiText = of(R.string.chat_stopped)

        fun chatTruncated(): UiText = of(R.string.chat_truncated)
    }
}
