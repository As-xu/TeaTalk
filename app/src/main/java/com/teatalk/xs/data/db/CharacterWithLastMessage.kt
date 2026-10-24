package com.teatalk.xs.data.db

import androidx.room.Embedded

/**
 * 聊天列表行：角色 + 最后一条消息（只统计有过消息的角色，即「真正开过聊」的）。
 *
 * 单独成文件：Room 的 KSP 处理器在 Entities.kt 同文件里解析 @Embedded POJO 时报过
 * `[MissingType] references a type that is not present`，拆文件后正常。
 */
data class CharacterWithLastMessage(
    @Embedded val character: CharacterEntity,
    val lastContent: String?,
    val lastAt: Long?,
)
