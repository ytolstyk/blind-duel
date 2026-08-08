package com.tolstykh.blindduel.ui.calibration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.quit
import com.tolstykh.blindduel.connection.reconnectStatus
import com.tolstykh.blindduel.connection.sessionEndedEvents
import com.tolstykh.blindduel.game.BearingModel
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.sensor.MotionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Explicit states for the mutual-Ready → synchronized-countdown → baseline-exchange handshake. */
sealed interface CalibrationPhase {
    data object AwaitingBothReady : CalibrationPhase
    data class CountingDown(val secondsRemaining: Int) : CalibrationPhase
    data object AwaitingOpponentBaseline : CalibrationPhase
    data object Ready : CalibrationPhase
}

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
    private val motionProvider: MotionProvider,
) : ViewModel() {

    val opponentName: String get() = gameSession.opponentName

    var phase by mutableStateOf<CalibrationPhase>(CalibrationPhase.AwaitingBothReady)
        private set

    /** Whether *I've* tapped Ready — a per-peer signal, not itself a phase (see [CalibrationPhase]). */
    var sentReady by mutableStateOf(false)
        private set

    var sessionEnded by mutableStateOf(false)
        private set

    /** True while a transient Nearby Connections drop is being silently retried (see
     * ConnectionState.Reconnecting) — drives ReconnectingOverlay. */
    var isReconnecting by mutableStateOf(false)
        private set
    var reconnectingSinceMs by mutableStateOf<Long?>(null)
        private set

    private val connection get() = activeGameConnection.current
    private var latestHeadingDegrees = 0f
    private var receivedReady = false
    private var myBaselineHeadingDegrees: Float? = null
    private var receivedOpponentBaseline: Float? = null

    /** Bounded timeouts recovering from a one-shot handshake message (ReadyForDuel/
     * CalibrationComplete) lost during a reconnect gap — the reconnect feature (see
     * NearbyGameConnection) silently repairs the transport but never redelivers a lost one-shot
     * message, which would otherwise leave this handshake hung forever with no error surfaced.
     * Both jobs use the same [GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS] duration — the
     * point of splitting them into two independently-scoped jobs (not one shared watchdog) isn't
     * a different timeout *value* per phase, it's a different *start point*:
     * [awaitingBothReadyTimeoutJob] starts at `init` and is cancelled the instant the countdown
     * actually begins, so a human taking their time physically getting into position and tapping
     * Ready (routinely tens of seconds, no connection problem involved) never eats into the
     * budget meant to detect a lost message during the automated phase that follows. A single
     * shared timer spanning both phases would wrongly kick out players who simply hadn't
     * finished setting up yet. On expiry,
     * either job calls the existing connection.quit() extension rather than introducing a new
     * UI state — this makes the failure symmetric for free (the peer's own sessionEndedEvents()
     * fires from the received Quit message, exactly like the local device's own disconnect())
     * and reuses the same terminal path every other failure in this app already goes through,
     * instead of a second, inconsistent "handshake failed" pattern. */
    private var awaitingBothReadyTimeoutJob: Job? = null
    private var awaitingOpponentBaselineTimeoutJob: Job? = null

    init {
        motionProvider.headingUpdates()
            .onEach { sample -> latestHeadingDegrees = sample.headingDegrees }
            .launchIn(viewModelScope)

        connection.incomingMessages
            .onEach { message ->
                when (message) {
                    is GameMessage.ReadyForDuel -> {
                        receivedReady = true
                        tryStartCountdown()
                    }
                    is GameMessage.CalibrationComplete -> {
                        receivedOpponentBaseline = message.baselineHeadingDegrees
                        tryFinishCalibration()
                    }
                    else -> Unit
                }
            }
            .launchIn(viewModelScope)

        connection.reconnectStatus()
            .onEach { status ->
                isReconnecting = status.isReconnecting
                reconnectingSinceMs = status.sinceMs
            }
            .launchIn(viewModelScope)

        connection.sessionEndedEvents()
            .onEach { sessionEnded = true }
            .launchIn(viewModelScope)

        awaitingBothReadyTimeoutJob = launchHandshakeTimeout()
    }

    /** Both handshake-timeout jobs (see the field doc above) are structurally identical —
     * `delay(...); connection.quit()` — differing only in *when* each is launched/cancelled, not
     * in what they do; a shared helper keeps that single body from being copy-pasted twice. */
    private fun launchHandshakeTimeout(): Job = viewModelScope.launch {
        delay(GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS)
        connection.quit()
    }

    fun onReadyClicked() {
        if (sentReady) return
        sentReady = true
        viewModelScope.launch { connection.sendMessage(GameMessage.ReadyForDuel) }
        tryStartCountdown()
    }

    private fun tryStartCountdown() {
        if (phase != CalibrationPhase.AwaitingBothReady || !sentReady || !receivedReady) return
        awaitingBothReadyTimeoutJob?.cancel()
        viewModelScope.launch {
            for (second in GameConstants.CALIBRATION_COUNTDOWN_SECONDS downTo 1) {
                phase = CalibrationPhase.CountingDown(second)
                delay(GameConstants.CALIBRATION_COUNTDOWN_TICK_MS)
            }
            phase = CalibrationPhase.CountingDown(0)
            val baseline = latestHeadingDegrees
            myBaselineHeadingDegrees = baseline
            connection.sendMessage(GameMessage.CalibrationComplete(baseline))
            phase = CalibrationPhase.AwaitingOpponentBaseline
            awaitingOpponentBaselineTimeoutJob = launchHandshakeTimeout()
            tryFinishCalibration()
        }
    }

    private fun tryFinishCalibration() {
        if (phase != CalibrationPhase.AwaitingOpponentBaseline) return
        val myBaseline = myBaselineHeadingDegrees ?: return
        val opponentBaseline = receivedOpponentBaseline ?: return
        awaitingOpponentBaselineTimeoutJob?.cancel()
        val aligned = BearingModel.isAlignedFaceToFace(
            myBaselineHeadingDegrees = myBaseline,
            opponentBaselineHeadingDegrees = opponentBaseline,
            toleranceDegrees = GameConstants.FACE_TO_FACE_TOLERANCE_DEGREES,
        )
        gameSession.recordCalibration(myBaseline, opponentBaseline, aligned)
        gameSession.resetForNewDuel()
        phase = CalibrationPhase.Ready
    }
}
