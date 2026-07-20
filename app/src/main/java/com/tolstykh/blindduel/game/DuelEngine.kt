package com.tolstykh.blindduel.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, Android-free rules/math for BlindDuel. Kept separate from any sensor or
 * networking code so the whole bearing/hit/elimination model is unit-testable
 * without instrumentation.
 */

/** Angle helpers. All angles are in degrees; "signed" angles are normalized to (-180, 180]. */
object AngleMath {
    fun normalizeSigned(angleDegrees: Float): Float {
        var a = angleDegrees % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }

    /** Minimal angular distance between two angles, in [0, 180]. */
    fun angularDistance(a: Float, b: Float): Float = abs(normalizeSigned(a - b))
}

/**
 * The pedestrian-dead-reckoning-lite bearing model: each device seeds an assumed
 * opponent position at calibration, then nudges it using the opponent's own
 * reported step-vector deltas. See the plan's "Direction / Hit Model" section.
 */
object BearingModel {
    /** A vector of [magnitudeMeters] pointing in the direction of [headingDegrees], in the
     * shared magnetic-north frame (x = sin, y = cos — standard compass-to-Cartesian mapping). */
    private fun headingToVector(headingDegrees: Float, magnitudeMeters: Float): Vector2 {
        val rad = Math.toRadians(headingDegrees.toDouble())
        return Vector2(
            x = (magnitudeMeters * sin(rad)).toFloat(),
            y = (magnitudeMeters * cos(rad)).toFloat(),
        )
    }

    /** Where the opponent is assumed to be, in the shared magnetic-north frame, at calibration. */
    fun computeInitialOpponentPosition(
        baselineHeadingDegrees: Float,
        assumedDistanceMeters: Float,
    ): Vector2 = headingToVector(baselineHeadingDegrees, assumedDistanceMeters)

    /** A single detected step's displacement, nudged in the direction of [headingDegrees] —
     * the dead-reckoning position update applied on every step-detector event. */
    fun stepDelta(headingDegrees: Float, stepLengthMeters: Float): Vector2 =
        headingToVector(headingDegrees, stepLengthMeters)

    /**
     * The hidden, never-rendered "truth" used only for hit-testing: the bearing to the
     * opponent relative to this device's current facing direction (0 = straight ahead).
     */
    fun computeBearingToOpponentOnScreen(
        initialOpponentPosition: Vector2,
        opponentStepVector: Vector2,
        myStepVector: Vector2,
        myCurrentHeadingDegrees: Float,
    ): Float {
        val opponentAbsolutePosition = initialOpponentPosition + opponentStepVector
        val myAbsolutePosition = myStepVector
        val relative = opponentAbsolutePosition - myAbsolutePosition
        val bearingAbsoluteDegrees =
            Math.toDegrees(atan2(relative.x.toDouble(), relative.y.toDouble())).toFloat()
        return AngleMath.normalizeSigned(bearingAbsoluteDegrees - myCurrentHeadingDegrees)
    }

    /** True if the two players' baseline headings roughly oppose each other, as they should
     * if both actually faced each other during calibration. */
    fun isAlignedFaceToFace(
        myBaselineHeadingDegrees: Float,
        opponentBaselineHeadingDegrees: Float,
        toleranceDegrees: Float,
    ): Boolean {
        val diff = AngleMath.normalizeSigned(myBaselineHeadingDegrees - opponentBaselineHeadingDegrees)
        return abs(abs(diff) - 180f) <= toleranceDegrees
    }
}

/** Converts an on-screen aim drag into a phone-relative angle and tests it against a bearing. */
object HitTest {
    /** dx/dy are the release-point offset from screen center, in the same unit (px or dp). */
    fun angleFromDrag(dx: Float, dy: Float): Float {
        val degrees = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        return AngleMath.normalizeSigned(degrees)
    }

    fun testHit(
        aimAngleDegrees: Float,
        bearingToOpponentOnScreenDegrees: Float,
        toleranceDegrees: Float,
    ): Boolean = AngleMath.angularDistance(aimAngleDegrees, bearingToOpponentOnScreenDegrees) <= toleranceDegrees
}

/** Fire-cooldown state machine, driven by explicit timestamps so it's trivially testable. */
class Cooldown(private val durationMs: Long) {
    fun remainingMs(firedAtMs: Long, nowMs: Long): Long =
        (durationMs - (nowMs - firedAtMs)).coerceAtLeast(0L)

    fun isReady(firedAtMs: Long?, nowMs: Long): Boolean =
        firedAtMs == null || remainingMs(firedAtMs, nowMs) == 0L

    fun secondsRemaining(firedAtMs: Long, nowMs: Long): Int =
        ceil(remainingMs(firedAtMs, nowMs) / 1000.0).toInt()
}

sealed interface DuelOutcome {
    data object Ongoing : DuelOutcome
    data object Win : DuelOutcome
    data object Loss : DuelOutcome
    data object Draw : DuelOutcome
}

/**
 * Resolves win/loss/draw from each device's own locally-authoritative health plus the
 * opponent's last-received health. A device that hits 0 health waits up to [drawWindowMs]
 * for an in-flight opponent-zero update before declaring a loss, so simultaneous
 * double-elimination resolves as a draw on both devices instead of racing.
 */
class OutcomeResolver(private val drawWindowMs: Long) {
    fun resolve(
        myHealth: Int,
        opponentHealth: Int,
        myHealthZeroAtMs: Long?,
        nowMs: Long,
    ): DuelOutcome = when {
        myHealth <= 0 && opponentHealth <= 0 -> DuelOutcome.Draw
        opponentHealth <= 0 -> DuelOutcome.Win
        myHealth <= 0 -> {
            val zeroAt = myHealthZeroAtMs
            if (zeroAt != null && nowMs - zeroAt >= drawWindowMs) DuelOutcome.Loss else DuelOutcome.Ongoing
        }
        else -> DuelOutcome.Ongoing
    }
}
