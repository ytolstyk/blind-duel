package com.tolstykh.blindduel.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.Serializable

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

    /** Signed angular delta from [from] to [to], normalized to (-180, 180] — positive means
     * [to] is clockwise of [from]. `abs(signedDelta(a, b)) == angularDistance(a, b)` always
     * holds; this is the missing signed sibling needed to broadcast a directional value
     * (e.g. the sniper's live aim heading) rather than just a magnitude. */
    fun signedDelta(from: Float, to: Float): Float = normalizeSigned(to - from)
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

    /**
     * The sniper's live laser-dot offset, recomputed in *my own* current frame (never cached
     * from when the MotionUpdate arrived, since my own heading keeps changing what "aimed
     * exactly at me" means on my screen). [shooterAbsoluteAimHeadingDegrees] is the shooter's
     * broadcast absolute (magnetic-frame) aim heading — see GameMessage.MotionUpdate's doc.
     * [bearingToShooterScreenRelativeDegrees] is my own live [computeBearingToOpponentOnScreen]
     * result (bearing *to* the shooter, in my screen-relative frame). Converting it back to the
     * absolute frame (re-adding [myCurrentHeadingDegrees]) and then taking its +180 reciprocal
     * (the shooter must aim at the *reverse* of the bearing I see them at, not the same bearing)
     * are both necessary — dropping either produces a systematic, hardware-noise-proof wrong
     * answer, which is exactly what SniperTelemetryTest in DuelEngineTest.kt exists to catch.
     * Returns ~0 when the shooter is aimed exactly at me.
     */
    fun sniperDotOffsetDegrees(
        shooterAbsoluteAimHeadingDegrees: Float,
        bearingToShooterScreenRelativeDegrees: Float,
        myCurrentHeadingDegrees: Float,
    ): Float {
        val absoluteBearingToShooter =
            AngleMath.normalizeSigned(bearingToShooterScreenRelativeDegrees + myCurrentHeadingDegrees)
        val reciprocalAbsoluteBearing = AngleMath.normalizeSigned(absoluteBearingToShooter + 180f)
        return AngleMath.signedDelta(from = reciprocalAbsoluteBearing, to = shooterAbsoluteAimHeadingDegrees)
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

    /** Straight-line estimated distance to the opponent, in meters — the same dead-reckoning
     * inputs [computeBearingToOpponentOnScreen] already uses for bearing, just returning the
     * missing scalar magnitude of that same relative-position vector instead of an angle. */
    fun estimatedDistanceMeters(
        initialOpponentPosition: Vector2,
        opponentStepVector: Vector2,
        myStepVector: Vector2,
    ): Float {
        val relative = (initialOpponentPosition + opponentStepVector) - myStepVector
        return hypot(relative.x, relative.y)
    }
}

/** Proximity zones for the "getting close" haptic — stricter (closer) zones have a higher
 * ordinal, which [ProximityMath.crossedIntoCloserZone] relies on directly. */
enum class ProximityZone { FAR, NEAR, VERY_NEAR }

/** Classifies [BearingModel.estimatedDistanceMeters] into a [ProximityZone] and detects the
 * zone-crossing that should trigger a proximity haptic. Deliberately stateless: the caller
 * (DuelViewModel) owns the "last zone" and "last trigger time" state across ticks, since a pure
 * function has nowhere to remember either between calls. */
object ProximityMath {
    fun zoneFor(distanceMeters: Float, nearMeters: Float, veryNearMeters: Float): ProximityZone = when {
        distanceMeters <= veryNearMeters -> ProximityZone.VERY_NEAR
        distanceMeters <= nearMeters -> ProximityZone.NEAR
        else -> ProximityZone.FAR
    }

