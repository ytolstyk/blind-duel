package com.tolstykh.blindduel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// BlindDuel is always played at night in spirit — a single dark scheme keeps the
// ambient dust/star effects and duel visuals consistent across every screen.
private val DuelColorScheme = darkColorScheme(
    primary = DuelAccentViolet,
    secondary = DuelAccentEmber,
    tertiary = DuelParticle,
    background = DuelBackground,
    surface = DuelSurface,
    onBackground = DuelOnBackground,
    onSurface = DuelOnBackground,
)

@Composable
fun BlindDuelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DuelColorScheme,
        typography = Typography,
        content = content,
    )
}
