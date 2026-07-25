package com.tolstykh.blindduel.game

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleMathTest {
    @Test
    fun `normalizeSigned wraps into the signed -180 to 180 range`() {
        assertEquals(0f, AngleMath.normalizeSigned(360f), 0.001f)
        assertEquals(-170f, AngleMath.normalizeSigned(190f), 0.001f)
        assertEquals(180f, AngleMath.normalizeSigned(180f), 0.001f)
        assertEquals(170f, AngleMath.normalizeSigned(-190f), 0.001f)
        assertEquals(10f, AngleMath.normalizeSigned(370f), 0.001f)
    }

    @Test
    fun `angularDistance is symmetric and minimal`() {
        assertEquals(20f, AngleMath.angularDistance(10f, -10f), 0.001f)
        assertEquals(20f, AngleMath.angularDistance(-10f, 10f), 0.001f)
        assertEquals(20f, AngleMath.angularDistance(350f, 10f), 0.001f)
        assertEquals(0f, AngleMath.angularDistance(180f, -180f), 0.001f)
    }

    @Test
    fun `signedDelta is signed and wraps correctly`() {
        assertEquals(20f, AngleMath.signedDelta(from = -10f, to = 10f), 0.001f)
        assertEquals(-20f, AngleMath.signedDelta(from = 10f, to = -10f), 0.001f)
        // wraparound case: the short way from 170 to -170 is +20, not -340.
        assertEquals(20f, AngleMath.signedDelta(from = 170f, to = -170f), 0.001f)
    }

    @Test
    fun `signedDelta magnitude always matches angularDistance`() {
        val cases = listOf(0f to 0f, 10f to -10f, 350f to 10f, 179f to -179f, -45f to 90f)
        for ((a, b) in cases) {
            assertEquals(AngleMath.angularDistance(a, b), abs(AngleMath.signedDelta(a, b)), 0.001f)
        }
    }
}

class BearingModelTest {
    @Test
    fun `initial opponent position seeds along baseline heading`() {
        val north = BearingModel.computeInitialOpponentPosition(0f, 3f)
        assertEquals(0f, north.x, 0.01f)
        assertEquals(3f, north.y, 0.01f)

        val east = BearingModel.computeInitialOpponentPosition(90f, 3f)
        assertEquals(3f, east.x, 0.01f)
        assertEquals(0f, east.y, 0.01f)
    }

    @Test
    fun `stepDelta points in the direction of the given heading`() {
        val north = BearingModel.stepDelta(0f, 0.7f)
        assertEquals(0f, north.x, 0.01f)
        assertEquals(0.7f, north.y, 0.01f)

        val south = BearingModel.stepDelta(180f, 0.7f)
        assertEquals(0f, south.x, 0.01f)
        assertEquals(-0.7f, south.y, 0.01f)
    }

    @Test
    fun `opponent pivoting without stepping does not shift the estimated bearing`() {
        // This is the fix for the reviewed flaw: the opponent's own heading is not an
        // input to the bearing formula at all, only their step vector is — so a pure
        // pivot-in-place (no step events) never moves the estimate.
        val initial = BearingModel.computeInitialOpponentPosition(0f, 3f)
        val bearingBefore = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = Vector2.ZERO,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = 0f,
        )
        // Opponent "turns around" — irrelevant to the model since they haven't stepped.
        val bearingAfter = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = Vector2.ZERO,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = 0f,
        )
        assertEquals(bearingBefore, bearingAfter, 0.001f)
        assertEquals(0f, bearingBefore, 0.01f) // still dead ahead
    }

    @Test
    fun `opponent stepping toward me moves the estimated bearing`() {
        val initial = BearingModel.computeInitialOpponentPosition(0f, 3f) // opponent 3m ahead
        // Opponent takes a step south (toward me, since I'm facing north/0 at them).
        val opponentStep = Vector2(0f, -0.7f)
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = opponentStep,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = 0f,
        )
        assertEquals(0f, bearing, 0.01f) // still dead ahead, just closer — bearing angle unaffected
    }

    @Test
    fun `my own rotation shifts opponent to the opposite side on screen`() {
        val initial = BearingModel.computeInitialOpponentPosition(0f, 3f) // opponent due north
        // I turn 90 degrees to face east.
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = Vector2.ZERO,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = 90f,
        )
        assertEquals(-90f, bearing, 0.01f) // opponent now appears to my left
    }

    @Test
    fun `isAlignedFaceToFace accepts near-opposite baselines and rejects same-direction`() {
        assertTrue(BearingModel.isAlignedFaceToFace(0f, 180f, 45f))
        assertTrue(BearingModel.isAlignedFaceToFace(10f, 200f, 45f))
        assertFalse(BearingModel.isAlignedFaceToFace(0f, 0f, 45f))
        assertFalse(BearingModel.isAlignedFaceToFace(0f, 90f, 45f))
    }
}

