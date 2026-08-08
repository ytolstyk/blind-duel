package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.ConnectionQuality

/**
 * Small Good/Fair/Poor colored dot — a new tiny component rather than repurposing
 * [HealthDots]/`DotsRow`, since those are shaped around a fixed pip count for health, not a
 * single tri-state signal. See `GameConnection.connectionQuality()` for the underlying heuristic.
 */
@Composable
fun ConnectionQualityDot(quality: ConnectionQuality, modifier: Modifier = Modifier) {
    val color = when (quality) {
        ConnectionQuality.GOOD -> Color(0xFF4CAF50)
        ConnectionQuality.FAIR -> Color(0xFFFFC107)
        ConnectionQuality.POOR -> Color(0xFFF44336)
    }
    Canvas(modifier = modifier.size(DOT_SIZE_DP.dp)) {
        drawCircle(color = color)
    }
}

private const val DOT_SIZE_DP = 10f
