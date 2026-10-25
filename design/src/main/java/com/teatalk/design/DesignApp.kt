package com.teatalk.design

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.teatalk.design.screens.ChatListSample
import com.teatalk.design.screens.ChatSample
import com.teatalk.design.screens.CharactersSample
import com.teatalk.design.screens.DetailSample
import com.teatalk.design.screens.ProfileSample
import com.teatalk.design.screens.SpecSample

class DesignActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        applyEdgeToEdge()
        setContent { DesignRoot() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
        applyEdgeToEdge()
    }

    /** 状态栏/导航栏图标随配色深浅切换（否则浅色底上白图标看不见） */
    private fun applyEdgeToEdge() {
        val dark = paletteState.value.dark
        val insets = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = insets, navigationBarStyle = insets)
    }

    /** 供 adb 批量截图：--es palette cream --es screen chat */
    private fun applyIntent(intent: android.content.Intent?) {
        val pid = intent?.getStringExtra("palette") ?: return
        palettes.firstOrNull { it.id == pid }?.let { paletteState.value = it }
        intent.getStringExtra("screen")?.let { name ->
            SampleScreen.entries.firstOrNull { it.name == name }?.let { screenState.value = it }
        }
    }
}


// ---------------------------------------------------------------- palettes

data class PaletteSet(
    val id: String,
    val label: String,
    val dark: Boolean,
    val primary: UiColor,
    val onPrimary: UiColor,
    val primaryContainer: UiColor,
    val onPrimaryContainer: UiColor,
    val secondary: UiColor,
    val secondaryContainer: UiColor,
    val tertiary: UiColor,
    val background: UiColor,
    val onBackground: UiColor,
    val surface: UiColor,
    val surfaceContainerLow: UiColor,
    val surfaceContainer: UiColor,
    val surfaceContainerHigh: UiColor,
    val surfaceContainerHighest: UiColor,
    val surfaceVariant: UiColor,
    val onSurfaceVariant: UiColor,
    val outline: UiColor,
    val outlineVariant: UiColor,
    val inverseSurface: UiColor,
    val inverseOnSurface: UiColor,
) {
    fun scheme() = if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, secondaryContainer = secondaryContainer, tertiary = tertiary,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onBackground,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = background, surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline, outlineVariant = outlineVariant,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            error = UiColor(0xFFFFB4AB),
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, secondaryContainer = secondaryContainer, tertiary = tertiary,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onBackground,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = surface, surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline, outlineVariant = outlineVariant,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            error = UiColor(0xFFBA1A1A),
        )
    }
}

private fun c(hex: Long) = UiColor(hex)

