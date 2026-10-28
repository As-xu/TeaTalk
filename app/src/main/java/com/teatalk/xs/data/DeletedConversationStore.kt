package com.teatalk.xs.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内「刚删除的会话」暂存：聊天页删掉当前会话后会立即退出，
 * 撤销入口必须由列表页展示，因此跨页面共享一份快照。
 */
object DeletedConversationStore {

    private val _deleted = MutableStateFlow<CharacterRepository.DeletedConversation?>(null)
    val deleted: StateFlow<CharacterRepository.DeletedConversation?> = _deleted.asStateFlow()

    fun put(snapshot: CharacterRepository.DeletedConversation) {
        _deleted.value = snapshot
    }

    fun clear() {
        _deleted.value = null
    }
}
