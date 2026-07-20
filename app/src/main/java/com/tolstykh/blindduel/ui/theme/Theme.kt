package com.tolstykh.blindduel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DuelDarkColorScheme = darkColorScheme(
    primary = DuelAccentViolet,
    secondary = DuelAccentEmber,
    tertiary = DuelParticle,
    background = DuelBackgroundDark,
    surface = DuelSurfaceDark,
    onBackground = DuelOnBackgroundDark,
    onSurface = DuelOnBackgroundDark,
)

private val DuelLightColorScheme = lightColorScheme(
    primary = DuelAccentViolet,
    secondary = DuelAccentEmber,
    tertiary = DuelParticle,
    background = DuelBackgroundLight,
    surface = DuelSurfaceLight,
    onBackground = DuelOnBackgroundLight,
    onSurface = DuelOnBackgroundLight,
)

@Composable
fun BlindDuelTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DuelDarkColorScheme else DuelLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            // MaterialTheme alone doesn't set LocalContentColor — without this Surface, every
            // Text() using its default color falls back to a hardcoded black, which is
            // unreadable against the dark scheme's near-black background.
            Surface(color = colorScheme.background, contentColor = colorScheme.onBackground) {
                content()
            }
        },
    )
}
