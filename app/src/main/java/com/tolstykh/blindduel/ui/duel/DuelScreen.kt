package com.tolstykh.blindduel.ui.duel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.HitTest
import com.tolstykh.blindduel.ui.components.CALIBRATION_ALIGNMENT_HINT
import com.tolstykh.blindduel.ui.components.COMPASS_ACCURACY_HINT
import com.tolstykh.blindduel.ui.components.CooldownRing
import com.tolstykh.blindduel.ui.components.DismissibleHintChip
import com.tolstykh.blindduel.ui.components.KeepScreenOn
import com.tolstykh.blindduel.ui.components.LabeledHealthDots
import com.tolstykh.blindduel.ui.components.ParticleField
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet
import com.tolstykh.blindduel.ui.theme.DuelAccentVioletDim
import com.tolstykh.blindduel.ui.theme.DuelIncomingDanger
import com.tolstykh.blindduel.ui.theme.DuelIncomingDangerDim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@Composable
fun DuelScreen(
    onDuelOver: () -> Unit,
    onConnectionLost: () -> Unit,
    viewModel: DuelViewModel = hiltViewModel(),
) {
    KeepScreenOn()

    val uiState = viewModel.uiState
    val aimDrag = viewModel.aimDrag
    val outgoingShot = viewModel.outgoingShot
    val incomingShot = viewModel.incomingShot

    LaunchedEffect(uiState.outcome) {
        if (uiState.outcome != DuelOutcome.Ongoing) onDuelOver()
    }
    LaunchedEffect(viewModel.sessionEnded) {
        if (viewModel.sessionEnded) onConnectionLost()
    }

    val flashAlpha by animateFloatAsState(
        targetValue = if (uiState.damageFlash) GameConstants.DAMAGE_FLASH_PEAK_ALPHA else 0f,
        label = "damageFlash",
    )

    val projectileProgress = remember(outgoingShot?.firedAtMs) { Animatable(0f) }
    if (outgoingShot != null) {
        LaunchedEffect(outgoingShot.firedAtMs) {
            projectileProgress.animateTo(
                1f,
                tween(GameConstants.PROJECTILE_TRAVEL_DURATION_MS.toInt(), easing = LinearEasing),
            )
            viewModel.onProjectileAnimationFinished(outgoingShot.firedAtMs)
        }
    }

    val incomingProjectileProgress = remember(incomingShot?.receivedAtMs) { Animatable(0f) }
    if (incomingShot != null) {
        LaunchedEffect(incomingShot.receivedAtMs) {
            // A miss overshoots past the character and off the far side instead of stopping —
            // a hit's arrival is timed to match DuelViewModel's delayed flash/shake trigger.
            val overshoot = if (incomingShot.hit) 1f else GameConstants.INCOMING_MISS_OVERSHOOT_MULTIPLIER
            val durationMs = (GameConstants.PROJECTILE_TRAVEL_DURATION_MS * overshoot).toInt()
            incomingProjectileProgress.animateTo(overshoot, tween(durationMs, easing = LinearEasing))
            viewModel.onIncomingProjectileAnimationFinished(incomingShot.receivedAtMs)
        }
    }

    // Explosion burst on the character, timed to viewModel.lastHitAtMs — the same trigger the
    // camera shake uses — so it appears exactly as the incoming projectile reaches distance 0.
    val explosionProgress = remember(viewModel.lastHitAtMs) { Animatable(0f) }
    LaunchedEffect(viewModel.lastHitAtMs) {
        if (viewModel.lastHitAtMs != null) {
            explosionProgress.animateTo(1f, tween(GameConstants.EXPLOSION_DURATION_MS, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> viewModel.onAimDrag(start - centerOf(size)) },
                    onDrag = { change, _ -> viewModel.onAimDrag(change.position - centerOf(size)) },
                    onDragEnd = { viewModel.onAimRelease() },
                    onDragCancel = { viewModel.onAimRelease() },
                )
            },
    ) {
        DuelShakeLayer(
            fireTriggerKey = outgoingShot?.firedAtMs,
            hitTriggerKey = viewModel.lastHitAtMs,
            modifier = Modifier.fillMaxSize(),
        ) {
            DuelParticleBackdrop(
                viewModel = viewModel,
                outgoingShot = outgoingShot,
                projectileProgress = projectileProgress,
                incomingShot = incomingShot,
                incomingProjectileProgress = incomingProjectileProgress,
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val characterRadiusPx = GameConstants.CHARACTER_SIZE_DP.dp.toPx() / 2f

                if (uiState.damageFlash) {
                    uiState.damageDirectionDegrees?.let { angleDegrees ->
                        drawDamageGlow(angleDegrees, flashAlpha, center)
                    }
                }

                aimDrag?.let { drag ->
                    drawAimLine(drag, center, GameConstants.AIM_MAX_DRAG_RADIUS_DP.dp.toPx())
                }

                val rotationDegrees = aimDrag?.let { HitTest.angleFromDrag(it.x, it.y) } ?: 0f
                drawCharacter(rotationDegrees, center, characterRadiusPx)

                val projectileRadiusPx = GameConstants.PROJECTILE_SIZE_DP.dp.toPx() / 2f
                val travelDistancePx = GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP.dp.toPx()

                if (outgoingShot != null) {
                    drawProjectile(
                        angleDegrees = outgoingShot.aimAngleDegrees,
                        distancePx = projectileProgress.value * travelDistancePx,
                        center = center,
                        radiusPx = projectileRadiusPx,
                        headColor = DuelAccentViolet,
                        tailColor = DuelAccentVioletDim,
                        travelPhase = projectileProgress.value,
                    )
                }

                if (incomingShot != null) {
                    drawProjectile(
                        angleDegrees = incomingShot.bearingDegrees,
                        distancePx = travelDistancePx * (1f - incomingProjectileProgress.value),
                        center = center,
                        radiusPx = projectileRadiusPx,
                        headColor = DuelIncomingDanger,
                        tailColor = DuelIncomingDangerDim,
                        travelPhase = incomingProjectileProgress.value,
                    )
                }

                if (viewModel.lastHitAtMs != null && explosionProgress.value < 1f) {
                    drawExplosion(center, characterRadiusPx, explosionProgress.value)
                }
            }
        }

        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = flashAlpha * GameConstants.DAMAGE_OVERLAY_ALPHA_MULTIPLIER)),
            )
        }

        LabeledHealthDots(
            label = "YOU",
            remainingHealth = uiState.myHealth,
            dotColor = DuelAccentEmber,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
        )

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            LabeledHealthDots(
                label = "ENEMY",
                remainingHealth = uiState.opponentHealth,
                dotColor = DuelIncomingDanger,
                horizontalAlignment = Alignment.End,
            )
            if (viewModel.isPracticeMode) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = viewModel::onLeavePracticeClicked) { Text("Leave Practice") }
            }
        }

        DuelCooldownRing(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )

        DuelHitIndicator(
            visible = uiState.showHitIndicator,
            modifier = Modifier.align(Alignment.Center),
        )

        if (uiState.showAccuracyWarning) {
            DismissibleHintChip(
                message = COMPASS_ACCURACY_HINT,
                onDismiss = viewModel::onDismissAccuracyWarning,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp),
            )
        } else if (uiState.showAlignmentWarning) {
            DismissibleHintChip(
                message = CALIBRATION_ALIGNMENT_HINT,
                onDismiss = viewModel::onDismissAlignmentWarning,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp),
            )
        }
    }
}