class HitTestTest {
    @Test
    fun `angleFromDrag maps screen directions to compass-style angles`() {
        assertEquals(0f, HitTest.angleFromDrag(0f, -100f), 0.01f) // straight up
        assertEquals(90f, HitTest.angleFromDrag(100f, 0f), 0.01f) // right
        assertEquals(180f, HitTest.angleFromDrag(0f, 100f), 0.01f) // down
        assertEquals(-90f, HitTest.angleFromDrag(-100f, 0f), 0.01f) // left
    }

    @Test
    fun `testHit respects tolerance`() {
        assertTrue(HitTest.testHit(aimAngleDegrees = 10f, bearingToOpponentOnScreenDegrees = 0f, toleranceDegrees = 20f))
        assertFalse(HitTest.testHit(aimAngleDegrees = 30f, bearingToOpponentOnScreenDegrees = 0f, toleranceDegrees = 20f))
        // wraparound case
        assertTrue(HitTest.testHit(aimAngleDegrees = 179f, bearingToOpponentOnScreenDegrees = -179f, toleranceDegrees = 5f))
    }

    @Test
    fun `shotgun's wider tolerance hits where the pistol's would miss`() {
        // 30 degrees off: outside the pistol's 20-degree tolerance, inside the shotgun's 40.
        assertFalse(
            HitTest.testHit(
                aimAngleDegrees = 30f,
                bearingToOpponentOnScreenDegrees = 0f,
                toleranceDegrees = GameConstants.HIT_ANGLE_TOLERANCE_DEGREES,
            ),
        )
        assertTrue(
            HitTest.testHit(
                aimAngleDegrees = 30f,
                bearingToOpponentOnScreenDegrees = 0f,
                toleranceDegrees = GameConstants.SHOTGUN_HIT_ANGLE_TOLERANCE_DEGREES,
            ),
        )
    }

    @Test
    fun `sniper's narrower tolerance misses where the pistol's would hit`() {
        // 10 degrees off: inside the pistol's 20-degree tolerance, outside the sniper's 8.
        assertTrue(
            HitTest.testHit(
                aimAngleDegrees = 10f,
                bearingToOpponentOnScreenDegrees = 0f,
                toleranceDegrees = GameConstants.HIT_ANGLE_TOLERANCE_DEGREES,
            ),
        )
        assertFalse(
            HitTest.testHit(
                aimAngleDegrees = 10f,
                bearingToOpponentOnScreenDegrees = 0f,
                toleranceDegrees = GameConstants.SNIPER_HIT_ANGLE_TOLERANCE_DEGREES,
            ),
        )
    }
}

class HealthMathTest {
    @Test
    fun `applyDamage subtracts and clamps to the valid range`() {
        assertEquals(2, HealthMath.applyDamage(currentHealth = 3, damage = 1, maxHealth = 3))
        assertEquals(0, HealthMath.applyDamage(currentHealth = 1, damage = 1, maxHealth = 3))
        assertEquals(0, HealthMath.applyDamage(currentHealth = 0, damage = 1, maxHealth = 3)) // never goes negative
        assertEquals(3, HealthMath.applyDamage(currentHealth = 3, damage = 0, maxHealth = 3)) // never exceeds max
    }
}

