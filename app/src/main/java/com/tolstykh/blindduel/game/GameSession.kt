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

    /** Solo Test-only config, set atomically from the Create screen's debug-only options — never
     * two independently-settable fields that could be paired inconsistently. Null means "this is
     * (or will become) a real match": [isPracticeMode]/[practiceStartingWeapon]/
     * [cratesEnabledForDuel] all derive from this single value, so a real match can never
     * observe a stale practice setting regardless of what a previous Solo Test session left here. */
    private var practiceSettings: PracticeSettings? = null

    val isPracticeMode: Boolean get() = practiceSettings != null
    val practiceStartingWeapon: WeaponType get() = practiceSettings?.startingWeapon ?: WeaponType.PISTOL
    val cratesEnabledForDuel: Boolean get() = practiceSettings?.cratesEnabled ?: true

    /** The match's max health — host-chosen (see CreateScreen's match-length picker),
     * adopted by the joiner via GameMessage.Hello.maxHealth. Not reset by [resetForNewDuel]
     * (a rematch keeps the host's original pick), but is reset by [enterPracticeMode] so a
     * previously-chosen real-match value can never leak into a later Solo Test session. */
    var matchMaxHealth: Int = GameConstants.MAX_PLAYER_HEALTH
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

    /** Called from Solo Test's entry point with the Create screen's chosen options, in one atomic
     * step — see [practiceSettings]'s doc for why this replaced two separately-mutated fields.
     * Also resets [matchMaxHealth] to the constant default: a real match's chosen match length
     * lives outside [practiceSettings] (it's meaningful to Solo Test too, via
     * [LoopbackGameConnection]), so it needs its own explicit reset here rather than inheriting
     * [practiceSettings]'s enter/exit hygiene for free. */
    fun enterPracticeMode(startingWeapon: WeaponType, cratesEnabled: Boolean) {
        practiceSettings = PracticeSettings(startingWeapon, cratesEnabled)
        matchMaxHealth = GameConstants.MAX_PLAYER_HEALTH
    }

    /** Called from the real Create/Join entry points, so a Solo Test session's leftover
     * settings can never be observed by a subsequent real match. */
    fun exitPracticeMode() {
        practiceSettings = null
    }

    /** Clamped to the valid [MatchLength] range here, not just at JoinViewModel's one current
     * call site — a defense-in-depth floor so a future caller forwarding an unclamped
     * peer-supplied value can't reopen the crash class this field's own doc warns about
     * (negative/zero flowing into DotsRow.repeat or HealthMath.applyDamage's coerceIn). */
    fun recordMatchMaxHealth(value: Int) {
        matchMaxHealth = value.coerceIn(
            MatchLength.entries.minOf { it.maxHealth },
            MatchLength.entries.maxOf { it.maxHealth },
        )
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
        // Uses matchMaxHealth (not the raw constant) so a rematch keeps the host's originally
        // chosen match length instead of silently reverting to the default.
        finalMyHealth = matchMaxHealth
        finalOpponentHealth = matchMaxHealth
    }
}

private data class PracticeSettings(val startingWeapon: WeaponType, val cratesEnabled: Boolean)
