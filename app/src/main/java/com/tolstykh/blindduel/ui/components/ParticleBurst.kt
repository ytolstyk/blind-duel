package com.tolstykh.blindduel.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Screen-space offset of a point [distancePx] away from center along a compass [angleDegrees]
 * (0°=up, clockwise) — shared by every draw call across the app that positions something
 * relative to a center point, so the angle→offset sign convention only lives in one place. */
fun offsetFromCenter(angleDegrees: Float, distancePx: Float): Offset {
    val rad = Math.toRadians(angleDegrees.toDouble())
    return Offset((distancePx * sin(rad)).toFloat(), (-distancePx * cos(rad)).toFloat())
}

/** One randomized particle in a burst: an angle offset (interpreted relative to whatever base
 * angle the caller is spraying around, or used directly as a full-circle angle for an
 * omnidirectional burst) and a per-particle speed multiplier. A named type instead of
 * `Pair<Float, Float>` so a reader doesn't have to guess which element is which. */
data class ParticleSeed(val angleOffsetDegrees: Float, val speedFactor: Float)

/** Computes [count] [ParticleSeed]s once per burst, seeded off its own trigger timestamp — call
 * from `remember(trigger timestamp)` in the composable body, not the draw closure, so a fresh
 * [Random] isn't allocated and re-rolled on every single redraw frame of the burst.
 * [spreadDegrees] is the half-width of the uniform angle range each seed's `angleOffsetDegrees`
 * is drawn from (±[spreadDegrees]) — pass 180f for a full-circle omnidirectional burst. */
fun randomSpraySeeds(seed: Long, count: Int, spreadDegrees: Float): List<ParticleSeed> {
    val random = Random(seed)
    return List(count) {
        val angleOffsetDegrees = (random.nextFloat() * 2f - 1f) * spreadDegrees
        val speedFactor = GameConstants.PARTICLE_BURST_MIN_SPEED_FACTOR +
            random.nextFloat() * (1f - GameConstants.PARTICLE_BURST_MIN_SPEED_FACTOR)
        ParticleSeed(angleOffsetDegrees, speedFactor)
    }
}

/** An all-directions debris burst radiating from [center] — [seeds] must have been generated
 * with `spreadDegrees = 180f` (a full circle) so each `angleOffsetDegrees` can be used directly
 * as an absolute angle. Used for the crate explosion, the Result-screen loss avatar burst, and
 * the Result-screen victory-balloon pop — any "debris flies outward in every direction" beat. */
fun DrawScope.drawOmnidirectionalParticles(
    center: Offset,
    progress: Float,
    maxDistancePx: Float,
    particleRadiusPx: Float,
    color: Color,
    seeds: List<ParticleSeed>,
) {
    val alpha = (1f - progress).coerceIn(0f, 1f)
    seeds.forEach { seed ->
        val travelled = maxDistancePx * seed.speedFactor * progress
        val position = center + offsetFromCenter(seed.angleOffsetDegrees, travelled)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = particleRadiusPx * (1f - progress * GameConstants.PARTICLE_SHRINK_FACTOR),
            center = position,
        )
    }
}

/** Expanding, fading ring burst — used when an incoming shot lands on the character, when a
 * crate is destroyed, and for the Result screen's loss avatar burst; just recentered/recolored
 * per case. */
fun DrawScope.drawExplosion(center: Offset, radiusPx: Float, progress: Float, color: Color) {
    val maxRadiusPx = GameConstants.EXPLOSION_MAX_RADIUS_DP.dp.toPx()
    val coreAlpha = 1f - progress
    drawCircle(
        color = color.copy(alpha = coreAlpha * GameConstants.EXPLOSION_CORE_ALPHA_MULTIPLIER),
        radius = radiusPx * (1f + progress),
        center = center,
    )
    repeat(GameConstants.EXPLOSION_RING_COUNT) { index ->
        // Clamped below 1 regardless of tuning: see the invariant documented next to
        // EXPLOSION_RING_STAGGER in GameConstants — without this, a stagger*count combination
        // that pushes a ring's delay to/past 1 would divide by zero or go negative below.
        val delayFraction = (index * GameConstants.EXPLOSION_RING_STAGGER).coerceAtMost(0.9f)
        val ringProgress = ((progress - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)
        if (ringProgress <= 0f || ringProgress >= 1f) return@repeat
        val ringRadius = radiusPx + (maxRadiusPx - radiusPx) * ringProgress
        drawCircle(
            color = color.copy(alpha = (1f - ringProgress) * GameConstants.EXPLOSION_RING_ALPHA_MULTIPLIER),
            radius = ringRadius,
            center = center,
            style = Stroke(width = GameConstants.EXPLOSION_RING_STROKE_WIDTH_DP.dp.toPx()),
        )
    }
}
