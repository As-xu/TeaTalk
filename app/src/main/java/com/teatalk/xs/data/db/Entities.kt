package com.teatalk.xs.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色卡（M0 只做导入与列表；聊天在 D4 起用）。
 *
 * 设计要点（对应方案 §4.6）：
 * - `cardJson` 存**原始 JSON 全文**（ImportedCard.card.raw），导出/重解析不丢字段。
 * - `avatarPath` 相对 `filesDir`：PNG 卡直接把原图拷一份当头像；JSON/CHARX 暂为 null。
 * - `tagsJson` 为 JSON 数组字符串（不引入额外序列化插件）。
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val avatarPath: String? = null,
    val cardJson: String,
    val cardSource: String,
    val tagsJson: String = "[]",
    val firstMes: String = "",
    val createdAt: Long = 0L,
)

/** 消息表：D4 聊天开始使用，先建表占位（version 1 内建，后续迁移不慌）。 */
@Entity(tableName = "messages", indices = [Index("characterId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val characterId: Long,
    /** user / assistant / system */
    val role: String,
    val content: String,
    val createdAt: Long = 0L,
)
