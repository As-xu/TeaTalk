package com.teatalk.xs

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.teatalk.xs.ui.HomeScreen
import com.teatalk.xs.ui.theme.TeaTalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M0 固定暗色主题：状态栏/导航栏图标用浅色，透明 scrim 才能透出 App 背景
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            TeaTalkTheme {
                HomeScreen()
            }
        }
    }
}