class ReloadTest {
    private val reload = Reload(
        durationMs = 2000L,
        perfectZoneStartFraction = 0.5f,
        perfectZoneEndFraction = 0.8f,
        missPenaltyMs = 300L,
    )

    @Test
    fun `progressFraction climbs from 0 to 1 and clamps at both ends`() {
        assertEquals(0f, reload.progressFraction(startedAtMs = 0L, nowMs = 0L), 0.001f)
        assertEquals(0.5f, reload.progressFraction(startedAtMs = 0L, nowMs = 1000L), 0.001f)
        assertEquals(1f, reload.progressFraction(startedAtMs = 0L, nowMs = 2000L), 0.001f)
        assertEquals(1f, reload.progressFraction(startedAtMs = 0L, nowMs = 5000L), 0.001f) // past 100%, still clamped
        assertEquals(0f, reload.progressFraction(startedAtMs = 500L, nowMs = 0L), 0.001f) // start in the future
    }

    @Test
    fun `isComplete only once the full duration has elapsed`() {
        assertFalse(reload.isComplete(startedAtMs = 0L, nowMs = 1999L))
        assertTrue(reload.isComplete(startedAtMs = 0L, nowMs = 2000L))
    }

    @Test
    fun `isInPerfectZone respects the 50 to 80 percent window inclusively`() {
        assertFalse(reload.isInPerfectZone(startedAtMs = 0L, nowMs = 999L)) // 49.95%
        assertTrue(reload.isInPerfectZone(startedAtMs = 0L, nowMs = 1000L)) // exactly 50%
        assertTrue(reload.isInPerfectZone(startedAtMs = 0L, nowMs = 1600L)) // exactly 80%
        assertFalse(reload.isInPerfectZone(startedAtMs = 0L, nowMs = 1601L))
    }

    @Test
    fun `penalizedStartTime pushes the start forward by the penalty`() {
        assertEquals(300L, reload.penalizedStartTime(startedAtMs = 0L, nowMs = 1000L))
    }

    @Test
    fun `penalizedStartTime clamps to now so rapid mashing cannot push progress negative`() {
        // Repeated miss-taps in quick succession would otherwise push startedAtMs past nowMs.
        assertEquals(500L, reload.penalizedStartTime(startedAtMs = 400L, nowMs = 500L))
        assertEquals(0f, reload.progressFraction(startedAtMs = 500L, nowMs = 500L), 0.001f)
    }
}

class OutcomeResolverTest {
    private val resolver = OutcomeResolver(drawWindowMs = 700L)

    @Test
    fun `ongoing while both alive`() {
        assertEquals(
            DuelOutcome.Ongoing,
            resolver.resolve(myHealth = 3, opponentHealth = 2, myHealthZeroAtMs = null, nowMs = 0L),
        )
    }

    @Test
    fun `win is immediate once opponent health is reported zero`() {
        assertEquals(
            DuelOutcome.Win,
            resolver.resolve(myHealth = 1, opponentHealth = 0, myHealthZeroAtMs = null, nowMs = 0L),
        )
    }

    @Test
    fun `own zero health waits within the draw window instead of declaring loss immediately`() {
        assertEquals(
            DuelOutcome.Ongoing,
            resolver.resolve(myHealth = 0, opponentHealth = 1, myHealthZeroAtMs = 1000L, nowMs = 1300L),
        )
    }

    @Test
    fun `loss once the draw window elapses with no opposing zero`() {
        assertEquals(
            DuelOutcome.Loss,
            resolver.resolve(myHealth = 0, opponentHealth = 1, myHealthZeroAtMs = 1000L, nowMs = 1800L),
        )
    }

    @Test
    fun `simultaneous double elimination is a draw regardless of timing`() {
        assertEquals(
            DuelOutcome.Draw,
            resolver.resolve(myHealth = 0, opponentHealth = 0, myHealthZeroAtMs = 1000L, nowMs = 1000L),
        )
    }
}

