package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cooldown indicator. [secondsRemaining] updates once per second (the request calls for the
 * cooldown to visibly tick "once every second"); [progressFraction] drives the ring smoothly
 * between ticks so it doesn't look static.
 */
@Composable
fun CooldownRing(
    secondsRemaining: Int,
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (secondsRemaining <= 0) return
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface,
        )
        Text(
            text = secondsRemaining.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
