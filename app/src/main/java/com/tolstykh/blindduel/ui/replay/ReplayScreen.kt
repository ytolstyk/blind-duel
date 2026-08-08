package com.tolstykh.blindduel.ui.replay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.ui.components.LabeledHealthDots
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelIncomingDanger

/**
 * Zoomed-out top-down replay of a finished duel (see [ReplayViewModel]): both players as dots
 * moving through real 2D space, each with a small rotated rectangle (phone silhouette) showing
 * facing direction, plus hit flashes and a live health readout synced to playback position.
 * Play/Pause + restart only, real-time speed — no scrubber (matches are short, and a scrubber
 * is unnecessary UI surface for v1).
 */
@Composable
fun ReplayScreen(onExit: () -> Unit, viewModel: ReplayViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Replay", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onExit) { Text("Close") }
        }

        Text(
            text = "Approximate paths from each phone's motion sensors — not GPS-accurate.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (viewModel.wasTruncated) {
            Text(
                text = "Replay ends early — this match ran long.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        ReplayCanvas(viewModel = viewModel, modifier = Modifier.weight(1f).fillMaxWidth())

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::togglePlayPause) {
                Text(if (viewModel.isPlaying) "Pause" else "Play")
            }
            OutlinedButton(onClick = viewModel::restart) { Text("Restart") }
        }
    }
}

// playbackPositionMs ticks at up to ~8Hz — isolated into its own composable for exactly the
// same recomposition-isolation reason as DuelReloadRing in DuelScreen.kt, including the small
// duration-label Text and health dots that also depend on it, so none of that churn forces
// ReplayScreen's own body (with its rarely-changing Play/Pause button) to recompose.
@Composable
private fun ReplayCanvas(viewModel: ReplayViewModel, modifier: Modifier = Modifier) {
    val tMs = viewModel.playbackPositionMs
    val localPos = viewModel.localPositionAt(tMs)
    val opponentPos = viewModel.opponentPositionAt(tMs)
    val localHeading = viewModel.localHeadingAt(tMs)
    val opponentHeading = viewModel.opponentHeadingAt(tMs)
    val myHealth = viewModel.myHealthAt(tMs)
    val opponentHealth = viewModel.opponentHealthAt(tMs)
    val myFlashAlpha = flashAlpha(tMs, viewModel.myHitFlashAtMs)
    val opponentFlashAlpha = flashAlpha(tMs, viewModel.opponentHitFlashAtMs)
    val scale = viewModel.scalePxPerMeter

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> viewModel.setCanvasSize(size.width.toFloat(), size.height.toFloat()) },
        ) {
            if (scale <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            drawPlayerDot(
                centerPx = viewModel.worldToScreenOffsetPx(localPos, center),
                headingDegrees = localHeading,
                color = DuelAccentEmber,
                flashAlpha = myFlashAlpha,
            )
            drawPlayerDot(
                centerPx = viewModel.worldToScreenOffsetPx(opponentPos, center),
                headingDegrees = opponentHeading,
                color = DuelIncomingDanger,
                flashAlpha = opponentFlashAlpha,
            )
        }

        LabeledHealthDots(
            label = viewModel.localPlayerName.uppercase(),
            remainingHealth = myHealth,
            dotColor = DuelAccentEmber,
            maxHealth = viewModel.matchMaxHealth,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        LabeledHealthDots(
            label = viewModel.opponentName.uppercase(),
            remainingHealth = opponentHealth,
            dotColor = DuelIncomingDanger,
            horizontalAlignment = Alignment.End,
            maxHealth = viewModel.matchMaxHealth,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        Text(
            text = "${formatMs(tMs)} / ${formatMs(viewModel.totalDurationMs)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
        )
    }
}

/** A player's dot: a small rotated rectangle (phone silhouette) oriented by [headingDegrees],
 * plus a filled dot at its center, plus a fading white ring when [flashAlpha] > 0 (a hit just
 * landed on this side — see [flashAlpha]'s own doc for why this is a pure derived value rather
 * than a reused live-duel Animatable). */
private fun DrawScope.drawPlayerDot(centerPx: Offset, headingDegrees: Float, color: Color, flashAlpha: Float) {
    val widthPx = GameConstants.REPLAY_PHONE_SILHOUETTE_WIDTH_DP.dp.toPx()
    val heightPx = GameConstants.REPLAY_PHONE_SILHOUETTE_HEIGHT_DP.dp.toPx()
    rotate(degrees = headingDegrees, pivot = centerPx) {
        drawRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(centerPx.x - widthPx / 2f, centerPx.y - heightPx / 2f),
            size = Size(widthPx, heightPx),
        )
    }
    drawCircle(color = color, radius = GameConstants.REPLAY_DOT_RADIUS_DP.dp.toPx(), center = centerPx)
    if (flashAlpha > 0f) {
        drawCircle(
            color = Color.White.copy(alpha = flashAlpha),
            radius = GameConstants.REPLAY_DOT_RADIUS_DP.dp.toPx() * GameConstants.REPLAY_HIT_FLASH_RING_RADIUS_MULTIPLIER,
            center = centerPx,
        )
    }
}

/** Replay's hit-flash progress must be a pure function of the virtual playback clock
 * ([tMs]/[flashAtMs]), not a reused live-duel `Animatable`/`tween` — the live idiom is driven by
 * Compose's real frame clock, so pausing replay wouldn't pause an in-flight `tween()`-driven
 * flash, since nothing would tie its progress to [ReplayViewModel.playbackPositionMs]. Returns 0
 * (no flash) before [flashAtMs] or once [GameConstants.DAMAGE_FLASH_DURATION_MS] has elapsed. */
private fun flashAlpha(tMs: Long, flashAtMs: Long?): Float {
    if (flashAtMs == null || tMs < flashAtMs) return 0f
    val elapsedMs = tMs - flashAtMs
    if (elapsedMs > GameConstants.DAMAGE_FLASH_DURATION_MS) return 0f
    return 1f - (elapsedMs.toFloat() / GameConstants.DAMAGE_FLASH_DURATION_MS)
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
