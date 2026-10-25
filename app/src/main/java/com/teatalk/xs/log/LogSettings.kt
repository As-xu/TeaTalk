package com.teatalk.xs.log

import android.content.Context
import android.content.SharedPreferences
import com.teatalk.core.log.LogLevel
import com.teatalk.core.log.TeaTalkLog

/**
 * 诊断日志开关：debug 默认开，release 默认关；用户可以手动打开（设置 → 关于）。
 * 开关影响：日志阈值（release 关时只留 W/E）与文件落盘。
 */
class LogSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("teatalk_prefs", Context.MODE_PRIVATE)

    val debugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)

    fun setDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_LOG, enabled).apply()
        applyToSinks(enabled)
    }

    /** 由 App 启动时调用一次（BuildConfig.DEBUG 决定默认值）。 */
    fun applyToSinks(enableDebug: Boolean) {
        TeaTalkLog.minLevel = if (enableDebug) LogLevel.D else LogLevel.W
    }

    private companion object {
        const val KEY_DEBUG_LOG = "debug_log_enabled"
    }
}
