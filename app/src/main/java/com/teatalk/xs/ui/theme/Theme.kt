package com.teatalk.xs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

/**
 * 配色定稿：A「奶油茶」（用户 2026-10-25 拍板），暗色为「奶油茶夜」。
 * 数值与 UX 样张 `design/` 中的 cream / dark 两套完全一致，改这里必须同步改样张。
 */
private val CreamColors = lightColorScheme(
    primary = Color(0xFF8C5A33),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF4DCC4),
    onPrimaryContainer = Color(0xFF3A2210),
    secondary = Color(0xFF6E7A5E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E8DA),
    onSecondaryContainer = Color(0xFF1E2418),
    tertiary = Color(0xFFA8695A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6DCD4),
    onTertiaryContainer = Color(0xFF33120B),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF2A2119),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF2A2119),
    surfaceVariant = Color(0xFFEFE7DA),
    onSurfaceVariant = Color(0xFF6E6255),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F0E6),
    surfaceContainer = Color(0xFFF2EBDF),
    surfaceContainerHigh = Color(0xFFEDE4D6),
    surfaceContainerHighest = Color(0xFFE6DBC9),
    outline = Color(0xFF8A7A6A),
    outlineVariant = Color(0xFFDED2C2),
    inverseSurface = Color(0xFF2F2924),
    inverseOnSurface = Color(0xFFF6EFE6),
    inversePrimary = Color(0xFFE7BE96),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val TeaNightColors = darkColorScheme(
    primary = Color(0xFFE7BE96),
    onPrimary = Color(0xFF4A2A0F),
    primaryContainer = Color(0xFF5C3A1D),
    onPrimaryContainer = Color(0xFFF8E3C4),
    secondary = Color(0xFFC3CBB2),
    onSecondary = Color(0xFF2C3327),
    secondaryContainer = Color(0xFF3A4238),
    onSecondaryContainer = Color(0xFFE0E6D5),
    tertiary = Color(0xFFE0B0A0),
    onTertiary = Color(0xFF442A22),
    tertiaryContainer = Color(0xFF5E3B31),
    onTertiaryContainer = Color(0xFFFBDDD3),
    background = Color(0xFF171310),
    onBackground = Color(0xFFEFE6DC),
    surface = Color(0xFF1E1915),
    onSurface = Color(0xFFEFE6DC),
    surfaceVariant = Color(0xFF2E2620),
    onSurfaceVariant = Color(0xFFBFB2A4),
    surfaceContainerLowest = Color(0xFF120F0C),
    surfaceContainerLow = Color(0xFF221C17),
    surfaceContainer = Color(0xFF261F19),
    surfaceContainerHigh = Color(0xFF2C251E),
    surfaceContainerHighest = Color(0xFF332B23),
    outline = Color(0xFF9A8B7C),
    outlineVariant = Color(0xFF4A4038),
    inverseSurface = Color(0xFFEFE6DC),
    inverseOnSurface = Color(0xFF332B23),
    inversePrimary = Color(0xFF8C5A33),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * @param darkTheme 跟随系统深浅色。设置页的「跟随系统 / 常暗 / 常亮」在 M2 做，届时由设置驱动本参数。
 */
@Composable
fun TeaTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TeaNightColors else CreamColors,
        content = content,
    )
}

/** 状态栏/导航栏图标明暗跟随主题（浅色底要用深色图标，否则看不见）。 */
@Composable
fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = view.context.findActivityWindow() ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}

private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context.window
        context = context.baseContext
    }
    return null
}