val palettes = listOf(
    PaletteSet(
        id = "cream", label = "A 奶油茶", dark = false,
        primary = c(0xFF8C5A33), onPrimary = c(0xFFFFFFFF),
        primaryContainer = c(0xFFF4DCC4), onPrimaryContainer = c(0xFF3A2210),
        secondary = c(0xFF6E7A5E), secondaryContainer = c(0xFFE4E8DA), tertiary = c(0xFFA8695A),
        background = c(0xFFFBF7F0), onBackground = c(0xFF2A2119),
        surface = c(0xFFFFFDF9), surfaceContainerLow = c(0xFFF6F0E6),
        surfaceContainer = c(0xFFF2EBDF), surfaceContainerHigh = c(0xFFEDE4D6),
        surfaceContainerHighest = c(0xFFE6DBC9),
        surfaceVariant = c(0xFFEFE7DA), onSurfaceVariant = c(0xFF6E6255),
        outline = c(0xFF8A7A6A), outlineVariant = c(0xFFDED2C2),
        inverseSurface = c(0xFF2F2924), inverseOnSurface = c(0xFFF6EFE6),
    ),
    PaletteSet(
        id = "latte", label = "B 奶咖", dark = false,
        primary = c(0xFF6B4A2F), onPrimary = c(0xFFFFFFFF),
        primaryContainer = c(0xFFE8D3BA), onPrimaryContainer = c(0xFF2A1505),
        secondary = c(0xFF8A7B62), secondaryContainer = c(0xFFEDE4D3), tertiary = c(0xFFA97155),
        background = c(0xFFF8F2E7), onBackground = c(0xFF241C14),
        surface = c(0xFFFFFDF8), surfaceContainerLow = c(0xFFF4EBDE),
        surfaceContainer = c(0xFFF0E5D5), surfaceContainerHigh = c(0xFFEADFCC),
        surfaceContainerHighest = c(0xFFE2D5BF),
        surfaceVariant = c(0xFFF0E6D7), onSurfaceVariant = c(0xFF6A5C4B),
        outline = c(0xFF857463), outlineVariant = c(0xFFD9CBB6),
        inverseSurface = c(0xFF2A241C), inverseOnSurface = c(0xFFF5EEE3),
    ),
    PaletteSet(
        id = "celadon", label = "C 青瓷", dark = false,
        primary = c(0xFF4F7A5E), onPrimary = c(0xFFFFFFFF),
        primaryContainer = c(0xFFCFE8D7), onPrimaryContainer = c(0xFF0D2B1B),
        secondary = c(0xFF7C7A5A), secondaryContainer = c(0xFFE8E7CE), tertiary = c(0xFFA8695A),
        background = c(0xFFFAF7F0), onBackground = c(0xFF20261F),
        surface = c(0xFFFFFFFF), surfaceContainerLow = c(0xFFF4F3EA),
        surfaceContainer = c(0xFFEFEFE4), surfaceContainerHigh = c(0xFFEAEADD),
        surfaceContainerHighest = c(0xFFE3E4D5),
        surfaceVariant = c(0xFFEDEEE1), onSurfaceVariant = c(0xFF5F6657),
        outline = c(0xFF7C8574), outlineVariant = c(0xFFD4D9C9),
        inverseSurface = c(0xFF2A2F28), inverseOnSurface = c(0xFFF2F2E8),
    ),
    PaletteSet(
        id = "qq", label = "D QQ蓝", dark = false,
        primary = c(0xFF0E86D4), onPrimary = c(0xFFFFFFFF),
        primaryContainer = c(0xFFD6ECFA), onPrimaryContainer = c(0xFF00344F),
        secondary = c(0xFF5A6B7C), secondaryContainer = c(0xFFE3E9EF), tertiary = c(0xFFE48A3C),
        background = c(0xFFF5F7FA), onBackground = c(0xFF1B1F24),
        surface = c(0xFFFFFFFF), surfaceContainerLow = c(0xFFF2F5F8),
        surfaceContainer = c(0xFFEDF1F5), surfaceContainerHigh = c(0xFFE7ECF1),
        surfaceContainerHighest = c(0xFFE0E7ED),
        surfaceVariant = c(0xFFEBF0F5), onSurfaceVariant = c(0xFF5B6672),
        outline = c(0xFF7A8794), outlineVariant = c(0xFFD2DAE2),
        inverseSurface = c(0xFF1F2429), inverseOnSurface = c(0xFFF1F4F7),
    ),
    PaletteSet(
        id = "dark", label = "暗色 · 奶油茶夜", dark = true,
        primary = c(0xFFE7BE96), onPrimary = c(0xFF4A2A0F),
        primaryContainer = c(0xFF5C3A1D), onPrimaryContainer = c(0xFFF8E3C4),
        secondary = c(0xFFC3CBB2), secondaryContainer = c(0xFF3A4238), tertiary = c(0xFFE0B0A0),
        background = c(0xFF171310), onBackground = c(0xFFEFE6DC),
        surface = c(0xFF1E1915), surfaceContainerLow = c(0xFF221C17),
        surfaceContainer = c(0xFF261F19), surfaceContainerHigh = c(0xFF2C251E),
        surfaceContainerHighest = c(0xFF332B23),
        surfaceVariant = c(0xFF2E2620), onSurfaceVariant = c(0xFFBFB2A4),
        outline = c(0xFF9A8B7C), outlineVariant = c(0xFF4A4038),
        inverseSurface = c(0xFFEFE6DC), inverseOnSurface = c(0xFF332B23),
    ),
)

enum class SampleScreen(val label: String) {
    ChatList("会话"),
    Characters("角色"),
    Detail("详情"),
    Chat("聊天"),
    Profile("我的"),
    Spec("规格"),
}


internal val paletteState = mutableStateOf(palettes[0])
internal val screenState = mutableStateOf(SampleScreen.ChatList)
@Composable
fun DesignTheme(palette: PaletteSet, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette.scheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignRoot() {
    val palette = paletteState.value
    val screen = screenState.value
    var showSwitcher by remember { mutableStateOf(false) }

    val appContext = androidx.compose.ui.platform.LocalContext.current
    SideEffect {
        val activity = appContext.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }

    DesignTheme(palette) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (screen) {
                SampleScreen.ChatList -> ChatListSample()
                SampleScreen.Characters -> CharactersSample()
                SampleScreen.Detail -> DetailSample()
                SampleScreen.Chat -> ChatSample()
                SampleScreen.Profile -> ProfileSample()
                SampleScreen.Spec -> SpecSample(palette)
            }

            // 隐形热区：右上角 72dp，点一下打开切换面板（截图里不可见）
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(72.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showSwitcher = true },
            )

            if (showSwitcher) {
                ModalBottomSheet(
                    onDismissRequest = { showSwitcher = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            "切换样张",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            palettes.take(3).forEach { p ->
                                Chip(p.label, selected = p.id == palette.id) { paletteState.value = p }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            palettes.drop(3).forEach { p ->
                                Chip(p.label, selected = p.id == palette.id) { paletteState.value = p }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "屏幕",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SampleScreen.entries.take(3).forEach { s ->
                                Chip(s.label, selected = s == screen) { screenState.value = s; showSwitcher = false }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SampleScreen.entries.drop(3).forEach { s ->
                                Chip(s.label, selected = s == screen) { screenState.value = s; showSwitcher = false }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}
