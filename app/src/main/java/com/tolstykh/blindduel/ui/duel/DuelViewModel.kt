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
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.HealthMath
import com.tolstykh.blindduel.game.HitTest
import com.tolstykh.blindduel.game.OutcomeResolver
import com.tolstykh.blindduel.game.Reload
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
    // Changes on every fired shot (not just reload start/end), same cadence as outgoingShot —
    // safe to read directly in DuelScreen's body only because it's always written in the same
    // synchronous call as outgoingShot, so they already coalesce into one recompose per shot. If
    // outgoingShot's write is ever decoupled from firing (e.g. moved behind a delay), this field
    // would become its own full-DuelScreen-recompose trigger and should move to an isolated
    // composable the way reloadProgress already is.
    val roundsRemaining: Int = GameConstants.MAGAZINE_SIZE,
) {
    // Derived, not stored: startReload()/completeReload() always empty/refill roundsRemaining
    // in the same call that starts/ends a reload, so this can never desync from it. Flips at
    // most twice per magazine cycle — well under the ~5Hz recomposition-isolation threshold that
    // applies to reloadProgress below.
    val isReloading: Boolean get() = roundsRemaining <= 0
}

/** A just-fired shot, for the shooter's own on-screen projectile animation. [wasHit] is already
 * known locally at fire time (the shooter decides hit/miss unilaterally). This slot holds at
 * most one shot — with no per-shot cooldown, a fast rapid-fire burst can overwrite it before its
 * own animation finishes, which is why the "HIT" indicator (see [DuelViewModel.hitConfirmedAtMs])
 * is scheduled independently from fire time rather than from this animation completing, so a
 * confirmed hit is never silently dropped just because a later shot in the same burst replaced
 * this slot first. */
data class OutgoingShot(val aimAngleDegrees: Float, val firedAtMs: Long, val wasHit: Boolean)

/** An opponent's shot arriving locally, for the receiver's incoming-projectile animation.
 * [bearingDegrees] is the receiver's own live bearing estimate to the opponent — there is no
 * ground truth for the shooter's actual aim, only where we currently believe they are. */