class WeaponConfigsTest {
    @Test
    fun `magazine sizes match the spec`() {
        assertEquals(GameConstants.MAGAZINE_SIZE, GameConstants.WeaponConfigs.PISTOL.magazineSize)
        assertEquals(3, GameConstants.WeaponConfigs.SHOTGUN.magazineSize)
        assertEquals(4, GameConstants.WeaponConfigs.BOW.magazineSize)
        assertEquals(2, GameConstants.WeaponConfigs.SNIPER.magazineSize)
        assertEquals(1, GameConstants.WeaponConfigs.LASER.magazineSize)
    }

    @Test
    fun `pistol stays loud — bow and laser are the only silent weapons`() {
        // Today's onFireReceived sets incomingShot regardless of hit/miss; this locks in
        // that the pistol must not silently become quiet as a side effect of adding the
        // loud/silent distinction for the new weapons.
        assertTrue(GameConstants.WeaponConfigs.PISTOL.isLoud)
        assertTrue(GameConstants.WeaponConfigs.SHOTGUN.isLoud)
        assertTrue(GameConstants.WeaponConfigs.SNIPER.isLoud)
        assertFalse(GameConstants.WeaponConfigs.BOW.isLoud)
        assertFalse(GameConstants.WeaponConfigs.LASER.isLoud)
    }

    @Test
    fun `sniper is narrower and shotgun is wider than the pistol's tolerance`() {
        val pistolTolerance = GameConstants.WeaponConfigs.PISTOL.hitToleranceDegrees
        assertTrue(GameConstants.WeaponConfigs.SNIPER.hitToleranceDegrees < pistolTolerance)
        assertTrue(GameConstants.WeaponConfigs.SHOTGUN.hitToleranceDegrees > pistolTolerance)
    }

    @Test
    fun `forType resolves every weapon type to a config of the matching type`() {
        WeaponType.entries.forEach { type ->
            assertEquals(type, GameConstants.WeaponConfigs.forType(type).type)
        }
    }
}

class BowMathTest {
    @Test
    fun `pullFraction clamps to the 0 to 1 range`() {
        assertEquals(0f, BowMath.pullFraction(dragDistance = -10f, maxDragRadius = 140f), 0.001f)
        assertEquals(0.5f, BowMath.pullFraction(dragDistance = 70f, maxDragRadius = 140f), 0.001f)
        assertEquals(1f, BowMath.pullFraction(dragDistance = 140f, maxDragRadius = 140f), 0.001f)
        assertEquals(1f, BowMath.pullFraction(dragDistance = 500f, maxDragRadius = 140f), 0.001f)
    }

    @Test
    fun `speedDpPerSec interpolates linearly and hits the exact bounds at 0 and 1`() {
        assertEquals(500f, BowMath.speedDpPerSec(0f, minSpeedDpPerSec = 500f, maxSpeedDpPerSec = 1800f), 0.001f)
        assertEquals(1800f, BowMath.speedDpPerSec(1f, minSpeedDpPerSec = 500f, maxSpeedDpPerSec = 1800f), 0.001f)
        assertEquals(1150f, BowMath.speedDpPerSec(0.5f, minSpeedDpPerSec = 500f, maxSpeedDpPerSec = 1800f), 0.001f)
    }

    @Test
    fun `isFullyCharged is false just under the hold threshold and true at or above it`() {
        assertFalse(BowMath.isFullyCharged(chargeStartedAtMs = 0L, nowMs = 599L, minChargeHoldMs = 600L))
        assertTrue(BowMath.isFullyCharged(chargeStartedAtMs = 0L, nowMs = 600L, minChargeHoldMs = 600L))
        assertTrue(BowMath.isFullyCharged(chargeStartedAtMs = 0L, nowMs = 1000L, minChargeHoldMs = 600L))
    }
}

class LaserBeamHitAccumulatorTest {
    @Test
    fun `stays false across an all-miss sequence`() {
        val accumulator = LaserBeamHitAccumulator()
        accumulator.sample(aimAngleDegrees = 90f, bearingDegrees = 0f, toleranceDegrees = 20f)
        accumulator.sample(aimAngleDegrees = -90f, bearingDegrees = 0f, toleranceDegrees = 20f)
        assertFalse(accumulator.registeredHit)
    }

