package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.tolstykh.blindduel.game.GameConstants
import kotlinx.coroutines.delay

private enum class OverlayPhase { HIDDEN, RECONNECTING, RECONNECTED }

/**
 * Full-screen overlay shown while [isReconnecting] — the duel/calibration is paused and
 * silently retrying reconnection underneath (see NearbyGameConnection.runReconnectLoop).
 * Deliberately consumes touch input (the same detectTapGestures-consumes-the-initial-down-event
 * mechanism [ReloadRing] already relies on) so a tap on a still-visible crate or aim ring during
 * the pause gets no silent no-op with no feedback.
 *
 * Owns its own "Reconnected" confirmation delay internally via a single [LaunchedEffect] keyed
 * on [isReconnecting], rather than the call site gating this composable with an `if` — an `if`
 * at the call site would remove this composable from the tree the instant [isReconnecting] flips
 * false, leaving no opportunity for a delayed dismiss to ever run. Callers must compose this
 * unconditionally (no `if (isReconnecting)` wrapper) for the confirmation to work.
 */
@Composable
fun ReconnectingOverlay(isReconnecting: Boolean, reconnectingSinceMs: Long?, modifier: Modifier = Modifier) {
    var phase by remember { mutableStateOf(OverlayPhase.HIDDEN) }
    var elapsedSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(isReconnecting) {
        if (isReconnecting) {
            phase = OverlayPhase.RECONNECTING
            while (true) {
                elapsedSeconds = reconnectingSinceMs?.let { (System.currentTimeMillis() - it) / 1000L } ?: 0L
                delay(GameConstants.RECONNECT_OVERLAY_TICK_MS)
            }
        } else if (phase == OverlayPhase.RECONNECTING) {
            phase = OverlayPhase.RECONNECTED
            delay(GameConstants.RECONNECT_OVERLAY_CONFIRMATION_DURATION_MS)
            phase = OverlayPhase.HIDDEN
        }
    }

    if (phase == OverlayPhase.HIDDEN) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = GameConstants.RECONNECT_OVERLAY_SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (phase == OverlayPhase.RECONNECTED) "Reconnected" else "Reconnecting… ${elapsedSeconds}s",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
}
