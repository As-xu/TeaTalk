package com.teatalk.xs

import android.app.Application
import com.teatalk.xs.data.AppContainer

/**
 * M0 手工 DI：Application 持有单例容器。
 * 量再大就换 Hilt（M2 聊天管线时再评估）。
 */
class TeaTalkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
