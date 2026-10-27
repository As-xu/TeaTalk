package com.teatalk.xs.data.model

import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.data.db.ModelProviderDao
import com.teatalk.xs.data.db.ModelProviderEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray

/** Provider 配置的读写：行存 Room，key 存 SecretStore，默认项存 AppPrefs。 */
class ProviderRepository(
    private val dao: ModelProviderDao,
    private val secrets: SecretStore,
    private val prefs: AppPrefs,
) {

    val providers: Flow<List<ModelProviderEntity>> = dao.observeAll()

    fun activeProviderId(): String? = prefs.activeProviderId

    fun apiKey(providerId: String): String? = secrets.get(providerId)

    fun maskedKey(providerId: String): String? = secrets.masked(providerId)

    /** 返回 id；`id == null` 表示新建。apiKey 为 null 表示不改动已有 key。 */
    suspend fun save(
        id: String?,
        name: String,
        baseUrl: String,
        apiFormat: String,
        defaultModel: String,
        models: List<String>,
        apiKey: String?,
    ): String {
        val providerId = id ?: UUID.randomUUID().toString()
        val existing = dao.find(providerId)
        val now = System.currentTimeMillis()
        dao.upsert(
            ModelProviderEntity(
                id = providerId,
                name = name.trim(),
                apiFormat = apiFormat,
                baseUrl = baseUrl.trim().trimEnd('/'),
                defaultModel = defaultModel.trim(),
                modelsJson = JSONArray(models).toString(),
                createdAt = existing?.createdAt ?: now,
                lastUsedAt = existing?.lastUsedAt ?: 0L,
            ),
        )
        if (!apiKey.isNullOrBlank()) secrets.put(providerId, apiKey.trim())
        if (prefs.activeProviderId == null) prefs.activeProviderId = providerId
        TeaTalkLog.i(
            "Net",
            "provider.save",
            "id" to providerId,
            "host" to Redact.url(baseUrl),
            "hasKey" to secrets.has(providerId),
            "models" to models.size,
        )
        return providerId
    }

    suspend fun delete(providerId: String) {
        dao.deleteById(providerId)
        secrets.remove(providerId)
        if (prefs.activeProviderId == providerId) {
            prefs.activeProviderId = dao.observeAll().first().firstOrNull()?.id
        }
        TeaTalkLog.i("Net", "provider.delete", "id" to providerId)
    }

    fun setActive(providerId: String) {
        prefs.activeProviderId = providerId
        TeaTalkLog.i("Net", "provider.active", "id" to providerId)
    }

    suspend fun touch(providerId: String) {
        dao.touch(providerId, System.currentTimeMillis())
    }

    /**
     * 解析「当前该用哪个 Provider」。
     *
     * 刻意把三种失败分开：没配置 / 行在但没有 key / 没选默认模型。
     * 之前统一返回 null，导致聊天把「没选模型」当成「没配置」并拿角色名兜底，
     * 现在由引擎映射成 NO_PROVIDER / NO_KEY / NO_MODEL 三种明确错误。
     */
    suspend fun resolve(): ProviderResolution {
        val id = prefs.activeProviderId ?: return ProviderResolution.NoProvider
        val row = dao.find(id) ?: return ProviderResolution.NoProvider
        val key = secrets.get(id)?.takeIf { it.isNotBlank() } ?: return ProviderResolution.NoKey(row)
        return ProviderResolution.Ok(
            ActiveProvider(
                id = row.id,
                name = row.name,
                baseUrl = row.baseUrl,
                model = row.defaultModel,
                apiKey = key,
                temperature = 0.8,
            ),
        )
    }
}

sealed interface ProviderResolution {
    data class Ok(val provider: ActiveProvider) : ProviderResolution
    data object NoProvider : ProviderResolution
    data class NoKey(val provider: ModelProviderEntity) : ProviderResolution
}

/**
 * 聊天引擎需要的「当前生效配置」，key 只在内存里出现。
 *
 * 不用 data class：自动生成的 toString() 会把 apiKey 打进日志/崩溃报告。
 */
class ActiveProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val temperature: Double,
) {
    override fun toString(): String =
        "ActiveProvider(id=$id, name=$name, baseUrl=${Redact.url(baseUrl)}, model=$model, key=<redacted>)"
}
