package com.teatalk.xs.data

import android.content.Context
import androidx.room.Room
import com.teatalk.core.log.RingBufferSink
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.BuildConfig
import com.teatalk.xs.data.db.TeaTalkDatabase
import com.teatalk.xs.data.model.AppPrefs
import com.teatalk.xs.data.model.ProviderRepository
import com.teatalk.xs.data.model.SecretStore
import com.teatalk.xs.data.net.ModelListClient
import com.teatalk.xs.log.AndroidLogSink
import com.teatalk.xs.log.FileLogSink
import com.teatalk.xs.log.LogSettings

/** 应用级依赖容器（M0 手工 DI）。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** 内存最近日志（「查看最近日志」用）。 */
    val logRing: RingBufferSink = RingBufferSink(capacity = 2000)

    val logSettings: LogSettings = LogSettings(appContext)

    private val fileLogSink: FileLogSink = FileLogSink(java.io.File(appContext.filesDir, "logs"))

    init {
        // 调试开关：debug 包默认开、release 默认关；用户可在「我的 → 关于」里打开
        if (BuildConfig.DEBUG) logSettings.setDebugEnabled(true)
        val debugEnabled = logSettings.debugEnabled
        fileLogSink.enabled = debugEnabled
        TeaTalkLog.install(AndroidLogSink(), logRing, fileLogSink)
        TeaTalkLog.minLevel = if (debugEnabled) com.teatalk.core.log.LogLevel.D else com.teatalk.core.log.LogLevel.W
        TeaTalkLog.i("Ui", "app.start", "version" to BuildConfig.VERSION_NAME, "debugLog" to debugEnabled)
    }

    fun setDebugLogging(enabled: Boolean) {
        logSettings.setDebugEnabled(enabled)
        fileLogSink.enabled = enabled
        TeaTalkLog.i("Ui", "log.toggle", "enabled" to enabled)
    }

    private val database: TeaTalkDatabase by lazy {
        val started = System.currentTimeMillis()
        TeaTalkLog.i("Db", "db.open", "name" to "teatalk.db", "version" to TeaTalkDatabase.VERSION)
        Room.databaseBuilder(appContext, TeaTalkDatabase::class.java, "teatalk.db")
            .addMigrations(
                TeaTalkDatabase.MIGRATION_1_2,
                TeaTalkDatabase.MIGRATION_2_3,
                TeaTalkDatabase.MIGRATION_3_4,
                TeaTalkDatabase.MIGRATION_4_5,
            )
            .build()
            .also {
                TeaTalkLog.i("Db", "db.ready", "ms" to (System.currentTimeMillis() - started))
            }
    }

    val characterRepository: CharacterRepository by lazy {
        CharacterRepository(appContext, database.characterDao())
    }

    val worldBookRepository: WorldBookRepository by lazy {
        WorldBookRepository(appContext, database.worldBookDao())
    }

    val appPrefs: AppPrefs by lazy { AppPrefs(appContext) }

    val secretStore: SecretStore by lazy { SecretStore(appContext) }

    val providerRepository: ProviderRepository by lazy {
        ProviderRepository(database.modelProviderDao(), secretStore, appPrefs)
    }

    val modelListClient: ModelListClient by lazy { ModelListClient() }

    val chatClient: com.teatalk.xs.data.net.ChatClient by lazy { com.teatalk.xs.data.net.ChatClient() }

    val chatEngine: ChatEngine by lazy {
        ChatEngine(characterRepository, providerRepository, chatClient)
    }
}
