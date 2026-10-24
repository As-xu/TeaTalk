package com.teatalk.xs.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TeaTalk 主色：「茶青」。按方案 §0，暗色为默认（夜间聊天是主场景）。
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AE8BF),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFA7F2D2),
    secondary = Color(0xFFB7E4F9),
    onSecondary = Color(0xFF04323F),
    secondaryContainer = Color(0xFF1F4550),
    onSecondaryContainer = Color(0xFFCDE9F5),
    tertiary = Color(0xFFF2D5A8),
    onTertiary = Color(0xFF3F2E12),
    tertiaryContainer = Color(0xFF574326),
    onTertiaryContainer = Color(0xFFF8E3C4),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE2E8E5),
    surface = Color(0xFF161B1A),
    onSurface = Color(0xFFE2E8E5),
    surfaceVariant = Color(0xFF222927),
    onSurfaceVariant = Color(0xFFA9B5B1),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF131817),
    surfaceContainer = Color(0xFF171C1B),
    surfaceContainerHigh = Color(0xFF1E2423),
    surfaceContainerHighest = Color(0xFF262D2B),
    outline = Color(0xFF6F7B77),
    outlineVariant = Color(0xFF3A4340),
    inverseSurface = Color(0xFFE2E8E5),
    inverseOnSurface = Color(0xFF2A312F),
    inversePrimary = Color(0xFF00694B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00694B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8FEBC1),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF3E6374),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8F2),
    onSecondaryContainer = Color(0xFF0C2630),
    tertiary = Color(0xFF7A5B2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7DFB7),
    onTertiaryContainer = Color(0xFF2B1D05),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181D1C),
    surfaceVariant = Color(0xFFDDE5E1),
    onSurfaceVariant = Color(0xFF414945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F7F4),
    surfaceContainer = Color(0xFFEDF3F0),
    surfaceContainerHigh = Color(0xFFE7EEEA),
    surfaceContainerHighest = Color(0xFFE1E9E5),
    outline = Color(0xFF71807A),
    outlineVariant = Color(0xFFC0C9C4),
    inverseSurface = Color(0xFF2A312F),
    inverseOnSurface = Color(0xFFECF2EE),
    inversePrimary = Color(0xFF8AE8BF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/**
 * @param darkTheme M0 固定暗色（方案 §0：夜间聊天为主场景）。做设置页时改为「跟随系统 / 常暗 / 常亮」。
 */
@Composable
fun TeaTalkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
