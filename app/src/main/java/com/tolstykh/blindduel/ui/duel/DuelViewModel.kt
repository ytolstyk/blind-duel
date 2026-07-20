package com.tolstykh.blindduel.ui.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.quit
import com.tolstykh.blindduel.connection.sessionEndedEvents
import com.tolstykh.blindduel.game.BearingModel
import com.tolstykh.blindduel.game.Cooldown
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.HealthMath
import com.tolstykh.blindduel.game.HitTest
import com.tolstykh.blindduel.game.OutcomeResolver
import com.tolstykh.blindduel.game.Vector2
import com.tolstykh.blindduel.sensor.Haptics
import com.tolstykh.blindduel.sensor.MotionProvider
import com.tolstykh.blindduel.sensor.isAccuracyLow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuelUiState(
    val myHealth: Int = GameConstants.MAX_PLAYER_HEALTH,
    val opponentHealth: Int = GameConstants.MAX_PLAYER_HEALTH,
    val damageFlash: Boolean = false,
    val damageDirectionDegrees: Float? = null,
    val showAccuracyWarning: Boolean = false,
    val showAlignmentWarning: Boolean = false,
    val outcome: DuelOutcome = DuelOutcome.Ongoing,
)

/** A just-fired shot, for the shooter's own on-screen projectile animation. */
data class OutgoingShot(val aimAngleDegrees: Float, val firedAtMs: Long)

