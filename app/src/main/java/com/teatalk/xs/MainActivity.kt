package com.teatalk.xs

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
import com.teatalk.xs.ui.theme.SystemBarsAppearance
import com.teatalk.xs.ui.theme.TeaTalkTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 外部打开/分享进来的卡：导入后把消息交给 HomeScreen 弹 snackbar。 */
    private var externalMessage by mutableStateOf<UiText?>(null)

    /** 应用内语言覆盖（null = 跟随系统 / 系统"应用语言"设置）。 */
    private var languageTag by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageTag = LanguagePrefs.read(this)
        // 跟随系统深浅色：透明 scrim 让 App 背景透到状态栏，图标明暗由 Compose 侧
        // （TeaTalkTheme 里的 WindowStyleEffect）随主题切换。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            TeaTalkTheme {
                SystemBarsAppearance(androidx.compose.foundation.isSystemInDarkTheme())
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
        handleExternalCard(intent)
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
