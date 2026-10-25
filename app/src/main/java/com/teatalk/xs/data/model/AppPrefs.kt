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

    private companion object {
        const val KEY_ACTIVE_PROVIDER = "active_provider_id"
    }
}