@HiltViewModel
class DuelViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
    private val motionProvider: MotionProvider,
    private val haptics: Haptics,
) : ViewModel() {

    var uiState by mutableStateOf(
        DuelUiState(showAlignmentWarning = !gameSession.isAlignedAtCalibration),
    )
        private set

    // Cooldown state is intentionally kept out of DuelUiState and updated at its own cadence:
    // it changes ~20x/sec while a cooldown is active, and bundling it into the same state
    // object as health/outcome/etc. would force the whole DuelScreen to recompose on every
    // tick instead of just the CooldownRing that actually reads it.
    var cooldownSecondsRemaining by mutableStateOf(0)
        private set
    var cooldownProgress by mutableStateOf(0f)
        private set

    /** Raw drag offset from screen center, in px; magnitude is visual-only, hit-testing only uses angle. */
    var aimDrag by mutableStateOf<Offset?>(null)
        private set

    var outgoingShot by mutableStateOf<OutgoingShot?>(null)
        private set

    var sessionEnded by mutableStateOf(false)
        private set

    val isPracticeMode: Boolean = gameSession.isPracticeMode

    private val connection get() = activeGameConnection.current
    private val cooldown = Cooldown(GameConstants.FIRE_COOLDOWN_MS)
    private val outcomeResolver = OutcomeResolver(GameConstants.DRAW_WINDOW_MS)

    private val myBaselineHeadingDegrees: Float = checkNotNull(gameSession.myBaselineHeadingDegrees) {
        "DuelViewModel requires calibration to have completed first"
    }
    private val initialOpponentPosition = BearingModel.computeInitialOpponentPosition(
        baselineHeadingDegrees = myBaselineHeadingDegrees,
        assumedDistanceMeters = GameConstants.ASSUMED_INITIAL_DISTANCE_METERS,
    )

    private var myHeadingDegrees = myBaselineHeadingDegrees
    private var myStepVector = Vector2.ZERO
    private var opponentStepVector = Vector2.ZERO
    private var firedAtMs: Long? = null
    private var myHealthZeroAtMs: Long? = null

    init {
        motionProvider.headingUpdates()
            .onEach { sample ->
                myHeadingDegrees = sample.headingDegrees
                if (!uiState.showAccuracyWarning && isAccuracyLow(sample.accuracy)) {
                    uiState = uiState.copy(showAccuracyWarning = true)
                }
            }
            .launchIn(viewModelScope)

        motionProvider.stepEvents()
            .onEach { myStepVector += BearingModel.stepDelta(myHeadingDegrees, GameConstants.STEP_LENGTH_METERS) }
            .launchIn(viewModelScope)

        connection.incomingMessages
            .onEach { message -> handleIncoming(message) }
            .launchIn(viewModelScope)

        connection.sessionEndedEvents()
            .onEach { sessionEnded = true }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            connection.sendMessage(GameMessage.HealthUpdate(GameConstants.MAX_PLAYER_HEALTH))
        }

        startMotionBroadcastLoop()
        startCooldownTicker()
    }

    private fun startMotionBroadcastLoop() {
        viewModelScope.launch {
            while (true) {
                connection.sendMessage(
                    GameMessage.MotionUpdate(myHeadingDegrees, myStepVector.x, myStepVector.y),
                )
                delay(GameConstants.HEADING_BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun startCooldownTicker() {
        viewModelScope.launch {
            while (true) {
                val firedAt = firedAtMs
                if (firedAt == null) {
                    delay(IDLE_POLL_INTERVAL_MS)
                    continue
                }
                val now = System.currentTimeMillis()
                val remainingMs = cooldown.remainingMs(firedAt, now)
                cooldownSecondsRemaining = cooldown.secondsRemaining(firedAt, now)
                cooldownProgress = remainingMs.toFloat() / GameConstants.FIRE_COOLDOWN_MS
                if (remainingMs <= 0L) {
                    firedAtMs = null // cooldown finished — next loop returns to idle polling
                }
                delay(GameConstants.COOLDOWN_RING_POLL_INTERVAL_MS)
            }
        }
    }

    private fun handleIncoming(message: GameMessage) {
        when (message) {
            is GameMessage.MotionUpdate -> {
                opponentStepVector = Vector2(message.stepVectorXMeters, message.stepVectorYMeters)
            }
            is GameMessage.FireEvent -> onFireReceived(message)
            is GameMessage.HealthUpdate -> {
                val clamped = message.remainingHealth.coerceIn(0, GameConstants.MAX_PLAYER_HEALTH)
                uiState = uiState.copy(opponentHealth = clamped)
                refreshOutcome()
            }
            else -> Unit
        }
    }

    private fun onFireReceived(fireEvent: GameMessage.FireEvent) {
        if (!fireEvent.hit) return
        // Damage is always the fixed local constant, never a peer-supplied value — a
        // modified client reporting an inflated FireEvent can't heal or over-damage either side.
        val newHealth = HealthMath.applyDamage(uiState.myHealth, GameConstants.HIT_DAMAGE, GameConstants.MAX_PLAYER_HEALTH)
        if (newHealth == 0 && myHealthZeroAtMs == null) {
            myHealthZeroAtMs = System.currentTimeMillis()
        }
        // Reuse our own live bearing estimate as a proxy for "which way the hit came from" —
        // the receiver has no ground truth for the shooter's aim, only its own belief of
        // where the opponent currently is.
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        uiState = uiState.copy(myHealth = newHealth, damageFlash = true, damageDirectionDegrees = bearing)
        haptics.hitFeedback()
        viewModelScope.launch {
            connection.sendMessage(GameMessage.HealthUpdate(newHealth))
            delay(GameConstants.DAMAGE_FLASH_DURATION_MS)
            uiState = uiState.copy(damageFlash = false)
        }
        refreshOutcome()
    }

    private fun refreshOutcome() {
        val outcome = outcomeResolver.resolve(
            myHealth = uiState.myHealth,
            opponentHealth = uiState.opponentHealth,
            myHealthZeroAtMs = myHealthZeroAtMs,
            nowMs = System.currentTimeMillis(),
        )
        if (outcome == uiState.outcome) return
        uiState = uiState.copy(outcome = outcome)
        if (outcome != DuelOutcome.Ongoing) {
            gameSession.recordOutcome(outcome, uiState.myHealth, uiState.opponentHealth)
        }
    }

    fun onDismissAccuracyWarning() {
        uiState = uiState.copy(showAccuracyWarning = false)
    }

    fun onDismissAlignmentWarning() {
        uiState = uiState.copy(showAlignmentWarning = false)
    }

    fun onAimDrag(offset: Offset) {
        if (!cooldown.isReady(firedAtMs, System.currentTimeMillis())) return
        aimDrag = offset
    }

    fun onAimRelease() {
        val drag = aimDrag ?: return
        aimDrag = null
        val now = System.currentTimeMillis()
        if (!cooldown.isReady(firedAtMs, now)) return

        val aimAngle = HitTest.angleFromDrag(drag.x, drag.y)
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        val hit = HitTest.testHit(aimAngle, bearing, GameConstants.HIT_ANGLE_TOLERANCE_DEGREES)
        firedAtMs = now
        outgoingShot = OutgoingShot(aimAngle, now)
        viewModelScope.launch {
            // Flat delay standing in for projectile flight time (see GameConstants doc) —
            // the opponent doesn't learn the outcome until this "travel time" elapses.
            delay(GameConstants.PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS)
            connection.sendMessage(GameMessage.FireEvent(hit = hit))
        }
    }

    fun onProjectileAnimationFinished(firedAtMs: Long) {
        if (outgoingShot?.firedAtMs == firedAtMs) {
            outgoingShot = null
        }
    }

    /** [quit] disconnects, which flips [sessionEnded] via the [sessionEndedEvents] collector
     * in [init] — DuelScreen already treats that the same as any other lost connection. */
    fun onLeavePracticeClicked() {
        viewModelScope.launch { connection.quit() }
    }

    private companion object {
        const val IDLE_POLL_INTERVAL_MS = 250L
    }
}
