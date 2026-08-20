package com.tolstykh.blindduel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet
import com.tolstykh.blindduel.ui.theme.DuelCrateAccent
import com.tolstykh.blindduel.ui.theme.DuelParticle
import com.tolstykh.blindduel.ui.theme.DuelReloadPerfectZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val BALLOON_COLORS = listOf(
    DuelAccentViolet,
    DuelAccentEmber,
    DuelReloadPerfectZone,
    DuelCrateAccent,
    DuelParticle,
)

/**
 * Victory beat for ResultScreen (also reached from Solo Test/practice mode): balloons endlessly
 * rise from the bottom of [modifier]'s bounds and loop back once past the top. Each is poppable
 * by tapping it, purely for fun — popping has no effect on rematch/quit flow. [onBalloonPopped]
 * fires once per pop so the caller can pair a haptic pulse without this composable needing to
 * know about [com.tolstykh.blindduel.sensor.Haptics] itself.
 */
@Composable
fun VictoryBalloons(
    modifier: Modifier = Modifier,
    onBalloonPopped: () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val parentWidthPx = with(density) { maxWidth.toPx() }
        val parentHeightPx = with(density) { maxHeight.toPx() }

        repeat(GameConstants.RESULT_BALLOON_COUNT) { index ->
            val initialDelayMs = remember(index) {
                (index.toLong() * GameConstants.RESULT_BALLOON_MAX_RISE_DURATION_MS / GameConstants.RESULT_BALLOON_COUNT) +
                    Random.nextLong(0L, GameConstants.RESULT_BALLOON_INITIAL_JITTER_MS)
            }
            Balloon(
                initialDelayMs = initialDelayMs,
                parentWidthPx = parentWidthPx,
                parentHeightPx = parentHeightPx,
                density = density,
                onPopped = onBalloonPopped,
            )
        }
    }
}

/** One rise cycle's randomized parameters — bundled into a single [remember] instead of four
 * separate ones so a fresh cycle (natural loop or post-pop respawn) re-rolls everything in one
 * place. */
private class BalloonCycle(val riseDurationMs: Int, val xFraction: Float, val color: Color, val wobblePhaseSeed: Float) {
    companion object {
        fun random(): BalloonCycle {
            val margin = GameConstants.RESULT_BALLOON_SPAWN_MARGIN_FRACTION
            return BalloonCycle(
                riseDurationMs = Random.nextInt(
                    GameConstants.RESULT_BALLOON_MIN_RISE_DURATION_MS,
                    GameConstants.RESULT_BALLOON_MAX_RISE_DURATION_MS,
                ),
                xFraction = margin + Random.nextFloat() * (1f - 2f * margin),
                color = BALLOON_COLORS[Random.nextInt(BALLOON_COLORS.size)],
                wobblePhaseSeed = Random.nextFloat() * (2 * PI).toFloat(),
            )
        }
    }
}

/** Pixel-space sizing shared by every balloon at a given [Density] — computed once rather than
 * as four separate `with(density) { ... }` conversions inline in [Balloon]. */
private class BalloonMetrics(val widthPx: Float, val heightPx: Float, val stringLengthPx: Float, val wobbleAmplitudePx: Float) {
    companion object {
        fun from(density: Density): BalloonMetrics = with(density) {
            BalloonMetrics(
                widthPx = GameConstants.RESULT_BALLOON_WIDTH_DP.dp.toPx(),
                heightPx = GameConstants.RESULT_BALLOON_HEIGHT_DP.dp.toPx(),
                stringLengthPx = GameConstants.RESULT_BALLOON_STRING_LENGTH_DP.dp.toPx(),
                wobbleAmplitudePx = GameConstants.RESULT_BALLOON_WOBBLE_AMPLITUDE_DP.dp.toPx(),
            )
        }
    }
}

