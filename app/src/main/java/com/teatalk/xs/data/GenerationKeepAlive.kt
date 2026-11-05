package com.teatalk.xs.data

import android.content.Context
import com.teatalk.xs.service.GenerationService

/**
 * 0.26.0：生成会话的保活引用计数。
 *
 * 一次点击可能同时存在「重试」和「发送」两条流，但前台服务只需要一个；
 * 计数归零才停服务，避免后结束的流把仍在进行的流一起关掉。
 *
 * 只在主线程使用（调用方都是 `viewModelScope`）。
 */
class GenerationKeepAlive(private val context: Context) {

    private var active = 0

    fun begin() {
        active++
        if (active == 1) {
            // 通知权限被拒 / 系统限制后台启动时不该影响生成本身。
            runCatching { GenerationService.start(context) }
        }
    }

    fun end() {
        if (active == 0) return
        active--
        if (active == 0) {
            runCatching { GenerationService.stop(context) }
        }
    }
}
