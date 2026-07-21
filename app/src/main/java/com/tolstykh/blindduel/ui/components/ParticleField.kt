package com.tolstykh.blindduel.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.ui.theme.DuelParticle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val GRAVITY_MS2 = 9.8f

private enum class ParticleKind { STAR, DUST }

private data class Particle(
    val kind: ParticleKind,
    val xFraction: Float,
    val yFraction: Float,
    val radiusPx: Float,
    val twinkleSpeed: Float,
    val phase: Float,
    /** Dust only — how fast this particle falls relative to the others. */
    val fallSpeedFactor: Float,
    /** Dust only — per-particle phase offset so swirl wobble isn't perfectly synchronized. */
    val swirlPhaseOffset: Float,
)

/**
 * Ambient dust/star backdrop behind the duel character — purely decorative, no gameplay
 * meaning. Stars twinkle, parallax-shift and slowly drift; dust continuously falls and swirls
 * when [deviceTilt] reports the phone moving. [activeProjectileOffset] is invoked once per
 * draw call (not read eagerly by the caller) — a projectile in flight changes every animation
 * frame, and reading it only inside this Canvas's draw phase keeps that churn from forcing a
 * recomposition of whatever composable is passing it in. A non-null result (relative to canvas
 * center) pushes nearby dust away from that point.
 */
@Composable
fun ParticleField(
    modifier: Modifier = Modifier,
    deviceTilt: Offset = Offset.Zero,
    activeProjectileOffset: () -> Offset? = { null },
) {
    val particles = remember {
        List(GameConstants.AMBIENT_STAR_COUNT) {
            Particle(
                kind = ParticleKind.STAR,
                xFraction = Random.nextFloat(),
                yFraction = Random.nextFloat(),
                radiusPx = 1f + Random.nextFloat() * 2.2f,
                twinkleSpeed = 0.5f + Random.nextFloat() * 1.5f,
                phase = Random.nextFloat() * (2 * PI).toFloat(),
                fallSpeedFactor = 0f,
                swirlPhaseOffset = 0f,
            )
        } + List(GameConstants.AMBIENT_DUST_COUNT) {
            Particle(
                kind = ParticleKind.DUST,
                xFraction = Random.nextFloat(),
                yFraction = Random.nextFloat(),
                radiusPx = 2.5f + Random.nextFloat() * 4.5f,
                twinkleSpeed = 0.8f + Random.nextFloat() * 1.2f,
                phase = Random.nextFloat() * (2 * PI).toFloat(),
                fallSpeedFactor = 0.6f + Random.nextFloat() * 0.8f,
                swirlPhaseOffset = Random.nextFloat() * (2 * PI).toFloat(),
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ambientParticles")
    val twinklePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(GameConstants.STAR_TWINKLE_PHASE_DURATION_MS, easing = LinearEasing),
        ),
        label = "twinklePhase",
    )
    val fallPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(GameConstants.DUST_FALL_CYCLE_MS, easing = LinearEasing)),
        label = "fallPhase",
    )

    // Swirl "energy" grows when the phone jolts (a big frame-to-frame tilt change) and decays
    // a fixed fraction per accelerometer sample — samples arrive at a roughly steady rate, so
    // this is simpler than tracking wall-clock delta time and looks the same.
    var swirlEnergy by remember { mutableFloatStateOf(0f) }
    var previousTilt by remember { mutableStateOf(deviceTilt) }
    LaunchedEffect(deviceTilt) {
        val jerk = (deviceTilt - previousTilt).getDistance()
        swirlEnergy = (swirlEnergy * GameConstants.DUST_SWIRL_DECAY_PER_SAMPLE + jerk * GameConstants.DUST_SWIRL_GAIN)
            .coerceIn(0f, 1f)
        previousTilt = deviceTilt
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val projectilePosition = activeProjectileOffset()?.let {
            Offset(size.width / 2f, size.height / 2f) + it
        }
        drawParticles(particles, twinklePhase, fallPhase, deviceTilt, swirlEnergy, projectilePosition)
    }
}

private fun DrawScope.drawParticles(
    particles: List<Particle>,
    twinklePhase: Float,
    fallPhase: Float,
    tilt: Offset,
    swirlEnergy: Float,
    projectilePosition: Offset?,
) {
    val parallax = Offset(
        x = -tilt.x / GRAVITY_MS2 * GameConstants.STAR_PARALLAX_FACTOR_PX,
        y = tilt.y / GRAVITY_MS2 * GameConstants.STAR_PARALLAX_FACTOR_PX,
    )
    val windBiasX = -tilt.x / GRAVITY_MS2 * GameConstants.DUST_SWIRL_AMPLITUDE_PX

    particles.forEach { particle ->
        val twinkle = (sin(twinklePhase * particle.twinkleSpeed + particle.phase) + 1f) / 2f
        val alpha = (GameConstants.STAR_ALPHA_BASE + GameConstants.STAR_ALPHA_RANGE * twinkle)
            .coerceIn(GameConstants.STAR_ALPHA_MIN, 1f)

        val position = when (particle.kind) {
            ParticleKind.STAR -> {
                val autoDrift = cos(twinklePhase * GameConstants.STAR_AUTO_DRIFT_FREQUENCY + particle.phase) *
                    GameConstants.STAR_AUTO_DRIFT_PX
                val xFraction = (particle.xFraction + (parallax.x + autoDrift) / size.width).mod(1f)
                val yFraction = (particle.yFraction + parallax.y / size.height).mod(1f)
                Offset(xFraction * size.width, yFraction * size.height)
            }
            ParticleKind.DUST -> {
                val loopedYFraction = (particle.yFraction + fallPhase * particle.fallSpeedFactor).mod(1f)
                val wobble = sin(
                    fallPhase * 2f * PI.toFloat() * GameConstants.DUST_SWIRL_WOBBLE_CYCLES + particle.swirlPhaseOffset,
                )
                val swirlOffsetX = (wobble * GameConstants.DUST_SWIRL_AMPLITUDE_PX + windBiasX) * swirlEnergy
                val xPx = (particle.xFraction * size.width + swirlOffsetX).mod(size.width)
                Offset(xPx, loopedYFraction * size.height)
            }
        }

        val displayPosition = if (particle.kind == ParticleKind.DUST && projectilePosition != null) {
            pushedAwayFrom(position, projectilePosition)
        } else {
            position
        }

        drawCircle(color = DuelParticle.copy(alpha = alpha), radius = particle.radiusPx, center = displayPosition)
    }
}

private fun pushedAwayFrom(position: Offset, projectilePosition: Offset): Offset {
    val delta = position - projectilePosition
    val distanceSquared = delta.x * delta.x + delta.y * delta.y
    val radius = GameConstants.PROJECTILE_PUSH_RADIUS_PX
    // Squared-distance early-out avoids a sqrt for the common case of a particle far outside
    // the push radius; distanceSquared < 1f also guards the division below.
    if (distanceSquared >= radius * radius || distanceSquared < 1f) return position
    val distance = sqrt(distanceSquared)
    val falloff = 1f - distance / radius
    val pushMagnitude = GameConstants.PROJECTILE_PUSH_STRENGTH_PX * falloff * falloff
    return position + delta / distance * pushMagnitude
}
