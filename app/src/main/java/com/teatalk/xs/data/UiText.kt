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
    }
}
