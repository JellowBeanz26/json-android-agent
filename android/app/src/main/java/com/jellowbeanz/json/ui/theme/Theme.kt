package com.jellowbeanz.json.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun JsonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentId: String = DEFAULT_ACCENT,
    content: @Composable () -> Unit,
) {
    val accent = accentById(accentId)
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = accent.color,
            onPrimary = accent.onColor,
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnBackground,
            surfaceVariant = DarkSurfaceElevated,
            onSurfaceVariant = DarkOnMuted,
            outline = DarkBorder,
        )
    } else {
        lightColorScheme(
            primary = accent.color,
            onPrimary = accent.onColor,
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnBackground,
            surfaceVariant = LightSurfaceElevated,
            onSurfaceVariant = LightOnMuted,
            outline = LightBorder,
        )
    }
    MaterialTheme(colorScheme = colors, typography = JsonTypography, content = content)
}
