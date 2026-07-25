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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.tolstykh.blindduel.game.CrateMath
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.HitTest
import com.tolstykh.blindduel.game.WeaponType
import com.tolstykh.blindduel.ui.components.CALIBRATION_ALIGNMENT_HINT
import com.tolstykh.blindduel.ui.components.COMPASS_ACCURACY_HINT
import com.tolstykh.blindduel.ui.components.DismissibleHintChip
import com.tolstykh.blindduel.ui.components.KeepScreenOn
import com.tolstykh.blindduel.ui.components.LabeledAmmoDots
import com.tolstykh.blindduel.ui.components.LabeledHealthDots
import com.tolstykh.blindduel.ui.components.ParticleField
import com.tolstykh.blindduel.ui.components.ReloadRing
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet
import com.tolstykh.blindduel.ui.theme.DuelAccentVioletDim
import com.tolstykh.blindduel.ui.theme.DuelCrateAccent
import com.tolstykh.blindduel.ui.theme.DuelIncomingDanger
import com.tolstykh.blindduel.ui.theme.DuelIncomingDangerDim
import com.tolstykh.blindduel.ui.theme.DuelLaserBeam
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

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
    val activeCrate = viewModel.activeCrate

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
                tween(outgoingShot.travelDurationMs.toInt(), easing = LinearEasing),
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
            val durationMs = (incomingShot.travelDurationMs * overshoot).toInt()
            incomingProjectileProgress.animateTo(overshoot, tween(durationMs, easing = LinearEasing))
            viewModel.onIncomingProjectileAnimationFinished(incomingShot.receivedAtMs)
        }
    }

    // Shotgun pellet jitter angles: computed once per shot (remember, keyed on the shot's own
    // timestamp) rather than allocating a fresh Random and re-rolling every draw-phase frame —
    // the seed already made this deterministic per shot, so re-rolling every frame was pure
    // waste, not a correctness issue, but avoidable in the hottest render path in this file.
    val outgoingPelletJitters = remember(outgoingShot?.firedAtMs) {
        if (outgoingShot?.weaponType == WeaponType.SHOTGUN) shotgunPelletJitters(outgoingShot.firedAtMs) else emptyList()
    }
    val incomingPelletJitters = remember(incomingShot?.receivedAtMs) {
        if (incomingShot?.weaponType == WeaponType.SHOTGUN) shotgunPelletJitters(incomingShot.receivedAtMs) else emptyList()
    }

    // Explosion burst on the character, timed to viewModel.lastHitAtMs — the same trigger the
    // camera shake uses — so it appears exactly as the incoming projectile reaches distance 0.
    val explosionProgress = remember(viewModel.lastHitAtMs) { Animatable(0f) }
    LaunchedEffect(viewModel.lastHitAtMs) {
        if (viewModel.lastHitAtMs != null) {
            explosionProgress.animateTo(1f, tween(GameConstants.EXPLOSION_DURATION_MS, easing = LinearEasing))
        }
    }

    // A crate's one-shot fall animation — not rememberInfiniteTransition (that's for
    // ParticleField's endless ambient loop). Keyed on the crate's id so picking it up mid-fall
    // (activeCrate transitions to null, changing the key) cancels this LaunchedEffect and never
    // calls onCrateLanded for a crate that's already been cleared by the pickup itself.
    val crateFallProgress = remember(activeCrate?.id) { Animatable(0f) }
    if (activeCrate != null) {
        LaunchedEffect(activeCrate.id) {
            crateFallProgress.animateTo(1f, tween(activeCrate.fallDurationMs.toInt(), easing = LinearEasing))
            viewModel.onCrateLanded(activeCrate.id)
        }
    }

    // Pixels-per-dp — passed to the ViewModel (which has no Compose/Android dependency of its
    // own) so it can convert the crate's logical-dp position into the same px space the raw
    // drag offset is already in for a real Euclidean hit-test, and scale the bow's max drag
    // radius the same way.
    val density = LocalDensity.current
    val densityPxPerDp = remember(density) { with(density) { 1.dp.toPx() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> viewModel.onAimDrag(start - centerOf(size)) },
                    onDrag = { change, _ -> viewModel.onAimDrag(change.position - centerOf(size)) },
                    onDragEnd = { viewModel.onAimRelease(densityPxPerDp) },
                    onDragCancel = { viewModel.onAimRelease(densityPxPerDp) },
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

                // Dimmed while out of ammo: aimDrag now stays live even mid-reload (crate
                // targeting needs it), so a reduced-alpha aim line/character is the visible
                // "you can still aim but can't shoot yet" signal instead of looking identical
                // to a normal, ready-to-fire aim.
                val canFireNow = !uiState.isReloading
                val aimAlpha = if (canFireNow) 1f else GameConstants.OUT_OF_AMMO_AIM_ALPHA

                aimDrag?.let { drag ->
                    drawAimLine(drag, center, GameConstants.AIM_MAX_DRAG_RADIUS_DP.dp.toPx(), aimAlpha)
                }

                val rotationDegrees = aimDrag?.let { HitTest.angleFromDrag(it.x, it.y) } ?: 0f
                drawCharacter(rotationDegrees, center, characterRadiusPx, aimAlpha)

                val projectileRadiusPx = GameConstants.PROJECTILE_SIZE_DP.dp.toPx() / 2f
                val travelDistancePx = GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP.dp.toPx()

                if (outgoingShot != null) {
                    if (outgoingShot.weaponType == WeaponType.SHOTGUN) {
                        drawShotgunPellets(
                            aimAngleDegrees = outgoingShot.aimAngleDegrees,
                            distancePx = projectileProgress.value * travelDistancePx,
                            center = center,
                            radiusPx = projectileRadiusPx,
                            headColor = DuelAccentViolet,
                            tailColor = DuelAccentVioletDim,
                            travelPhase = projectileProgress.value,
                            jitterDegrees = outgoingPelletJitters,
                        )
                    } else {
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
                }

                // The laser's own live sweeping beam — a plain value with no fade/smoothing, read
                // directly here (draw phase) rather than hoisted into the composable body, the
                // same treatment as projectileProgress.value above, so a ~20Hz-updating angle
                // doesn't force this whole composable to recompose.
                viewModel.laserBeamAngleDegrees?.let { angle ->
                    drawLine(
                        color = DuelLaserBeam,
                        start = center,
                        end = center + offsetFromCenter(angle, travelDistancePx),
                        strokeWidth = GameConstants.AIM_LINE_STROKE_WIDTH_DP.dp.toPx() * GameConstants.LASER_BEAM_WIDTH_MULTIPLIER,
                    )
                }

                if (incomingShot != null) {
                    if (incomingShot.weaponType == WeaponType.SHOTGUN) {
                        drawShotgunPellets(
                            aimAngleDegrees = incomingShot.bearingDegrees,
                            distancePx = travelDistancePx * (1f - incomingProjectileProgress.value),
                            center = center,
                            radiusPx = projectileRadiusPx,
                            headColor = DuelIncomingDanger,
                            tailColor = DuelIncomingDangerDim,
                            travelPhase = incomingProjectileProgress.value,
                            jitterDegrees = incomingPelletJitters,
                        )
                    } else {
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
                }

                if (viewModel.lastHitAtMs != null && explosionProgress.value < 1f) {
                    drawExplosion(center, characterRadiusPx, explosionProgress.value)
                }

                if (activeCrate != null && crateFallProgress.value < 1f) {
                    val offsetDp = CrateMath.currentOffsetDp(
                        progress = crateFallProgress.value,
                        laneXDp = activeCrate.laneXDp,
                        topYDp = GameConstants.CRATE_SPAWN_TOP_Y_DP,
                        bottomYDp = GameConstants.CRATE_LAND_BOTTOM_Y_DP,
                        wobbleAmplitudeDp = GameConstants.CRATE_WOBBLE_AMPLITUDE_DP,
                        wobbleFrequency = GameConstants.CRATE_WOBBLE_FREQUENCY,
                    )
                    val cratePx = center + Offset(offsetDp.x.dp.toPx(), offsetDp.y.dp.toPx())
                    drawCrate(cratePx, GameConstants.CRATE_SIZE_DP.dp.toPx())
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

        DuelSniperLaserDot(viewModel = viewModel, modifier = Modifier.fillMaxSize())

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

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            LabeledAmmoDots(
                label = "AMMO",
                roundsRemaining = uiState.roundsRemaining,
                dotColor = DuelAccentEmber,
                onTap = viewModel::onManualReloadRequested,
                horizontalAlignment = Alignment.End,
                magazineSize = uiState.equippedWeaponConfig.magazineSize,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DuelReloadCompleteIndicator(
                triggerKey = viewModel.reloadCompletedAtMs,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (uiState.isReloading) {
                DuelReloadRing(viewModel = viewModel)
            } else {
                DuelBowChargeIndicator(viewModel = viewModel)
            }
        }

        DuelHitIndicator(
            triggerKey = viewModel.hitConfirmedAtMs,
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

// reloadProgress/isInPerfectReloadZone update ~20x/sec while a reload is active. Reading them
// here as a nested @Composable call (rather than as ReloadRing(...) arguments resolved directly
// in DuelScreen's own body) scopes recomposition to just this function — resolving it inline in
// DuelScreen would invalidate DuelScreen's whole restart scope on every tick, including the
// Canvas above, since Canvas's onDraw lambda is a plain (non-@Composable) function type that
// isn't automatically memoized and would be reallocated — and therefore redrawn — every time.
@Composable
private fun DuelReloadRing(viewModel: DuelViewModel, modifier: Modifier = Modifier) {
    val config = viewModel.uiState.equippedWeaponConfig
    ReloadRing(
        progressFraction = viewModel.reloadProgress,
        isInPerfectZone = viewModel.isInPerfectReloadZone,
        missTapAtMs = viewModel.reloadMissTapAtMs,
        onTap = viewModel::onReloadRingTapped,
        modifier = modifier,
        perfectZoneStartFraction = config.reloadPerfectZoneStartFraction,
        perfectZoneEndFraction = config.reloadPerfectZoneEndFraction,
    )
}

// bowChargeProgress ticks ~20x/sec while actively drawing the bow — isolated into its own
// composable for exactly the same reason as [DuelReloadRing] above.
@Composable
private fun DuelBowChargeIndicator(viewModel: DuelViewModel, modifier: Modifier = Modifier) {
    if (viewModel.uiState.equippedWeapon != WeaponType.BOW || viewModel.aimDrag == null) return
    val progress = viewModel.bowChargeProgress
    ReloadRing(
        progressFraction = progress,
        isInPerfectZone = progress >= 1f,
        missTapAtMs = null,
        onTap = {},
        modifier = modifier,
        perfectZoneStartFraction = 1f,
        perfectZoneEndFraction = 1f,
    )
}

// sniperDotOffsetDegrees is recomputed ~20x/sec whenever the opponent has a sniper aimed at me
// (see DuelViewModel.startTickers) — isolated into its own composable for the same reason as
// [DuelReloadRing], plus animateFloatAsState here needs a composition-phase read (unlike the
// laser beam angle above, which has no fade and can stay in the draw closure).
@Composable
private fun DuelSniperLaserDot(viewModel: DuelViewModel, modifier: Modifier = Modifier) {
    val offsetDegrees = viewModel.sniperDotOffsetDegrees
    val alpha by animateFloatAsState(targetValue = if (offsetDegrees != null) 1f else 0f, label = "sniperDot")
    if (alpha <= 0f) return
    Canvas(modifier = modifier) {
        val angle = offsetDegrees ?: return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val orbitRadiusPx = GameConstants.SNIPER_AIM_DOT_ORBIT_RADIUS_DP.dp.toPx()
        drawCircle(
            color = DuelIncomingDanger.copy(alpha = alpha),
            radius = GameConstants.SNIPER_AIM_DOT_RADIUS_DP.dp.toPx(),
            center = center + offsetFromCenter(angle, orbitRadiusPx),
        )
    }
}

// triggerKey-driven, same pattern as the camera shake in DuelShakeLayer below — a fresh
// Animatable per distinct reloadCompletedAtMs value, so the ViewModel never needs to reset a
// persistent "visible" flag back to false itself.
@Composable
private fun DuelReloadCompleteIndicator(triggerKey: Long?, modifier: Modifier = Modifier) {
    val alpha = remember(triggerKey) { Animatable(0f) }
    LaunchedEffect(triggerKey) {
        if (triggerKey == null) return@LaunchedEffect
        alpha.animateTo(1f, tween(GameConstants.RELOAD_COMPLETE_FADE_IN_MS, easing = LinearEasing))
        delay(GameConstants.RELOAD_COMPLETE_FLASH_DURATION_MS)
        alpha.animateTo(0f, tween(GameConstants.RELOAD_COMPLETE_FADE_OUT_MS, easing = LinearEasing))
    }
    if (alpha.value > 0f) {
        Text(
            text = "RELOADED",
            color = DuelAccentEmber.copy(alpha = alpha.value),
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier,
        )
    }
}

// Keyed off a trigger timestamp rather than a shared boolean (same reasoning as
// DuelReloadCompleteIndicator above) so that a rapid-fire burst of hits — now reachable with no
// per-shot cooldown gating fire rate — gives each landed hit its own independent show/hide
// window: `visible` and its LaunchedEffect are both scoped to `remember(triggerKey)`, so an
// earlier hit's delayed "hide" can never stomp a later hit's indicator still in its window.
// Isolated into its own composable, same reasoning as [DuelReloadRing] above, so the per-frame
// animateFloatAsState read doesn't invalidate DuelScreen's body (and reallocate its Canvas lambda).
@Composable
private fun DuelHitIndicator(triggerKey: Long?, modifier: Modifier = Modifier) {
    var visible by remember(triggerKey) { mutableStateOf(false) }
    LaunchedEffect(triggerKey) {
        if (triggerKey == null) return@LaunchedEffect
        visible = true
        delay(GameConstants.HIT_INDICATOR_DURATION_MS)
        visible = false
    }
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
// same mistake [DuelReloadRing] above documents avoiding for the reload ring.
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
// composable — like [DuelReloadRing] and [DuelParticleBackdrop] above — so the per-frame
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

private fun DrawScope.drawAimLine(drag: Offset, center: Offset, maxRadiusPx: Float, alpha: Float) {
    val distance = hypot(drag.x, drag.y)
    val clamped = if (distance <= maxRadiusPx) drag else drag * (maxRadiusPx / distance)
    drawLine(
        color = DuelAccentViolet.copy(alpha = alpha),
        start = center,
        end = center + clamped,
        strokeWidth = GameConstants.AIM_LINE_STROKE_WIDTH_DP.dp.toPx(),
    )
}

private fun DrawScope.drawCharacter(rotationDegrees: Float, center: Offset, radiusPx: Float, alpha: Float) {
    rotate(degrees = rotationDegrees, pivot = center) {
        drawCircle(color = DuelAccentEmber.copy(alpha = alpha), radius = radiusPx, center = center)
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

/** Computes [GameConstants.SHOTGUN_PELLET_TRAIL_COUNT] jitter offsets once per shot, seeded off
 * its own fire/receive timestamp — called from `remember(shot timestamp)` in the composable
 * body, not from the draw closure, so a fresh [Random] isn't allocated and re-rolled on every
 * single redraw frame during a shotgun shot's flight. */
private fun shotgunPelletJitters(seed: Long): List<Float> {
    val random = Random(seed)
    return List(GameConstants.SHOTGUN_PELLET_TRAIL_COUNT) {
        (random.nextFloat() * 2f - 1f) * GameConstants.SHOTGUN_PELLET_SPREAD_DEGREES
    }
}

/** Shotgun spread: draws one cosmetic pellet trail per entry in [jitterDegrees] (precomputed
 * once per shot — see [shotgunPelletJitters]), each offset from [aimAngleDegrees]. Hit-testing
 * never uses this — hit/miss stays the single widened-tolerance boolean from HitTest, decided
 * once at fire time. */
private fun DrawScope.drawShotgunPellets(
    aimAngleDegrees: Float,
    distancePx: Float,
    center: Offset,
    radiusPx: Float,
    headColor: Color,
    tailColor: Color,
    travelPhase: Float,
    jitterDegrees: List<Float>,
) {
    jitterDegrees.forEach { jitter ->
        drawProjectile(
            angleDegrees = aimAngleDegrees + jitter,
            distancePx = distancePx,
            center = center,
            radiusPx = radiusPx * GameConstants.SHOTGUN_PELLET_SIZE_FACTOR,
            headColor = headColor,
            tailColor = tailColor,
            travelPhase = travelPhase,
        )
    }
}

/** A falling weapon crate: a parachute canopy (arc + two riser lines) above a filled crate box,
 * drawn at its current fall position — position math lives in CrateMath so the renderer and the
 * ViewModel's hit-test can never disagree about where a crate visually is. */
private fun DrawScope.drawCrate(center: Offset, sizePx: Float) {
    val half = sizePx / 2f
    val canopyCenter = center - Offset(0f, sizePx * 1.1f)
    val canopyAlphaColor = DuelCrateAccent.copy(alpha = 0.7f)
    drawArc(
        color = canopyAlphaColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(canopyCenter.x - half, canopyCenter.y - half / 2f),
        size = Size(sizePx, sizePx),
        style = Stroke(width = sizePx * 0.12f),
    )
    drawLine(
        color = canopyAlphaColor,
        start = canopyCenter + Offset(-half, 0f),
        end = center + Offset(-half * 0.5f, -half * 0.5f),
        strokeWidth = sizePx * 0.04f,
    )
    drawLine(
        color = canopyAlphaColor,
        start = canopyCenter + Offset(half, 0f),
        end = center + Offset(half * 0.5f, -half * 0.5f),
        strokeWidth = sizePx * 0.04f,
    )
    drawRoundRect(
        color = DuelCrateAccent,
        topLeft = center - Offset(half, half),
        size = Size(sizePx, sizePx),
        cornerRadius = CornerRadius(sizePx * 0.15f, sizePx * 0.15f),
    )
}
