package com.teatalk.xs

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.teatalk.xs.data.ImportOutcome
import com.teatalk.xs.ui.HomeScreen
import com.teatalk.xs.ui.theme.SystemBarsAppearance
import com.teatalk.xs.ui.theme.TeaTalkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 外部打开/分享进来的卡：导入后把消息交给 HomeScreen 弹 snackbar。 */
    private var externalMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                )
            }
        }
        handleExternalCard(intent)
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
                is ImportOutcome.Success -> buildString {
                    append("已导入「${outcome.name}」")
                    if (outcome.warnings.isNotEmpty()) append("（${outcome.warnings.size} 条兼容提示）")
                }
                is ImportOutcome.Failure -> outcome.message
            }
            Toast.makeText(this@MainActivity, externalMessage, Toast.LENGTH_LONG).show()
        }
    }
}
