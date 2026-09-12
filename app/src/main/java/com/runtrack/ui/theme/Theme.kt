package com.runtrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDeep,

    background = Background,
    onBackground = TextPrimary,

    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Background,
    onSurfaceVariant = TextSub,

    outline = Border,
    outlineVariant = Border,

    error = Danger,
    onError = Color.White,
    errorContainer = DangerBg,
    onErrorContainer = Danger
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryDeep,
    onPrimaryContainer = PrimaryLight,

    background = Color(0xFF111111),
    onBackground = Color.White,

    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9CA3AF),

    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF374151),

    error = Danger,
    onError = Color.White,
    errorContainer = DangerBg,
    onErrorContainer = Danger
)

@Composable
fun RunTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}