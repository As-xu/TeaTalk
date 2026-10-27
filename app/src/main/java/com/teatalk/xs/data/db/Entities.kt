package com.teatalk.xs.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色卡。
 *
 * 设计要点（对应方案 §4.6）：
 * - `cardJson` 存**原始 JSON 全文**（ImportedCard.card.raw），导出/重解析不丢字段。
 * - `avatarPath` 相对 `filesDir`：PNG 卡直接把原图拷一份当头像；JSON/CHARX 暂为 null。
 * - `tagsJson` 为 JSON 数组字符串（不引入额外序列化插件）。
 * - `favorite` / `pinned` / `updatedAt` 为 D6 卡库新增（迁移 1→2）。
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
    /** 收藏（卡库「收藏」筛选）。 */
    val favorite: Boolean = false,
    /** 置顶（列表最前，与聊天列表一致）。 */
    val pinned: Boolean = false,
    /** 最近改动时间（编辑、收藏、置顶都会刷新，用于「最近」排序）。 */
    val updatedAt: Long = 0L,
)

/** 消息表：按角色归档，`createdAt` 升序即对话顺序。 */
@Entity(tableName = "messages", indices = [Index("characterId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val characterId: Long,
    /** user / assistant / system */
    val role: String,
    /** 当前展示的文本（= variants[selectedVariant] 的镜像，老代码/列表预览都用它）。 */
    val content: String,
    val createdAt: Long = 0L,
    /**
     * 多版本（M2）：同一轮回复的所有候选文本，JSON 数组字符串。
     * 空数组表示「只有一个版本」（老数据/用户消息）。
     */
    val variantsJson: String = "[]",
    /** 每个版本的生成状态（与 variantsJson 等长；空数组表示全部 complete）。 */
    @ColumnInfo(defaultValue = "[]") val variantStatusJson: String = "[]",
    /** 当前选中版本的下标（0 起）。 */
    val selectedVariant: Int = 0,
    /** complete / stopped（停止生成留下的半截）/ failed（错误前半截）。 */
    val status: String = STATUS_COMPLETE,
) {
    companion object {
        const val STATUS_COMPLETE = "complete"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * 独立世界书（D6）：从 ST 世界书 JSON 或角色卡内嵌世界书导入，存原始条目 JSON。
 *
 * M0/M6 阶段只做「导入 + 查看 + 删除」；绑定到会话与注入管线在 M2 做。
 */
@Entity(tableName = "world_books")
data class WorldBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 世界书名（ST 文件名或卡名）。 */
    val name: String,
    /** 来源标记：`st_json` / `card` / `manual`。 */
    val source: String,
    /** 原始 JSON 全文（不丢字段，后续 M2 管线按需重解析）。 */
    val entriesJson: String,
    /** 条目数（列表展示用，避免每次解析全文）。 */
    val entryCount: Int,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
