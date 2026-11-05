package com.teatalk.xs

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.teatalk.xs.data.ImportOutcome
import com.teatalk.xs.data.UiText
import com.teatalk.xs.ui.HomeScreen
import com.teatalk.xs.ui.consent.ConsentScreen
import com.teatalk.xs.ui.theme.SystemBarsAppearance
import com.teatalk.xs.ui.theme.TeaTalkTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 外部打开/分享进来的卡：导入后把消息交给 HomeScreen 弹 snackbar。 */
    private var externalMessage by mutableStateOf<UiText?>(null)

    /** 应用内语言覆盖（null = 跟随系统 / 系统"应用语言"设置）。 */
    private var languageTag by mutableStateOf<String?>(null)

    /** 0.26.0：首启知情同意；同意前只渲染 ConsentScreen，不初始化任何会话数据。 */
    private var consentAccepted by mutableStateOf(false)

    /** 同意前收到 VIEW/SEND 卡片时先暂存，同意后再导入。 */
    private var pendingCard by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试构建下的崩溃自测入口（验证崩溃记录链路）；release 中不存在。
        if (BuildConfig.DEBUG && intent.getBooleanExtra("teatalk.crash", false)) {
            throw IllegalStateException("TeaTalk crash test")
        }
        languageTag = LanguagePrefs.read(this)
        val app = application as TeaTalkApp
        consentAccepted = app.container.appPrefs.consentAccepted
        // 仅显式 `-Pteatalk.feedOverride=true` 的构建接受 adb 指定的更新源，
        // 用来在真机上走通「检查 → 下载 → 安装」；普通 debug 包不再接受，
        // 否则同机任意 App 都能改写更新地址（其后再配合路径穿越即可越界写文件）。
        if (BuildConfig.FEED_OVERRIDE) {
            intent.getStringExtra("teatalk.update_feed")?.let { feed ->
                (application as TeaTalkApp).container.appPrefs.updateFeedUrl = feed
            }
        }
        // 跟随系统深浅色：透明 scrim 让 App 背景透到状态栏，图标明暗由 Compose 侧
        // （TeaTalkTheme 里的 WindowStyleEffect）随主题切换。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            TeaTalkTheme {
                SystemBarsAppearance(androidx.compose.foundation.isSystemInDarkTheme())
                if (!consentAccepted) {
                    ConsentScreen(
                        onAccept = { allowNsfw ->
                            val prefs = app.container.appPrefs
                            prefs.nsfwAllowed = allowNsfw
                            prefs.consentAccepted = true
                            consentAccepted = true
                            // 同意之后再要通知权限，并且只问一次（拒绝后可在系统设置里改）。
                            requestNotificationPermission()
                            pendingCard?.let { uri ->
                                pendingCard = null
                                importExternalCard(uri)
                            }
                        },
                        onExit = { finish() },
                    )
                } else {
                    HomeScreen(
                        externalMessage = externalMessage,
                        onExternalMessageShown = { externalMessage = null },
                        languageTag = languageTag,
                        onChangeLanguage = { tag ->
                            LanguagePrefs.write(this, tag)
                            recreate()
                        },
                    )
                }
            }
        }
        handleExternalCard(intent)
    }

    /**
     * 0.26.0：生成保活用的前台服务通知。Android 13+ 需要运行时授权；
     * 被拒也不影响生成，只是后台时看不到那条提示。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = (application as TeaTalkApp).container.appPrefs
        if (prefs.notificationAsked) return
        prefs.notificationAsked = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 4201
    }

    /**
     * 让 Activity 的资源使用应用内选择的语言。
     * 没有覆盖时直接用系统配置（API 33+ 还能吃到系统「应用语言」设置）。
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = LanguagePrefs.read(newBase)
        if (tag.isNullOrEmpty()) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalCard(intent)
    }

    private fun handleExternalCard(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            @Suppress("DEPRECATION")
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        } ?: return
        // 同意之前不写库：先记下来，点「同意并继续」后再导入。
        if (!consentAccepted) {
            pendingCard = uri
            return
        }
        importExternalCard(uri)
    }

    private fun importExternalCard(uri: Uri) {
        val repository = (application as TeaTalkApp).container.characterRepository
        lifecycleScope.launch {
            externalMessage = when (val outcome = repository.importCard(uri)) {
                is ImportOutcome.Success -> UiText.imported(outcome.name, outcome.warnings.size)
                is ImportOutcome.Failure -> outcome.message
            }
        }
    }
}

/** 应用内语言覆盖的持久化（键名固定，不随语言变化）。 */
object LanguagePrefs {
    private const val PREFS = "teatalk_prefs"
    private const val KEY_LANGUAGE = "language_tag"

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    fun write(context: Context, tag: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (tag.isNullOrEmpty()) editor.remove(KEY_LANGUAGE) else editor.putString(KEY_LANGUAGE, tag)
        editor.apply()
    }
}
