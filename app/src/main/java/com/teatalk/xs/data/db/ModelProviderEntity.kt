package com.teatalk.xs.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个用户自定义的大模型提供方（OpenAI 兼容协议）。
 *
 * - `id` 用 UUID 字符串：API Key 存在 EncryptedSharedPreferences，键为 `provider_key_<id>`，
 *   与行一一对应；删行时同步删 key。
 * - `modelsJson` 是拉取到的模型 id 数组（org.json 序列化），避免单独建表。
 */
@Entity(tableName = "model_providers")
data class ModelProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val apiFormat: String = "openai",
    val baseUrl: String,
    val defaultModel: String = "",
    val modelsJson: String = "[]",
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    // 可选采样参数：ST 预设未导入时作为回退，优先级 preset > provider > 内置默认。
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
)
