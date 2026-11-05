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

    /** 翻译专用 provider；null 表示回落到聊天默认项。 */
    var translationProviderId: String?
        get() = prefs.getString(KEY_TRANSLATION_PROVIDER, null)
        set(value) {
            prefs.edit().putString(KEY_TRANSLATION_PROVIDER, value).apply()
        }

    /** 翻译目标语言（`TranslationTarget.code`），默认简体中文。 */
    var translationTargetLang: String
        get() = prefs.getString(KEY_TRANSLATION_TARGET, null) ?: "zh-Hans"
        set(value) {
            prefs.edit().putString(KEY_TRANSLATION_TARGET, value).apply()
        }

    /** 自动翻译新到的角色回复。 */
    var autoTranslateReplies: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSLATE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply()
        }

    /**
     * 备用接口链（0.23.0）：主接口 429/5xx/超时/断网时按这个顺序尝试。
     *
     * 存成逗号分隔的 id 列表而不是新表：只是「顺序偏好」，没有独立生命周期。
     * 删除 provider 时由 ProvidersViewModel.delete 主动把该 id 从链里移除；
     * 残留的失效 id 会被 ChatEngine.resolveChain 跳过（不会崩，只是白跑一次解析）。
     */
    var fallbackProviderIds: List<String>
        get() = prefs.getString(KEY_FALLBACK_PROVIDERS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (clean.isEmpty()) {
                prefs.edit().remove(KEY_FALLBACK_PROVIDERS).apply()
            } else {
                prefs.edit().putString(KEY_FALLBACK_PROVIDERS, clean.joinToString(",")).apply()
            }
        }

    // ---- 0.24.0 WebDAV 备份（密码不在这里，走 SecretStore）----

    /** WebDAV 根地址，如 `https://dav.jianguoyun.com/dav/`。 */
    var webDavUrl: String
        get() = prefs.getString(KEY_WEBDAV_URL, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_WEBDAV_URL, value.trim()).apply()
        }

    /** WebDAV 用户名（坚果云是邮箱）。 */
    var webDavUser: String
        get() = prefs.getString(KEY_WEBDAV_USER, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_WEBDAV_USER, value.trim()).apply()
        }

    /** 远端目录，默认 `TeaTalk`（相对 WebDAV 根）。 */
    var webDavDir: String
        get() = prefs.getString(KEY_WEBDAV_DIR, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WEBDAV_DIR
        set(value) {
            prefs.edit().putString(KEY_WEBDAV_DIR, value.trim().trim('/').ifBlank { DEFAULT_WEBDAV_DIR }).apply()
        }

    // ---- 0.25.0 TTS 朗读 ----

    /** 语速倍率（0.5–1.5，1.0 为系统默认）。 */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f).coerceIn(0.5f, 1.5f)
        set(value) {
            prefs.edit().putFloat(KEY_TTS_RATE, value.coerceIn(0.5f, 1.5f)).apply()
        }

    /** 音高（0.5–1.5）。 */
    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f).coerceIn(0.5f, 1.5f)
        set(value) {
            prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 1.5f)).apply()
        }

    /** 新回复自动朗读（默认关：多数人不想在地铁里被外放）。 */
    var ttsAutoRead: Boolean
        get() = prefs.getBoolean(KEY_TTS_AUTO_READ, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TTS_AUTO_READ, value).apply()
        }

    private companion object {
        const val KEY_ACTIVE_PROVIDER = "active_provider_id"
        const val KEY_USER_NAME = "user_name"
        const val DEFAULT_USER_NAME = "User"
        const val KEY_TRANSLATION_PROVIDER = "translation_provider_id"
        const val KEY_TRANSLATION_TARGET = "translation_target_lang"
        const val KEY_AUTO_TRANSLATE = "auto_translate_replies"
        const val KEY_FALLBACK_PROVIDERS = "fallback_provider_ids"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USER = "webdav_user"
        const val KEY_WEBDAV_DIR = "webdav_dir"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_TTS_AUTO_READ = "tts_auto_read"
        const val DEFAULT_WEBDAV_DIR = "TeaTalk"
    }
}
