package com.teatalk.xs.data.model

import android.content.Context

/** 轻量偏好：当前生效的 Provider、语言等（非机密）。 */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("teatalk_prefs", Context.MODE_PRIVATE)

    var activeProviderId: String?
        get() = prefs.getString(KEY_ACTIVE_PROVIDER, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_PROVIDER, value).apply()
        }

    /** 个人资料名：替换 prompt 与开场白里的 `{{user}}`，默认 `User`。 */
    var userName: String
        get() = prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim().ifBlank { DEFAULT_USER_NAME }).apply()
        }

    private companion object {
        const val KEY_ACTIVE_PROVIDER = "active_provider_id"
        const val KEY_USER_NAME = "user_name"
        const val DEFAULT_USER_NAME = "User"
    }
}
