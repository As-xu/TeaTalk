package com.teatalk.xs

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.teatalk.xs.data.AppContainer
import com.teatalk.xs.data.AppForeground
import com.teatalk.xs.log.CrashLogStore

/**
 * M0 手工 DI：Application 持有单例容器。
 * 量再大就换 Hilt（M2 聊天管线时再评估）。
 */
class TeaTalkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogStore.install(this)
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = AppForeground.onResumed()

            override fun onActivityPaused(activity: Activity) = AppForeground.onPaused()

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
