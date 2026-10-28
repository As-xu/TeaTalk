package com.teatalk.xs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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
    // 显式给 errorContainer 角色：默认 lightColorScheme 的紫红容器会和奶油茶主色打架
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),
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
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 代码块/行内代码的底色。
 *
 * 不能直接用 surfaceContainerHighest：在奶油茶里它和气泡底色只差 ~1.05:1，
 * 代码块几乎「陷」进气泡。这里单独给一组与背景有明显层差的令牌。
 */
data class CodeSurfaces(val block: Color, val inline: Color)

val LocalCodeSurfaces = staticCompositionLocalOf {
    CodeSurfaces(Color(0xFFE6DAC6), Color(0xFFEDE2D0))
}

private val CreamCodeSurfaces = CodeSurfaces(Color(0xFFE6DAC6), Color(0xFFEDE2D0))
private val NightCodeSurfaces = CodeSurfaces(Color(0xFF100D0B), Color(0xFF171310))

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
    ) {
        CompositionLocalProvider(
            // 默认行高交给字体度量：只改 fontSize 的小字（11sp 脚注等）不再继承 bodyLarge 的 24sp 行高，
            // 否则小字的行距会比正文还大（UI 审阅列为 P0）。
            LocalTextStyle provides TextStyle.Default.copy(lineHeight = TextUnit.Unspecified),
            LocalCodeSurfaces provides if (darkTheme) NightCodeSurfaces else CreamCodeSurfaces,
        ) {
            content()
        }
    }
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

/** 字号令牌：UI 只从这里取字号，避免散落的 11/11.5/12.5sp 魔数（审阅 P1）。 */
object TypeScale {
    val s10 = 10.sp
    val s11 = 11.sp
    val s11_5 = 11.5.sp
    val s12 = 12.sp
    val s12_5 = 12.5.sp
    val s13 = 13.sp
    val s13_5 = 13.5.sp
    val s14 = 14.sp
    val s14_5 = 14.5.sp
    val s15 = 15.sp
    val s15_5 = 15.5.sp
    val s16 = 16.sp
    val s17 = 17.sp
    val s18 = 18.sp
    val s19 = 19.sp
    val s20 = 20.sp
    val s21 = 21.sp
    val s22 = 22.sp
    val s26 = 26.sp
    val s28 = 28.sp
    val s44 = 44.sp
}