    /** True only on a transition into a stricter (closer) zone — never on entering FAR, and
     * never while remaining in the same zone — so retreating and re-approaching the same
     * boundary can pulse again, but hovering right at it can't spam every tick. */
    fun crossedIntoCloserZone(previous: ProximityZone, current: ProximityZone): Boolean =
        current.ordinal > previous.ordinal
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

/** Applies a fixed amount of damage to a health value, clamped to a valid range — shared by
 * every path that turns a landed hit into a new health total (the local player taking a real
 * hit, and the practice-mode simulated opponent taking one). */
object HealthMath {
    fun applyDamage(currentHealth: Int, damage: Int, maxHealth: Int): Int =
        (currentHealth - damage).coerceIn(0, maxHealth)
}

/**
 * Manual-timed reload state machine, driven by explicit timestamps so it's trivially testable. A
 * tap while the dial's progress is inside [perfectZoneStartFraction, perfectZoneEndFraction]
 * completes the reload instantly (skill-based "active reload"); a mistimed tap instead extends it.
 */
class Reload(
    private val durationMs: Long,
    private val perfectZoneStartFraction: Float,
    private val perfectZoneEndFraction: Float,
    private val missPenaltyMs: Long,
) {
    fun progressFraction(startedAtMs: Long, nowMs: Long): Float =
        ((nowMs - startedAtMs).toFloat() / durationMs).coerceIn(0f, 1f)

    fun isComplete(startedAtMs: Long, nowMs: Long): Boolean = progressFraction(startedAtMs, nowMs) >= 1f

    fun isInPerfectZone(startedAtMs: Long, nowMs: Long): Boolean {
        val progress = progressFraction(startedAtMs, nowMs)
        return progress in perfectZoneStartFraction..perfectZoneEndFraction
    }

    /** A mistimed tap pushes the effective start time forward by [missPenaltyMs], extending the
     * remaining reload — but clamped to never exceed [nowMs], so even continuous rapid mashing
     * can only pin progress at exactly 0% (as if freshly restarted), never push it negative or
     * leave a backlog once tapping stops. */
    fun penalizedStartTime(startedAtMs: Long, nowMs: Long): Long =
        (startedAtMs + missPenaltyMs).coerceAtMost(nowMs)
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

/** The five weapons a player can wield. `@Serializable` (the one dependency this otherwise
 * pure file takes outside kotlin.math) since this rides on GameMessage.FireEvent/MotionUpdate. */
@Serializable
enum class WeaponType { PISTOL, SHOTGUN, BOW, SNIPER, LASER }

/**
 * Every per-weapon tunable, assembled once per [WeaponType] as GameConstants.WeaponConfigs.
 * The bow/laser-specific fields are unused (left at their default) on every other weapon.
 */
data class WeaponConfig(
    val type: WeaponType,
    // A separate field from `type.name`, deliberately: WeaponType rides the wire protocol
    // (GameMessage.FireEvent/MotionUpdate), so its constant names are a protocol detail, not UI
    // copy — a future rename for wire reasons shouldn't silently change what a player sees on
    // screen, matching the existing displayName-via-explicit-mapping convention ResultScreen
    // already uses for DuelOutcome rather than deriving text from an enum's own identifier.
    val displayName: String,
    val magazineSize: Int,
    val reloadDurationMs: Long,
    val reloadPerfectZoneStartFraction: Float,
    val reloadPerfectZoneEndFraction: Float,
    val reloadMissPenaltyMs: Long,
    val hitToleranceDegrees: Float,
    val damage: Int,
    val isLoud: Boolean,
    val projectileSpeedDpPerSec: Float,
    val projectileOffscreenTravelTimeMs: Long,
    // The receiver's incoming-projectile animation/network-delay duration for this weapon (see
    // GameMessage.FireEvent.travelDurationMs) — a per-weapon constant for every weapon except BOW,
    // whose real timing is instead derived per-shot from pull strength (DuelViewModel.fireBow) and
    // never captured here; BOW's value below is only ever used as Solo Test Mode's simulated-
    // opponent fallback (LoopbackGameConnection has no real drag gesture to derive pull strength
    // from). No default value, deliberately: every WeaponConfig below must set this explicitly, so
    // a future weapon addition that forgets it is a compile error, not a silent fallback to the
    // wrong timing — this field exists specifically because that used to be duplicated ad hoc at
    // each call site (DuelViewModel's laser/pistol paths, LoopbackGameConnection's simulated fire).
    val incomingTravelDurationMs: Long,
    // Meaningful only for BOW; unused (0) for other types.
    val bowMinChargeHoldMs: Long = 0L,
    val bowMinSpeedDpPerSec: Float = 0f,
    val bowMaxSpeedDpPerSec: Float = 0f,
    // Meaningful only for LASER; unused (0) for other types.
    val laserBeamDurationMs: Long = 0L,
    val laserSampleIntervalMs: Long = 0L,
)

/** Hunting bow charge/pull math: how long you must hold before firing is allowed, and how
 * far you pull back maps to projectile speed. */
object BowMath {
    fun pullFraction(dragDistance: Float, maxDragRadius: Float): Float =
        (dragDistance / maxDragRadius).coerceIn(0f, 1f)

    fun speedDpPerSec(pullFraction: Float, minSpeedDpPerSec: Float, maxSpeedDpPerSec: Float): Float =
        minSpeedDpPerSec + (maxSpeedDpPerSec - minSpeedDpPerSec) * pullFraction.coerceIn(0f, 1f)

    fun isFullyCharged(chargeStartedAtMs: Long, nowMs: Long, minChargeHoldMs: Long): Boolean =
        (nowMs - chargeStartedAtMs) >= minChargeHoldMs
}

/** Tracks whether a continuous laser sweep has registered a hit — sticky (never resets to
 * false once true), so a single beam activation scores at most one hit no matter how many
 * samples land within tolerance or in what order. */
class LaserBeamHitAccumulator {
    var registeredHit: Boolean = false
        private set

    fun sample(aimAngleDegrees: Float, bearingDegrees: Float, toleranceDegrees: Float) {
        if (!registeredHit) {
            registeredHit = HitTest.testHit(aimAngleDegrees, bearingDegrees, toleranceDegrees)
        }
    }
}

/** A single falling weapon crate — capped at one active at a time (see DuelViewModel). */
data class FallingCrate(
    val id: Long,
    val weaponType: WeaponType,
    val spawnedAtMs: Long,
    val laneXDp: Float,
    val fallDurationMs: Long,
)

/** Pure fall/wobble math for a [FallingCrate] — the single formula both the renderer and the
 * ViewModel's hit-test use, so they can never disagree about where a crate visually is. */
object CrateMath {
    fun fallProgress(spawnedAtMs: Long, nowMs: Long, fallDurationMs: Long): Float =
        ((nowMs - spawnedAtMs).toFloat() / fallDurationMs).coerceIn(0f, 1f)

    fun hasLanded(progress: Float): Boolean = progress >= 1f

    /** Current offset from screen center, in dp — y interpolates top to bottom over the fall,
     * x wobbles sinusoidally around [laneXDp] like a parachute swaying side to side. */
    fun currentOffsetDp(
        progress: Float,
        laneXDp: Float,
        topYDp: Float,
        bottomYDp: Float,
        wobbleAmplitudeDp: Float,
        wobbleFrequency: Float,
    ): Vector2 {
        val y = topYDp + (bottomYDp - topYDp) * progress
        val x = laneXDp + wobbleAmplitudeDp * sin(progress * wobbleFrequency * 2f * PI.toFloat())
        return Vector2(x, y)
    }

    /**
     * True if firing along [aimAngleDegrees] (0°=up, clockwise — same convention as
     * BearingModel/HitTest) sends a projectile within [hitRadiusDp] of the crate's current
     * [crateOffsetDp]. A point-to-ray distance test, deliberately not reusing the angle-only
     * tolerance model [HitTest] uses for the blind, never-rendered opponent: a crate is visible
     * and at a known position, so its hit region must be a truly fixed-size area around where it
     * actually is, not an angular cone whose absolute width would otherwise scale with the
     * crate's current distance from center. The crate's position is projected onto the aim
     * direction rather than compared to a single fixed point, so a crate behind the firing
     * direction (negative projection) is never hit, even if it happens to be within [hitRadiusDp]
     * of the ray's *infinite* extension backward.
     */
    fun isRayHit(aimAngleDegrees: Float, crateOffsetDp: Vector2, hitRadiusDp: Float): Boolean {
        val rad = Math.toRadians(aimAngleDegrees.toDouble())
        val dirX = sin(rad).toFloat()
        val dirY = -cos(rad).toFloat()
        val projection = crateOffsetDp.x * dirX + crateOffsetDp.y * dirY
        if (projection < 0f) return false
        val closestX = dirX * projection
        val closestY = dirY * projection
        return hypot(crateOffsetDp.x - closestX, crateOffsetDp.y - closestY) <= hitRadiusDp
    }
}

/** Fog-of-war confidence decay: the longer since the local player's own last detected step,
 * the more their own outgoing-shot hit tolerance widens — formalizing "your sensor estimate
 * gets worse over time" into an intentional, forgiving-cone mechanic. Never shown to the
 * player. See [HitToleranceMath] for how this combines with [DodgeMath]. */
object FogOfWarMath {
    /** Grows tolerance from [baseToleranceDegrees] toward `baseToleranceDegrees * (1 +
     * [maxBonusFraction])` as [msSinceLastStep] grows from [graceMs] to [fullDecayMs] — a
     * proportional (not flat-degree) bonus so it scales sanely across per-weapon base
     * tolerances from the sniper's narrow cone to the shotgun's wide one. Never applied before
     * [graceMs] elapses, so the first few seconds/steps of a duel aren't already forgiving. */
    fun widenedToleranceDegrees(
        baseToleranceDegrees: Float,
        msSinceLastStep: Long,
        graceMs: Long,
        fullDecayMs: Long,
        maxBonusFraction: Float,
    ): Float {
        if (msSinceLastStep <= graceMs) return baseToleranceDegrees
        val decayWindowMs = (fullDecayMs - graceMs).coerceAtLeast(1L)
        val fraction = ((msSinceLastStep - graceMs).toFloat() / decayWindowMs).coerceIn(0f, 1f)
        return baseToleranceDegrees * (1f + maxBonusFraction * fraction)
    }
}

/** Dodge/juke: a tappable HUD button that broadcasts a zero-payload `GameMessage.DodgeEvent`,
 * received and timestamped locally (never trusting a peer-supplied timestamp — sidesteps clock
 * skew) by the opponent's device, which then briefly narrows its own effective hit tolerance
 * against the dodging player. See [HitToleranceMath] for how this combines with [FogOfWarMath]. */
object DodgeMath {
    fun isDodging(nowMs: Long, dodgeReceivedAtMs: Long?, windowMs: Long): Boolean =
        dodgeReceivedAtMs != null && nowMs - dodgeReceivedAtMs < windowMs

    fun narrowedToleranceDegrees(toleranceDegrees: Float, multiplier: Float): Float =
        toleranceDegrees * multiplier

    /** Receive-side rate limit on an incoming DodgeEvent, not just the sender's own UI cooldown
     * — a modified client could otherwise spam DodgeEvent to stay permanently "dodging" (every
     * hit test narrowed, indefinitely). Rejecting anything faster than [cooldownMs] bounds a
     * spamming peer to the same duty cycle a well-behaved client produces. Pure and unit-tested
     * specifically because this is anti-cheat-relevant peer-trust logic, not just UI polish. */
    fun shouldAcceptDodgeEvent(nowMs: Long, lastAcceptedAtMs: Long?, cooldownMs: Long): Boolean =
        lastAcceptedAtMs == null || nowMs - lastAcceptedAtMs >= cooldownMs
}

/** The single place fog-of-war and dodge combine, so DuelViewModel's 3 hit-test call sites
 * never each re-derive this. Fog widens [FogOfWarMath] first; dodge then narrows
 * multiplicatively on top of the *widened* value via [DodgeMath] — note this order is not
 * itself what guarantees safety, since both steps are scalar multiplies and commute. The
 * actual safety invariant — dodge can never make a shot *easier* to land than not dodging, at
 * any fog level — holds only because
 * `(1 + GameConstants.FOG_OF_WAR_MAX_BONUS_FRACTION) * GameConstants.DODGE_TOLERANCE_MULTIPLIER < 1`.
 * This is a cross-constant relationship `GameConstants.kt` can't express on its own, so
 * [GameConstants.WeaponConfigs]'s own `init { check(...) }` pattern is reused below — it fails
 * loudly if a future retune of either constant breaks it, instead of silently letting a dodged
 * shot land more easily than an un-dodged one. See `HitToleranceMathTest` for the runtime
 * invariant check across every weapon's real tolerance. */
object HitToleranceMath {
    fun effectiveToleranceDegrees(
        baseToleranceDegrees: Float,
        msSinceLastStep: Long,
        isDodging: Boolean,
        fogGraceMs: Long,
        fogFullDecayMs: Long,
        fogMaxBonusFraction: Float,
        dodgeMultiplier: Float,
    ): Float {
        val widened = FogOfWarMath.widenedToleranceDegrees(
            baseToleranceDegrees, msSinceLastStep, fogGraceMs, fogFullDecayMs, fogMaxBonusFraction,
        )
        return if (isDodging) DodgeMath.narrowedToleranceDegrees(widened, dodgeMultiplier) else widened
    }

    init {
        // Fails the moment either constant changes in a way that would let a dodged shot land
        // more easily than an un-dodged one — a future "just bump the fog bonus a bit" tuning
        // pass otherwise has no other signal that it silently broke this invariant.
        check(
            (1f + GameConstants.FOG_OF_WAR_MAX_BONUS_FRACTION) * GameConstants.DODGE_TOLERANCE_MULTIPLIER < 1f,
        ) {
            "Dodge would no longer guarantee a narrower tolerance than baseline under max fog — " +
                "see HitToleranceMath's doc comment."
        }
    }
}
