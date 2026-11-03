package com.teatalk.xs.data

import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.db.TranslationDao
import com.teatalk.xs.data.db.TranslationEntity
import com.teatalk.xs.data.model.AppPrefs
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.model.ProviderResolution
import com.teatalk.xs.data.net.TranslationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 翻译失败的可读分类，交给 UI 映射成字符串资源。 */
enum class TranslationFailure {
    EMPTY_TEXT,
    NO_PROVIDER,
    NO_KEY,
    NO_MODEL,
    NETWORK,
    EMPTY_RESULT,
    UNKNOWN,
}

sealed interface TranslationResult {
    data class Ok(val text: String, val cached: Boolean, val engine: String) : TranslationResult
    data class Failed(val failure: TranslationFailure) : TranslationResult
}

/**
 * 翻译仓库：缓存优先，未命中才调接口。
 *
 * 只负责「文本 → 译文」，不碰消息表；原文由调用方保管（消息、输入框、角色卡字段）。
 * 缓存键是 (目标语言 + 原文)，所以同一段文本翻成不同语言会各存一份。
 */
class TranslationRepository(
    private val dao: TranslationDao,
    private val providers: ProviderRepository,
    private val client: TranslationClient,
    private val prefs: AppPrefs,
) {

    fun targetLang(): String = prefs.translationTargetLang

    fun autoTranslateReplies(): Boolean = prefs.autoTranslateReplies

    /** 当前翻译接口 id；null 表示回落到聊天默认项。 */
    fun providerId(): String? = prefs.translationProviderId

    suspend fun translate(
        text: String,
        targetLang: String = prefs.translationTargetLang,
        force: Boolean = false,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val source = text.trim()
        if (source.isEmpty()) return@withContext TranslationResult.Failed(TranslationFailure.EMPTY_TEXT)
        val hash = TranslationKey.hash(targetLang, source)
        if (!force) {
            dao.find(hash)?.let { cached ->
                if (cached.text.isNotBlank()) {
                    TeaTalkLog.i("Trans", "trans.cache.hit", "lang" to targetLang, "chars" to source.length)
                    return@withContext TranslationResult.Ok(cached.text, cached = true, engine = cached.engine)
                }
            }
        }
        when (val resolution = providers.resolve(prefs.translationProviderId)) {
            is ProviderResolution.NoProvider -> return@withContext TranslationResult.Failed(TranslationFailure.NO_PROVIDER)
            is ProviderResolution.NoKey -> return@withContext TranslationResult.Failed(TranslationFailure.NO_KEY)
            is ProviderResolution.Ok -> {
                val provider = resolution.provider
                if (provider.model.isBlank()) {
                    return@withContext TranslationResult.Failed(TranslationFailure.NO_MODEL)
                }
                val target = TranslationTarget.of(targetLang)
                val outcome = client.complete(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    model = provider.model,
                    systemPrompt = TranslationPrompt.system(target),
                    userPrompt = TranslationPrompt.user(source),
                )
                outcome.fold(
                    onSuccess = { translated ->
                        val trimmed = translated.trim()
                        if (trimmed.isEmpty()) {
                            return@withContext TranslationResult.Failed(TranslationFailure.EMPTY_RESULT)
                        }
                        dao.upsert(
                            TranslationEntity(
                                hash = hash,
                                targetLang = targetLang,
                                text = trimmed,
                                engine = provider.id,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        TeaTalkLog.i("Trans", "trans.ok", "lang" to targetLang, "chars" to source.length)
                        TranslationResult.Ok(trimmed, cached = false, engine = provider.id)
                    },
                    onFailure = { error ->
                        TeaTalkLog.w(
                            "Trans",
                            "trans.fail",
                            "kind" to error.javaClass.simpleName,
                            "detail" to (error.message ?: ""),
                        )
                        TranslationResult.Failed(
                            when {
                                error.message?.startsWith("HTTP") == true -> TranslationFailure.NETWORK
                                else -> TranslationFailure.UNKNOWN
                            },
                        )
                    },
                )
            }
        }
    }

    suspend fun cacheSize(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        dao.clear()
        TeaTalkLog.i("Trans", "trans.cache.clear")
    }
}
