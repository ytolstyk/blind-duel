package com.tolstykh.blindduel.game

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handoff state that needs to outlive a single screen's ViewModel. [GameConnection]'s
 * incoming-message flow has no replay, so facts established on one screen (the opponent's
 * name, each side's calibration baseline) would otherwise be lost to a later screen's
 * ViewModel that starts collecting after the message already went by.
 *
 * All fields are written only through the methods below, and the calibration baselines are
 * nullable so "not yet calibrated" is representable and checkable — a screen that reads them
 * before calibration completes fails loudly ([kotlin.checkNotNull]) instead of silently
 * computing bearings from a fake 0° default.
 */
@Singleton
class GameSession @Inject constructor() {
    var localPlayerName: String = "Player"
        private set
    var opponentName: String = "Opponent"
        private set

    var myBaselineHeadingDegrees: Float? = null
        private set
    var opponentBaselineHeadingDegrees: Float? = null
        private set
    var isAlignedAtCalibration: Boolean = true
        private set

    var finalOutcome: DuelOutcome = DuelOutcome.Ongoing
        private set
    var finalMyHealth: Int = GameConstants.MAX_PLAYER_HEALTH
        private set
    var finalOpponentHealth: Int = GameConstants.MAX_PLAYER_HEALTH
        private set

    fun recordLocalPlayerName(name: String) {
        localPlayerName = name.ifBlank { "Player" }
    }

    fun recordOpponentName(name: String) {
        opponentName = name
    }

    fun recordCalibration(myBaseline: Float, opponentBaseline: Float, aligned: Boolean) {
        myBaselineHeadingDegrees = myBaseline
        opponentBaselineHeadingDegrees = opponentBaseline
        isAlignedAtCalibration = aligned
    }

    fun recordOutcome(outcome: DuelOutcome, myHealth: Int, opponentHealth: Int) {
        finalOutcome = outcome
        finalMyHealth = myHealth
        finalOpponentHealth = opponentHealth
    }

    fun resetForNewDuel() {
        finalOutcome = DuelOutcome.Ongoing
        finalMyHealth = GameConstants.MAX_PLAYER_HEALTH
        finalOpponentHealth = GameConstants.MAX_PLAYER_HEALTH
    }
}
