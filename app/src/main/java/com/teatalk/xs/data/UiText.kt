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

        /** 卡 JSON 损坏/无法写回时明确报错，不再「假保存」（评审 P0-1）。 */
        fun cardSaveFailed(): UiText = of(R.string.toast_card_save_failed)

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
            is com.teatalk.xs.data.net.ChatError.SERVER -> of(R.string.chat_error_server, error.message.take(160))
            com.teatalk.xs.data.net.ChatError.BAD_RESPONSE -> of(R.string.chat_error_bad_response)
        }

        /**
         * Provider「测试连接 / 拉取模型」失败文案。
         *
         * 以前直接把 [Throwable.message] 塞进「连接失败：%s」，用户会看到英文 `HTTP 401 Unauthorized`。
         * 这里按状态码/异常类型给出可操作的中文提示，未知错误才退回到短消息。
         */
        fun providerFailure(error: Throwable): UiText {
            // OkHttp 对缺少 scheme 的地址会抛 IllegalArgumentException("Expected URL scheme ...")，
            // 给用户一句能改的建议，而不是英文原文。
            if (error is IllegalArgumentException && error.message?.contains("URL scheme", true) == true) {
                return of(R.string.providers_fail_bad_url)
            }
            if (error is com.teatalk.xs.data.net.HttpStatusException) {
                return when (error.status) {
                    401 -> of(R.string.providers_fail_unauthorized)
                    403 -> of(R.string.providers_fail_forbidden)
                    404 -> of(R.string.providers_fail_not_found)
                    429 -> of(R.string.providers_fail_rate_limited)
                    in 500..599 -> of(R.string.providers_fail_server, error.status)
                    else -> of(R.string.providers_test_fail, "HTTP ${error.status}")
                }
            }
            if (error is java.net.SocketTimeoutException ||
                error is java.io.InterruptedIOException
            ) {
                return of(R.string.providers_fail_timeout)
            }
            if (error is java.io.IOException) {
                return of(R.string.providers_fail_network)
            }
            val short = error.message?.trim()?.take(160)
            return if (short.isNullOrEmpty()) {
                of(R.string.providers_fail_unknown)
            } else {
                of(R.string.providers_test_fail, short)
            }
        }

        fun chatStopped(): UiText = of(R.string.chat_stopped)
fun conversationDeleted(title: String, messageCount: Long): UiText =
    if (messageCount == 1L) of(R.string.chat_conversation_deleted_one, title)
    else of(R.string.chat_conversation_deleted_count, title, messageCount)
fun conversationRestored(title: String): UiText = of(R.string.chat_conversation_restored, title)

        fun chatTruncated(): UiText = of(R.string.chat_truncated)
    }
}