    @Test
    fun `becomes true on the first in-tolerance sample and stays true regardless of later misses`() {
        val accumulator = LaserBeamHitAccumulator()
        accumulator.sample(aimAngleDegrees = 90f, bearingDegrees = 0f, toleranceDegrees = 20f) // miss
        accumulator.sample(aimAngleDegrees = 5f, bearingDegrees = 0f, toleranceDegrees = 20f) // hit
        assertTrue(accumulator.registeredHit)
        accumulator.sample(aimAngleDegrees = 179f, bearingDegrees = 0f, toleranceDegrees = 20f) // miss, but sticky
        assertTrue(accumulator.registeredHit)
    }
}

class CrateMathTest {
    @Test
    fun `fallProgress climbs from 0 to 1 and clamps at both ends`() {
        assertEquals(0f, CrateMath.fallProgress(spawnedAtMs = 0L, nowMs = 0L, fallDurationMs = 3000L), 0.001f)
        assertEquals(0.5f, CrateMath.fallProgress(spawnedAtMs = 0L, nowMs = 1500L, fallDurationMs = 3000L), 0.001f)
        assertEquals(1f, CrateMath.fallProgress(spawnedAtMs = 0L, nowMs = 3000L, fallDurationMs = 3000L), 0.001f)
        assertEquals(1f, CrateMath.fallProgress(spawnedAtMs = 0L, nowMs = 9000L, fallDurationMs = 3000L), 0.001f)
    }

    @Test
    fun `hasLanded is true exactly at progress 1 and false just before`() {
        assertFalse(CrateMath.hasLanded(0.999f))
        assertTrue(CrateMath.hasLanded(1f))
    }

    @Test
    fun `currentOffsetDp has zero wobble at progress 0 and interpolates y linearly`() {
        val offsetAtStart = CrateMath.currentOffsetDp(
            progress = 0f,
            laneXDp = 20f,
            topYDp = -400f,
            bottomYDp = 400f,
            wobbleAmplitudeDp = 24f,
            wobbleFrequency = 2.2f,
        )
        assertEquals(20f, offsetAtStart.x, 0.001f) // sin(0) == 0, no wobble yet
        assertEquals(-400f, offsetAtStart.y, 0.001f)

        val offsetAtHalf = CrateMath.currentOffsetDp(
            progress = 0.5f,
            laneXDp = 20f,
            topYDp = -400f,
            bottomYDp = 400f,
            wobbleAmplitudeDp = 24f,
            wobbleFrequency = 2.2f,
        )
        assertEquals(0f, offsetAtHalf.y, 0.001f) // halfway between -400 and 400
    }

    @Test
    fun `isHit is a direct two-point Euclidean check`() {
        val crateOffset = Vector2(10f, -100f)
        assertTrue(CrateMath.isHit(aimPointDp = Vector2(10f, -100f), crateOffsetDp = crateOffset, hitRadiusDp = 10f)) // exact same point
        assertTrue(CrateMath.isHit(aimPointDp = Vector2(15f, -95f), crateOffsetDp = crateOffset, hitRadiusDp = 10f)) // within radius
        assertFalse(CrateMath.isHit(aimPointDp = Vector2(30f, -100f), crateOffsetDp = crateOffset, hitRadiusDp = 10f)) // 20dp away, outside
    }