@Composable
private fun Balloon(
    initialDelayMs: Long,
    parentWidthPx: Float,
    parentHeightPx: Float,
    density: Density,
    onPopped: () -> Unit,
) {
    var generation by remember { mutableIntStateOf(0) }
    var popped by remember { mutableStateOf(false) }
    // Not re-keyed on generation — a rise cycle looping or being interrupted by a pop reuses the
    // same Animatable/gesture-detector identity instead of tearing both down and relaunching them
    // every ~6-11s (or every pop), which would otherwise risk a tap landing exactly on a
    // re-registration boundary and being silently dropped.
    val riseProgress = remember { Animatable(0f) }
    val popProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val cycle = remember(generation) { BalloonCycle.random() }
    val metrics = remember(density) { BalloonMetrics.from(density) }
    val knotPath = remember(metrics) { balloonKnotPath(metrics.widthPx, metrics.heightPx) }
    val startY = parentHeightPx + metrics.heightPx + metrics.stringLengthPx
    val endY = -(metrics.heightPx + metrics.stringLengthPx)
    val popSeeds = remember(generation) {
        randomSpraySeeds(
            seed = Random.nextLong(),
            count = GameConstants.RESULT_BALLOON_POP_PARTICLE_COUNT,
            spreadDegrees = GameConstants.PARTICLE_BURST_FULL_CIRCLE_SPREAD_DEGREES,
        )
    }

    LaunchedEffect(generation) {
        popped = false
        delay(if (generation == 0) initialDelayMs else Random.nextLong(0L, GameConstants.RESULT_BALLOON_RESPAWN_DELAY_MS))
        riseProgress.snapTo(0f)
        try {
            // A pop calls riseProgress.stop() from a different coroutine (popBalloon, below),
            // which interrupts this animateTo via a CancellationException specific to
            // riseProgress's own internal mutation tracking — it does not cancel this
            // LaunchedEffect's own job, so catching it here and simply not advancing the
            // generation is safe: popBalloon drives the next generation++ itself, after its
            // burst animation.
            riseProgress.animateTo(1f, tween(cycle.riseDurationMs, easing = LinearEasing))
            generation++
        } catch (_: CancellationException) {
            // Interrupted by a pop — nothing further to do here.
        }
    }

    Canvas(
        modifier = Modifier
            // Deferred into the offset lambda (a layout-phase closure) rather than computed as a
            // val above — riseProgress.value changes every animation frame, and reading it here
            // instead of at composition time keeps that churn from recomposing this composable.
            .offset {
                val progress = riseProgress.value
                val wobble = sin(
                    progress * 2f * PI.toFloat() * GameConstants.RESULT_BALLOON_WOBBLE_CYCLES + cycle.wobblePhaseSeed,
                ) * metrics.wobbleAmplitudePx
                val xPx = cycle.xFraction * parentWidthPx + wobble - metrics.widthPx / 2f
                val yPx = startY + (endY - startY) * progress
                IntOffset(xPx.roundToInt(), yPx.roundToInt())
            }
            .size(GameConstants.RESULT_BALLOON_WIDTH_DP.dp, (GameConstants.RESULT_BALLOON_HEIGHT_DP + GameConstants.RESULT_BALLOON_STRING_LENGTH_DP).dp)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (popped) return@detectTapGestures
                    popped = true
                    scope.launch {
                        popBalloon(riseProgress, popProgress, onPopped) { generation++ }
                    }
                }
            },
    ) {
        if (popped) {
            // The balloon body oval is drawn at heightPx * 0.92f tall (see drawBalloon below),
            // not a square widthPx x widthPx, so the burst center must match that, not widthPx.
            drawOmnidirectionalParticles(
                center = Offset(metrics.widthPx / 2f, metrics.heightPx * 0.92f / 2f),
                progress = popProgress.value,
                maxDistancePx = metrics.widthPx * GameConstants.RESULT_BALLOON_POP_MAX_DISTANCE_FACTOR,
                particleRadiusPx = metrics.widthPx * GameConstants.RESULT_BALLOON_POP_PARTICLE_SIZE_FACTOR,
                color = cycle.color,
                seeds = popSeeds,
            )
        } else {
            drawBalloon(cycle.color, metrics.widthPx, metrics.heightPx, metrics.stringLengthPx, knotPath)
        }
    }
}

/** Stops the rise, plays the pop burst, then hands off to a fresh cycle via [advanceGeneration]
 * once the respawn delay elapses. */
private suspend fun popBalloon(
    riseProgress: Animatable<Float, *>,
    popProgress: Animatable<Float, *>,
    onPopped: () -> Unit,
    advanceGeneration: () -> Unit,
) {
    riseProgress.stop()
    onPopped()
    popProgress.snapTo(0f)
    popProgress.animateTo(1f, tween(GameConstants.RESULT_BALLOON_POP_DURATION_MS, easing = LinearEasing))
    delay(GameConstants.RESULT_BALLOON_RESPAWN_DELAY_MS)
    advanceGeneration()
}

private fun balloonKnotPath(widthPx: Float, heightPx: Float): Path = Path().apply {
    moveTo(widthPx / 2f - widthPx * 0.08f, heightPx * 0.92f)
    lineTo(widthPx / 2f + widthPx * 0.08f, heightPx * 0.92f)
    lineTo(widthPx / 2f, heightPx)
    close()
}

private fun DrawScope.drawBalloon(color: Color, widthPx: Float, heightPx: Float, stringLengthPx: Float, knotPath: Path) {
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(widthPx / 2f, heightPx),
        end = Offset(widthPx / 2f, heightPx + stringLengthPx),
        strokeWidth = 1.5f,
    )
    drawPath(knotPath, color = color)
    drawOval(color = color, topLeft = Offset.Zero, size = Size(widthPx, heightPx * 0.92f))
    drawOval(
        color = Color.White.copy(alpha = 0.3f),
        topLeft = Offset(widthPx * 0.24f, heightPx * 0.14f),
        size = Size(widthPx * 0.22f, heightPx * 0.16f),
    )
}
