package com.teatalk.xs.data

import com.teatalk.core.worldinfo.WorldInfoEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 最近一次请求的 prompt 快照（M3 步骤 2：Prompt 调试面板）。
 *
 * 只存在内存里，杀进程即清空：它不是日志，也不是历史记录，
 * 只是「这一次到底发了什么」的可读视图，避免把角色卡正文/聊天原文落盘。
 */
data class PromptSnapshot(
    val providerName: String,
    val model: String,
    val system: String,
    val messages: List<PromptMessageSnapshot>,
    val worldEntries: List<WorldEntrySnapshot>,
    val worldChars: Int,
    val takenAt: Long,
)

data class PromptMessageSnapshot(val role: String, val content: String)

data class WorldEntrySnapshot(
    val comment: String,
    val position: String,
    val chars: Int,
) {
    companion object {
        fun of(entry: WorldInfoEntry): WorldEntrySnapshot = WorldEntrySnapshot(
            comment = entry.comment.ifBlank { entry.keys.take(3).joinToString(",") },
            position = when (entry.position) {
                0 -> "before"
                1 -> "after"
                2 -> "antop"
                3 -> "anbottom"
                4 -> "atdepth"
                5 -> "emtop"
                6 -> "embottom"
                else -> "position-${entry.position}"
            },
            chars = entry.content.length,
        )
    }
}

object PromptDebugStore {
    private val _snapshot = MutableStateFlow<PromptSnapshot?>(null)
    val snapshot: StateFlow<PromptSnapshot?> = _snapshot.asStateFlow()

    fun publish(
        providerName: String,
        model: String,
        system: String,
        messages: List<Pair<String, String>>,
        activated: List<WorldInfoEntry>,
        worldChars: Int,
    ) {
        _snapshot.value = PromptSnapshot(
            providerName = providerName,
            model = model,
            system = system,
            messages = messages.map { (role, content) -> PromptMessageSnapshot(role, content) },
            worldEntries = activated.map(WorldEntrySnapshot::of),
            worldChars = worldChars,
            takenAt = System.currentTimeMillis(),
        )
    }
}