// Cooldown state changes up to 20x/sec while a shot is on cooldown. Reading it here as a
// nested @Composable call (rather than as CooldownRing(...) arguments resolved directly in
// DuelScreen's own body) scopes recomposition to just this function — resolving it inline in
// DuelScreen would invalidate DuelScreen's whole restart scope on every tick, including the
// Canvas above, since Canvas's onDraw lambda is a plain (non-@Composable) function type that
// isn't automatically memoized and would be reallocated — and therefore redrawn — every time.
@Composable
private fun DuelCooldownRing(viewModel: DuelViewModel, modifier: Modifier = Modifier) {
    CooldownRing(
        secondsRemaining = viewModel.cooldownSecondsRemaining,
        progressFraction = viewModel.cooldownProgress,
        modifier = modifier,
    )
}

// [visible] itself only flips twice per landed hit (~0.4Hz, well under the file's ~5Hz
// isolation threshold), but animateFloatAsState re-reads its value every animation frame while
// fading — isolated into its own composable, same reasoning as [DuelCooldownRing] above, so
// that per-frame read doesn't invalidate DuelScreen's body (and reallocate its Canvas lambda).
@Composable
private fun DuelHitIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f, label = "hitIndicator")
    if (alpha > 0f) {
        Text(
            text = "HIT",
            color = DuelAccentViolet.copy(alpha = alpha),
            style = MaterialTheme.typography.headlineMedium,
            modifier = modifier.offset(y = GameConstants.HIT_INDICATOR_VERTICAL_OFFSET_DP.dp),
        )
    }
}