data class IncomingShot(val bearingDegrees: Float, val hit: Boolean, val receivedAtMs: Long)

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

    // Kept out of DuelUiState and updated at its own cadence: reloadProgress changes ~20x/sec
    // while a reload is active, and bundling it into the same state object as health/outcome/etc.
    // would force the whole DuelScreen to recompose on every tick instead of just the ReloadRing
    // that actually reads it.
    var reloadProgress by mutableStateOf(0f)
        private set
    var isInPerfectReloadZone by mutableStateOf(false)
        private set

    /** Trigger timestamp for the reload-complete animation — same pattern as [lastHitAtMs]. */
    var reloadCompletedAtMs by mutableStateOf<Long?>(null)
        private set

    /** Trigger timestamp for the reload ring's miss-tap red flash — same pattern as [lastHitAtMs]. */
    var reloadMissTapAtMs by mutableStateOf<Long?>(null)
        private set

    /** Raw drag offset from screen center, in px; magnitude is visual-only, hit-testing only uses angle. */
    var aimDrag by mutableStateOf<Offset?>(null)
        private set

    var outgoingShot by mutableStateOf<OutgoingShot?>(null)
        private set

    /** Trigger timestamp for the "HIT" confirmation indicator — scheduled directly from fire
     * time (see [onAimRelease]) rather than from [outgoingShot]'s own animation completing, since
     * a rapid-fire burst (no per-shot cooldown gates this anymore) can overwrite [outgoingShot]
     * before an earlier shot's animation finishes; keying off an independent trigger timestamp
     * means every landed hit gets its own confirmation window regardless of later shots. */
    var hitConfirmedAtMs by mutableStateOf<Long?>(null)
        private set

    var incomingShot by mutableStateOf<IncomingShot?>(null)
        private set

    /** Timestamp of the most recent landed hit against me — a trigger key for the duel
     * screen's camera-shake effect, distinct from [outgoingShot] which triggers its own. */
    var lastHitAtMs by mutableStateOf<Long?>(null)
        private set

    /** Ambient decorative accelerometer reading (m/s^2, x/y) — feeds the particle backdrop's
     * swirl/parallax only, never gameplay. */
    var deviceTilt by mutableStateOf(Offset.Zero)
        private set

    var sessionEnded by mutableStateOf(false)
        private set

    val isPracticeMode: Boolean = gameSession.isPracticeMode

    private val connection get() = activeGameConnection.current
    private val outcomeResolver = OutcomeResolver(GameConstants.DRAW_WINDOW_MS)
    private val reload = Reload(
        durationMs = GameConstants.RELOAD_DURATION_MS,
        perfectZoneStartFraction = GameConstants.RELOAD_PERFECT_ZONE_START_FRACTION,
        perfectZoneEndFraction = GameConstants.RELOAD_PERFECT_ZONE_END_FRACTION,
        missPenaltyMs = GameConstants.RELOAD_MISS_PENALTY_MS,
    )

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
    private var myHealthZeroAtMs: Long? = null
    private var reloadStartedAtMs: Long? = null

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

        motionProvider.tiltUpdates()
            .onEach { sample -> deviceTilt = Offset(sample.xAxis, sample.yAxis) }
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
        startReloadTicker()
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

    private fun startReloadTicker() {
        viewModelScope.launch {
            while (true) {
                val startedAt = reloadStartedAtMs
                if (startedAt == null) {
                    delay(GameConstants.RELOAD_TICKER_IDLE_POLL_INTERVAL_MS)
                    continue
                }
                val now = System.currentTimeMillis()
                reloadProgress = reload.progressFraction(startedAt, now)
                isInPerfectReloadZone = reload.isInPerfectZone(startedAt, now)
                if (reload.isComplete(startedAt, now)) completeReload()
                delay(GameConstants.RELOAD_RING_POLL_INTERVAL_MS)
            }
        }
    }

    /** Starts a reload, discarding any rounds still in the magazine — shared by the automatic
     * (magazine emptied by firing) and voluntary ([onManualReloadRequested]) trigger paths. */
    private fun startReload(now: Long) {
        reloadStartedAtMs = now
        uiState = uiState.copy(roundsRemaining = 0)
    }

    private fun completeReload() {
        if (reloadStartedAtMs == null) return
        reloadStartedAtMs = null
        reloadProgress = 0f
        isInPerfectReloadZone = false
        uiState = uiState.copy(roundsRemaining = GameConstants.MAGAZINE_SIZE)
        reloadCompletedAtMs = System.currentTimeMillis()
    }

    /** Voluntary early reload — lets the player top off before the magazine empties, forfeiting
     * whatever rounds remain (same discard-on-reload convention as the automatic trigger). */
    fun onManualReloadRequested() {
        if (reloadStartedAtMs != null) return
        if (uiState.roundsRemaining >= GameConstants.MAGAZINE_SIZE) return
        startReload(System.currentTimeMillis())
    }

    /** A tap on the reload ring: completes the reload instantly if the dial is in the perfect
     * zone, otherwise extends the remaining reload as a mistiming penalty. */
    fun onReloadRingTapped() {
        val startedAt = reloadStartedAtMs ?: return
        val now = System.currentTimeMillis()
        if (reload.isInPerfectZone(startedAt, now)) {
            completeReload()
        } else {
            reloadStartedAtMs = reload.penalizedStartTime(startedAt, now)
            reloadMissTapAtMs = now
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
        // Reuse our own live bearing estimate as a proxy for "which way the shot came from" —
        // the receiver has no ground truth for the shooter's aim, only its own belief of
        // where the opponent currently is. Used for the incoming-projectile visual regardless
        // of hit/miss, and (on a hit) doubles as the damage-glow direction.
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        incomingShot = IncomingShot(bearing, fireEvent.hit, System.currentTimeMillis())
        if (!fireEvent.hit) return

        // Damage is always the fixed local constant, never a peer-supplied value — a
        // modified client reporting an inflated FireEvent can't heal or over-damage either side.
        val newHealth = HealthMath.applyDamage(uiState.myHealth, GameConstants.HIT_DAMAGE, GameConstants.MAX_PLAYER_HEALTH)
        if (newHealth == 0 && myHealthZeroAtMs == null) {
            myHealthZeroAtMs = System.currentTimeMillis()
        }
        uiState = uiState.copy(myHealth = newHealth)
        viewModelScope.launch { connection.sendMessage(GameMessage.HealthUpdate(newHealth)) }
        refreshOutcome()

        viewModelScope.launch {
            // Delay the cosmetic flash/shake/haptic until the incoming projectile visually
            // reaches the character, instead of firing them the instant the network message
            // lands — health/outcome resolution above stays immediate and unaffected.
            delay(GameConstants.PROJECTILE_TRAVEL_DURATION_MS)
            lastHitAtMs = System.currentTimeMillis()
            haptics.hitFeedback()
            uiState = uiState.copy(damageFlash = true, damageDirectionDegrees = bearing)
            delay(GameConstants.DAMAGE_FLASH_DURATION_MS)
            uiState = uiState.copy(damageFlash = false)
        }
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

    // No per-shot cooldown — the reload (see startReload above) is now the only pacing break
    // between shots, so firing is gated solely on not currently being in one.
    private fun canFire(): Boolean = !uiState.isReloading

    fun onAimDrag(offset: Offset) {
        if (!canFire()) return
        aimDrag = offset
    }

    fun onAimRelease() {
        val drag = aimDrag ?: return
        aimDrag = null
        if (!canFire()) return
        val now = System.currentTimeMillis()

        val aimAngle = HitTest.angleFromDrag(drag.x, drag.y)
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        val hit = HitTest.testHit(aimAngle, bearing, GameConstants.HIT_ANGLE_TOLERANCE_DEGREES)
        outgoingShot = OutgoingShot(aimAngle, now, hit)

        val roundsAfterFiring = uiState.roundsRemaining - 1
        uiState = uiState.copy(roundsRemaining = roundsAfterFiring)
        if (roundsAfterFiring <= 0) startReload(now)

        viewModelScope.launch {
            // Flat delay standing in for projectile flight time (see GameConstants doc) —
            // the opponent doesn't learn the outcome until this "travel time" elapses.
            delay(GameConstants.PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS)
            connection.sendMessage(GameMessage.FireEvent(hit = hit))
        }

        if (hit) {
            viewModelScope.launch {
                // Scheduled from fire time, independent of outgoingShot's own animation slot —
                // see hitConfirmedAtMs's doc for why (a rapid-fire burst can overwrite that slot).
                delay(GameConstants.PROJECTILE_TRAVEL_DURATION_MS)
                hitConfirmedAtMs = System.currentTimeMillis()
            }
        }
    }

    fun onProjectileAnimationFinished(firedAtMs: Long) {
        val shot = outgoingShot ?: return
        if (shot.firedAtMs != firedAtMs) return
        outgoingShot = null
    }

    fun onIncomingProjectileAnimationFinished(receivedAtMs: Long) {
        if (incomingShot?.receivedAtMs == receivedAtMs) {
            incomingShot = null
        }
    }

    /** [quit] disconnects, which flips [sessionEnded] via the [sessionEndedEvents] collector
     * in [init] — DuelScreen already treats that the same as any other lost connection. */
    fun onLeavePracticeClicked() {
        viewModelScope.launch { connection.quit() }
    }
}