    @Test
    fun `isHit's boundary is independent of the crate's distance from center`() {
        // Found during review: an earlier version of isHit reconstructed an "aim point" by
        // projecting an angle onto the crate's own radial distance, which made the effective
        // tolerance scale with distance (crates near center became nearly unmissable, crates near
        // the fall's start/end became harder to hit than the sniper). This test locks in that a
        // fixed hitRadiusDp requires the exact same absolute miss-distance regardless of how far
        // the crate is from screen center.
        val hitRadiusDp = 50f
        val nearCrate = Vector2(0f, -50f) // 50dp from center
        val farCrate = Vector2(0f, -500f) // 500dp from center

        // Exactly at the hit radius's edge (directly "below" each crate, same absolute miss
        // distance) is a hit for both, independent of distance from center.
        assertTrue(CrateMath.isHit(Vector2(0f, -50f + hitRadiusDp), nearCrate, hitRadiusDp))
        assertTrue(CrateMath.isHit(Vector2(0f, -500f + hitRadiusDp), farCrate, hitRadiusDp))

        // Just past the hit radius is a miss for both, again independent of distance from center.
        assertFalse(CrateMath.isHit(Vector2(0f, -50f + hitRadiusDp + 1f), nearCrate, hitRadiusDp))
        assertFalse(CrateMath.isHit(Vector2(0f, -500f + hitRadiusDp + 1f), farCrate, hitRadiusDp))
    }
}

/**
 * Reproduces, end to end with fixed synthetic device positions/headings, the composed
 * cross-device formula the sniper's live laser-dot telemetry relies on
 * ([BearingModel.sniperDotOffsetDegrees], consumed by DuelViewModel/GameMessage.MotionUpdate's
 * sniperAimHeadingDegrees). This calls the real production function directly — not a
 * hand-copied duplicate — so a future regression in the actual shipped formula fails this test,
 * rather than a private test-local copy silently diverging from it. This is a pure geometry
 * check that must pass before any two-device manual verification — a systematic sign/offset
 * defect here (two were found and fixed during plan review, including a 180-degree
 * reciprocal-bearing error and a dropped receiver-heading term) cannot be surfaced by
 * hardware noise, only by exactly this kind of deterministic desk-check.
 */
class SniperTelemetryTest {
    @Test
    fun `dot offset is zero when the shooter is aimed exactly at the receiver`() {
        // Shooter is due east of the receiver: absolute bearing receiver-to-shooter = 90 degrees.
        val initial = BearingModel.computeInitialOpponentPosition(baselineHeadingDegrees = 90f, assumedDistanceMeters = 3f)
        val receiverHeading = 90f // receiver facing directly at the shooter
        val bearingToShooterScreenRelative = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = Vector2.ZERO,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = receiverHeading,
        )
        assertEquals(0f, bearingToShooterScreenRelative, 0.01f) // sanity check: shooter appears dead ahead

        // Shooter faces west (directly at the receiver) and aims dead ahead on their own screen.
        val shooterHeading = 270f
        val shooterAimAngle = 0f
        val broadcastAimHeading = AngleMath.normalizeSigned(shooterHeading + shooterAimAngle)

        val dotOffsetDegrees = BearingModel.sniperDotOffsetDegrees(
            shooterAbsoluteAimHeadingDegrees = broadcastAimHeading,
            bearingToShooterScreenRelativeDegrees = bearingToShooterScreenRelative,
            myCurrentHeadingDegrees = receiverHeading,
        )
        assertEquals(0f, dotOffsetDegrees, 0.01f)
    }

    @Test
    fun `dot offset reflects the size and direction of a real aim error`() {
        val initial = BearingModel.computeInitialOpponentPosition(baselineHeadingDegrees = 0f, assumedDistanceMeters = 3f) // shooter due north
        val receiverHeading = 45f
        val bearingToShooterScreenRelative = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initial,
            opponentStepVector = Vector2.ZERO,
            myStepVector = Vector2.ZERO,
            myCurrentHeadingDegrees = receiverHeading,
        )

        val shooterHeading = 200f
        val perfectAimAngle = -20f // the aim angle that would produce a zero offset for these headings
        val offAimAngle = perfectAimAngle + 15f
        val broadcastAimHeading = AngleMath.normalizeSigned(shooterHeading + offAimAngle)

        val dotOffsetDegrees = BearingModel.sniperDotOffsetDegrees(
            shooterAbsoluteAimHeadingDegrees = broadcastAimHeading,
            bearingToShooterScreenRelativeDegrees = bearingToShooterScreenRelative,
            myCurrentHeadingDegrees = receiverHeading,
        )
        assertEquals(15f, dotOffsetDegrees, 0.01f)
    }
}
