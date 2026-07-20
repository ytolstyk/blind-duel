package com.tolstykh.blindduel.ui.duel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
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
import com.tolstykh.blindduel.ui.components.HealthDots
import com.tolstykh.blindduel.ui.components.KeepScreenOn
import com.tolstykh.blindduel.ui.components.ParticleField
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet
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
            val durationMs = (
                GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP / GameConstants.PROJECTILE_SPEED_DP_PER_SEC * 1000
                ).toInt()
            projectileProgress.animateTo(1f, tween(durationMs, easing = LinearEasing))
            viewModel.onProjectileAnimationFinished(outgoingShot.firedAtMs)
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
        ParticleField(modifier = Modifier.fillMaxSize())

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

            if (outgoingShot != null) {
                val travelDistancePx = GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP.dp.toPx()
                drawProjectile(
                    angleDegrees = outgoingShot.aimAngleDegrees,
                    distancePx = projectileProgress.value * travelDistancePx,
                    center = center,
                    radiusPx = GameConstants.PROJECTILE_SIZE_DP.dp.toPx() / 2f,
                )
            }
        }

        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = flashAlpha * GameConstants.DAMAGE_OVERLAY_ALPHA_MULTIPLIER)),
            )
        }

        HealthDots(
            remainingHealth = uiState.myHealth,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
        )

        DuelCooldownRing(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )

        if (viewModel.isPracticeMode) {
            TextButton(
                onClick = viewModel::onLeavePracticeClicked,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            ) { Text("Leave Practice") }
        }

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

private fun centerOf(size: IntSize): Offset = Offset(size.width / 2f, size.height / 2f)

private fun DrawScope.drawDamageGlow(angleDegrees: Float, alpha: Float, center: Offset) {
    if (alpha <= 0f) return
    val rad = Math.toRadians(angleDegrees.toDouble())
    val glowRadius = min(size.width, size.height) / 2f
    val glowCenter = center + Offset(
        x = (glowRadius * sin(rad)).toFloat(),
        y = (-glowRadius * cos(rad)).toFloat(),
    )
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

private fun DrawScope.drawProjectile(angleDegrees: Float, distancePx: Float, center: Offset, radiusPx: Float) {
    val rad = Math.toRadians(angleDegrees.toDouble())
    val position = center + Offset(
        x = (distancePx * sin(rad)).toFloat(),
        y = (-distancePx * cos(rad)).toFloat(),
    )
    drawCircle(color = DuelAccentViolet, radius = radiusPx, center = position)
}
