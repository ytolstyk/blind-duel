package com.tolstykh.blindduel.game

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
}

class CooldownTest {
    private val cooldown = Cooldown(durationMs = 2000L)

    @Test
    fun `not ready immediately after firing, ready after duration elapses`() {
        assertFalse(cooldown.isReady(firedAtMs = 1000L, nowMs = 1500L))
        assertTrue(cooldown.isReady(firedAtMs = 1000L, nowMs = 3000L))
        assertTrue(cooldown.isReady(firedAtMs = null, nowMs = 3000L))
    }

    @Test
    fun `secondsRemaining rounds up so the UI never shows a false zero`() {
        assertEquals(2, cooldown.secondsRemaining(firedAtMs = 0L, nowMs = 100L)) // 1900ms left -> 2
        assertEquals(1, cooldown.secondsRemaining(firedAtMs = 0L, nowMs = 1500L)) // 500ms left -> 1
        assertEquals(0, cooldown.secondsRemaining(firedAtMs = 0L, nowMs = 2000L))
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
