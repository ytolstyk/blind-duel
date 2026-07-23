package com.tolstykh.blindduel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.ui.theme.DuelReloadPerfectZone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Reload progress indicator: a track ring, a gold arc marking the perfect-tap zone
 * ([GameConstants.RELOAD_PERFECT_ZONE_START_FRACTION] to
 * [GameConstants.RELOAD_PERFECT_ZONE_END_FRACTION]), and a small dial dot that travels once
 * clockwise around the ring as [progressFraction] goes 0 -> 1. Tapping anywhere inside the
 * visible circle invokes [onTap] — hit-tested against the ring's actual drawn radius, not its
 * bounding box, so a tap in the box's corner (outside the circle) doesn't count.
 *
 * [missTapAtMs] is a trigger timestamp (like [com.tolstykh.blindduel.ui.duel.OutgoingShot]'s
 * firedAtMs) — changing it flashes the ring red once, so a mistimed tap that pushes the reload
 * back reads as an explicit penalty rather than a rendering glitch.
 *
 * Uses its own [pointerInput]/[detectTapGestures] rather than
 * [androidx.compose.foundation.clickable] so the down event is consumed here: Compose dispatches
 * pointer events child-first, and [detectTapGestures] consumes the initial down, so DuelScreen's
 * full-size `detectDragGestures` (whose `awaitFirstDown` defaults to `requireUnconsumed = true`)
 * never sees a touch that started inside this ring.
 */
@Composable
fun ReloadRing(
    progressFraction: Float,
    isInPerfectZone: Boolean,
    missTapAtMs: Long?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surface
    val dialColor = MaterialTheme.colorScheme.primary

    val missFlash = remember { Animatable(0f) }
    LaunchedEffect(missTapAtMs) {
        if (missTapAtMs != null) {
            missFlash.snapTo(1f)
            missFlash.animateTo(0f, tween(GameConstants.RELOAD_MISS_FLASH_DURATION_MS.toInt(), easing = LinearEasing))
        }
    }

    Canvas(
        modifier = modifier
            .size(GameConstants.RELOAD_RING_SIZE_DP.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = minOf(size.width, size.height) / 2f
                        if (hypot(offset.x - center.x, offset.y - center.y) <= radius) onTap()
                    },
                )
            },
    ) {
        val strokeWidthPx = GameConstants.RELOAD_RING_STROKE_WIDTH_DP.dp.toPx()
        val ringRadiusPx = (size.minDimension - strokeWidthPx) / 2f
        val topLeft = Offset(
            (size.width - ringRadiusPx * 2f) / 2f,
            (size.height - ringRadiusPx * 2f) / 2f,
        )
        val arcSize = Size(ringRadiusPx * 2f, ringRadiusPx * 2f)

        // Track — full ring, drawn first so the perfect-zone arc and dial paint over it.
        drawArc(
            color = trackColor,
            startAngle = ARC_START_DEGREES,
            sweepAngle = FULL_CIRCLE_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx),
        )

        // Perfect-zone band, from RELOAD_PERFECT_ZONE_START_FRACTION to _END_FRACTION of the circle.
        val zoneStartDegrees = GameConstants.RELOAD_PERFECT_ZONE_START_FRACTION * FULL_CIRCLE_DEGREES
        val zoneSweepDegrees =
            (GameConstants.RELOAD_PERFECT_ZONE_END_FRACTION - GameConstants.RELOAD_PERFECT_ZONE_START_FRACTION) * FULL_CIRCLE_DEGREES
        drawArc(
            color = DuelReloadPerfectZone,
            startAngle = ARC_START_DEGREES + zoneStartDegrees,
            sweepAngle = zoneSweepDegrees,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidthPx),
        )

        // Dial — a small dot at the current progress point around the circumference.
        val dialAngleRadians = Math.toRadians((ARC_START_DEGREES + progressFraction * FULL_CIRCLE_DEGREES).toDouble())
        val dialCenter = Offset(
            x = topLeft.x + ringRadiusPx + ringRadiusPx * cos(dialAngleRadians).toFloat(),
            y = topLeft.y + ringRadiusPx + ringRadiusPx * sin(dialAngleRadians).toFloat(),
        )
        drawCircle(
            color = if (isInPerfectZone) DuelReloadPerfectZone else dialColor,
            radius = GameConstants.RELOAD_RING_DIAL_RADIUS_DP.dp.toPx(),
            center = dialCenter,
        )

        if (missFlash.value > 0f) {
            drawArc(
                color = Color.Red.copy(alpha = missFlash.value),
                startAngle = ARC_START_DEGREES,
                sweepAngle = FULL_CIRCLE_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx),
            )
        }
    }
}

private const val FULL_CIRCLE_DEGREES = 360f

// Compose's drawArc measures 0 degrees at 3 o'clock, clockwise — offsetting by -90 starts the
// dial/perfect-zone at 12 o'clock, matching CircularProgressIndicator's own convention.
private const val ARC_START_DEGREES = -90f
