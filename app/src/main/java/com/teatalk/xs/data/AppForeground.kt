package com.teatalk.xs.data

/**
 * 0.25.0：应用是否在前台的粗略判断（不引入 lifecycle-process 依赖）。
 *
 * 只服务于「自动朗读」这类后台不该打扰用户的行为：Activity resumed 计数 > 0 即前台。
 * 计数在 [com.teatalk.xs.TeaTalkApp] 注册的 ActivityLifecycleCallbacks 里维护。
 */
object AppForeground {

    @Volatile
    private var resumed = 0

    val isForeground: Boolean get() = resumed > 0

    fun onResumed() {
        resumed++
    }

    fun onPaused() {
        if (resumed > 0) resumed--
    }
}
