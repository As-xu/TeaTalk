package com.teatalk.xs.data.model

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.teatalk.core.log.TeaTalkLog

/**
 * API Key 只存加密 Preferences（MasterKey AES256-GCM）；日志与 UI 都只出现尾部 4 位。
 */
class SecretStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "teatalk_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(providerId: String, apiKey: String) {
        prefs.edit().putString(keyName(providerId), apiKey).apply()
        TeaTalkLog.i("Net", "secret.put", "provider" to providerId, "tail" to apiKey.takeLast(4))
    }

    fun get(providerId: String): String? = prefs.getString(keyName(providerId), null)

    fun has(providerId: String): Boolean = !get(providerId).isNullOrBlank()

    fun remove(providerId: String) {
        prefs.edit().remove(keyName(providerId)).apply()
    }

    /** UI 展示用：`****c35e`，无 key 返回 null。 */
    fun masked(providerId: String): String? = get(providerId)
        ?.takeIf { it.isNotBlank() }
        ?.let { "****" + it.takeLast(4) }

    private fun keyName(providerId: String) = "provider_key_$providerId"
}
