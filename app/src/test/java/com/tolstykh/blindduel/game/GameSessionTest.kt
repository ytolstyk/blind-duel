package com.tolstykh.blindduel.game

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSessionTest {
    @Test
    fun `recordMatchMaxHealth mutates matchMaxHealth`() {
        val session = GameSession()
        session.recordMatchMaxHealth(GameConstants.MATCH_LENGTH_MARATHON_HEALTH)
        assertEquals(GameConstants.MATCH_LENGTH_MARATHON_HEALTH, session.matchMaxHealth)
    }

    @Test
    fun `resetForNewDuel resets final health to the current matchMaxHealth, not the constant`() {
        val session = GameSession()
        session.recordMatchMaxHealth(GameConstants.MATCH_LENGTH_MARATHON_HEALTH)
        session.recordOutcome(DuelOutcome.Win, myHealth = 1, opponentHealth = 0)

        session.resetForNewDuel()

        assertEquals(GameConstants.MATCH_LENGTH_MARATHON_HEALTH, session.finalMyHealth)
        assertEquals(GameConstants.MATCH_LENGTH_MARATHON_HEALTH, session.finalOpponentHealth)
    }

    @Test
    fun `enterPracticeMode resets matchMaxHealth back to the constant default`() {
        val session = GameSession()
        session.recordMatchMaxHealth(GameConstants.MATCH_LENGTH_MARATHON_HEALTH)

        session.enterPracticeMode(startingWeapon = WeaponType.PISTOL, cratesEnabled = true)

        assertEquals(GameConstants.MAX_PLAYER_HEALTH, session.matchMaxHealth)
    }

    @Test
    fun `recordMatchMaxHealth clamps an out-of-range value instead of storing it raw`() {
        // Defense-in-depth: this must hold even though JoinViewModel's own call site no longer
        // pre-clamps, since a modified/buggy peer's Hello.maxHealth flows straight into this
        // setter. An unclamped negative/zero value here would otherwise crash downstream
        // (DotsRow.repeat(totalCount), HealthMath.applyDamage's coerceIn(0, maxHealth)).
        val session = GameSession()

        session.recordMatchMaxHealth(-5)
        assertEquals(GameConstants.MATCH_LENGTH_QUICK_HEALTH, session.matchMaxHealth)

        session.recordMatchMaxHealth(999)
        assertEquals(GameConstants.MATCH_LENGTH_MARATHON_HEALTH, session.matchMaxHealth)
    }
}
