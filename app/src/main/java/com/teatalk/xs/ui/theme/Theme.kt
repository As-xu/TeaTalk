package com.teatalk.xs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TeaTalk 主色：「茶青」。暗色为默认（夜间聊天是主场景）。
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AE8BF),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFFB7E4F9),
    background = Color(0xFF101413),
    surface = Color(0xFF161B1A),
    surfaceVariant = Color(0xFF222927),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00694B),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3E6374),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun TeaTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
