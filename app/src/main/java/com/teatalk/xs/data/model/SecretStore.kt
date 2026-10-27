package com.teatalk.xs.data.model

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.teatalk.core.log.Redact
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
        // 安全：日志里只放不可逆指纹与长度，绝不放 key 的任何片段（短 key 的「后 4 位」可能等于全文）。
        TeaTalkLog.i(
            "Net",
            "secret.put",
            "provider" to providerId,
            "fp" to Redact.fingerprint(apiKey),
            "len" to apiKey.length,
        )
    }

    fun get(providerId: String): String? = prefs.getString(keyName(providerId), null)

    fun has(providerId: String): Boolean = !get(providerId).isNullOrBlank()

    fun remove(providerId: String) {
        prefs.edit().remove(keyName(providerId)).apply()
    }

    /** UI 展示用：`****c35e`；短 key（<12 位）只显示 `****`，避免在屏幕上暴露大半个 key。 */
    fun masked(providerId: String): String? = get(providerId)
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.length < 12) "****" else "****" + it.takeLast(4) }

    private fun keyName(providerId: String) = "provider_key_$providerId"
}