// Device tilt updates at ~16Hz. A live projectile's position changes every animation frame
// while a shot is in flight, so [ParticleField] reads projectileProgress/incomingProjectileProgress
// itself, inside its own Canvas draw phase, via the activeProjectileOffset lambda below — not
// here in this composable's body. Resolving it here instead would subscribe *this composable's
// recomposition* to every animation frame of a shot (on top of the already-isolated tilt churn),
// same mistake [DuelCooldownRing] above documents avoiding for the cooldown ring.
@Composable
private fun DuelParticleBackdrop(
    viewModel: DuelViewModel,
    outgoingShot: OutgoingShot?,
    projectileProgress: Animatable<Float, *>,
    incomingShot: IncomingShot?,
    incomingProjectileProgress: Animatable<Float, *>,
    modifier: Modifier = Modifier,
) {
    val travelDistancePx = with(LocalDensity.current) { GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP.dp.toPx() }
    ParticleField(
        modifier = modifier,
        deviceTilt = viewModel.deviceTilt,
        activeProjectileOffset = {
            when {
                outgoingShot != null ->
                    offsetFromCenter(outgoingShot.aimAngleDegrees, projectileProgress.value * travelDistancePx)
                incomingShot != null ->
                    offsetFromCenter(incomingShot.bearingDegrees, travelDistancePx * (1f - incomingProjectileProgress.value))
                else -> null
            }
        },
    )
}

/** Screen-space offset of a point [distancePx] away from center along a compass [angleDegrees]
 * (0°=up, clockwise), shared by every draw call that positions something relative to the
 * character so the angle→offset sign convention only lives in one place. */
private fun offsetFromCenter(angleDegrees: Float, distancePx: Float): Offset {
    val rad = Math.toRadians(angleDegrees.toDouble())
    return Offset((distancePx * sin(rad)).toFloat(), (-distancePx * cos(rad)).toFloat())
}

// Camera shake on firing (small) and on taking a hit (larger). Isolated into its own
// composable — like [DuelCooldownRing] and [DuelParticleBackdrop] above — so the per-frame
// graphicsLayer reads driving the shake don't invalidate DuelScreen's own recompose scope; the
// wrapped [Box]'s graphicsLayer block is a draw-phase closure, so reading Animatable.value
// inside it only triggers a redraw of this layer, not a recomposition.
@Composable
private fun DuelShakeLayer(
    fireTriggerKey: Long?,
    hitTriggerKey: Long?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val fireShake = remember { Animatable(0f) }
    val hitShake = remember { Animatable(0f) }
    val density = LocalDensity.current
    val fireMagnitudePx = with(density) { GameConstants.CAMERA_SHAKE_FIRE_MAGNITUDE_DP.dp.toPx() }
    val hitMagnitudePx = with(density) { GameConstants.CAMERA_SHAKE_HIT_MAGNITUDE_DP.dp.toPx() }

    LaunchedEffect(fireTriggerKey) {
        if (fireTriggerKey != null) fireShake.triggerShakePulse()
    }
    LaunchedEffect(hitTriggerKey) {
        if (hitTriggerKey != null) hitShake.triggerShakePulse()
    }

    Box(
        modifier = modifier.graphicsLayer {
            // shake.value decays 1 -> 0; using its complement as the oscillation input means
            // the wobble runs through its cycles exactly once over the shake's lifetime.
            val fireElapsedFraction = 1f - fireShake.value
            val hitElapsedFraction = 1f - hitShake.value
            translationX = (
                sin(fireElapsedFraction * GameConstants.CAMERA_SHAKE_FIRE_CYCLES_X * TAU) * fireMagnitudePx * fireShake.value +
                    sin(hitElapsedFraction * GameConstants.CAMERA_SHAKE_HIT_CYCLES_X * TAU) * hitMagnitudePx * hitShake.value
                )
            translationY = (
                cos(fireElapsedFraction * GameConstants.CAMERA_SHAKE_FIRE_CYCLES_Y * TAU) * fireMagnitudePx * fireShake.value +
                    cos(hitElapsedFraction * GameConstants.CAMERA_SHAKE_HIT_CYCLES_Y * TAU) * hitMagnitudePx * hitShake.value
                )
        },
        content = content,
    )
}

private suspend fun Animatable<Float, AnimationVector1D>.triggerShakePulse() {
    snapTo(1f)
    animateTo(0f, tween(GameConstants.CAMERA_SHAKE_DURATION_MS, easing = LinearEasing))
}

private const val TAU = (2 * PI).toFloat()

private fun centerOf(size: IntSize): Offset = Offset(size.width / 2f, size.height / 2f)

private fun DrawScope.drawDamageGlow(angleDegrees: Float, alpha: Float, center: Offset) {
    if (alpha <= 0f) return
    val glowRadius = min(size.width, size.height) / 2f
    val glowCenter = center + offsetFromCenter(angleDegrees, glowRadius)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Red.copy(alpha = alpha), Color.Transparent),
            center = glowCenter,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = glowCenter,
    )
}

private fun DrawScope.drawAimLine(drag: Offset, center: Offset, maxRadiusPx: Float) {
    val distance = hypot(drag.x, drag.y)
    val clamped = if (distance <= maxRadiusPx) drag else drag * (maxRadiusPx / distance)
    drawLine(
        color = DuelAccentViolet,
        start = center,
        end = center + clamped,
        strokeWidth = GameConstants.AIM_LINE_STROKE_WIDTH_DP.dp.toPx(),
    )
}

private fun DrawScope.drawCharacter(rotationDegrees: Float, center: Offset, radiusPx: Float) {
    rotate(degrees = rotationDegrees, pivot = center) {
        drawCircle(color = DuelAccentEmber, radius = radiusPx, center = center)
    }
}

/** Expanding, fading ring burst drawn on the character when an incoming shot lands — gives the
 * impact a visible moment instead of the projectile simply reaching the character and vanishing. */
private fun DrawScope.drawExplosion(center: Offset, characterRadiusPx: Float, progress: Float) {
    val maxRadiusPx = GameConstants.EXPLOSION_MAX_RADIUS_DP.dp.toPx()
    val coreAlpha = 1f - progress
    drawCircle(
        color = DuelIncomingDanger.copy(alpha = coreAlpha * GameConstants.EXPLOSION_CORE_ALPHA_MULTIPLIER),
        radius = characterRadiusPx * (1f + progress),
        center = center,
    )
    repeat(GameConstants.EXPLOSION_RING_COUNT) { index ->
        // Clamped below 1 regardless of tuning: see the invariant documented next to
        // EXPLOSION_RING_STAGGER in GameConstants — without this, a stagger*count combination
        // that pushes a ring's delay to/past 1 would divide by zero or go negative below.
        val delayFraction = (index * GameConstants.EXPLOSION_RING_STAGGER).coerceAtMost(0.9f)
        val ringProgress = ((progress - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)
        if (ringProgress <= 0f || ringProgress >= 1f) return@repeat
        val radius = characterRadiusPx + (maxRadiusPx - characterRadiusPx) * ringProgress
        drawCircle(
            color = DuelIncomingDanger.copy(alpha = (1f - ringProgress) * GameConstants.EXPLOSION_RING_ALPHA_MULTIPLIER),
            radius = radius,
            center = center,
            style = Stroke(width = GameConstants.EXPLOSION_RING_STROKE_WIDTH_DP.dp.toPx()),
        )
    }
}

/** Draws a projectile with a flame-like tail that flops perpendicular to its travel direction
 * and fades/cools from [headColor] to [tailColor] toward the back. [travelPhase] drives the
 * tail's wobble — the outgoing/incoming shot's own travel progress, so the flop animates in
 * lockstep with the projectile's flight rather than needing separate animation state. */
private fun DrawScope.drawProjectile(
    angleDegrees: Float,
    distancePx: Float,
    center: Offset,
    radiusPx: Float,
    headColor: Color,
    tailColor: Color,
    travelPhase: Float,
) {
    val direction = offsetFromCenter(angleDegrees, 1f)
    val perpendicular = Offset(-direction.y, direction.x)

    val segmentSpacingPx = GameConstants.PROJECTILE_TAIL_SPACING_DP.dp.toPx()
    val wobbleAmplitudePx = GameConstants.PROJECTILE_TAIL_WOBBLE_AMPLITUDE_DP.dp.toPx()
    val segmentCount = GameConstants.PROJECTILE_TAIL_SEGMENT_COUNT

    for (i in segmentCount downTo 1) {
        val fraction = i.toFloat() / segmentCount
        val segmentDistance = distancePx - i * segmentSpacingPx
        val wobblePhaseOffset = i * (TAU / segmentCount)
        val wobble = sin(travelPhase * GameConstants.PROJECTILE_TAIL_WOBBLE_FREQUENCY + wobblePhaseOffset)
        val flop = wobble * wobbleAmplitudePx * fraction
        val segmentCenter = center + direction * segmentDistance + perpendicular * flop
        drawCircle(
            color = lerp(headColor, tailColor, fraction)
                .copy(alpha = (headColor.alpha * (1f - fraction * GameConstants.PROJECTILE_TAIL_FADE_FACTOR)).coerceIn(0f, 1f)),
            radius = radiusPx * (1f - fraction * GameConstants.PROJECTILE_TAIL_SHRINK_FACTOR),
            center = segmentCenter,
        )
    }

    drawCircle(color = headColor, radius = radiusPx, center = center + direction * distancePx)
}
