package com.tolstykh.blindduel.ui.calibration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.sessionEndedEvents
import com.tolstykh.blindduel.game.BearingModel
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.sensor.MotionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
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

    private val connection get() = activeGameConnection.current
    private var latestHeadingDegrees = 0f
    private var receivedReady = false
    private var myBaselineHeadingDegrees: Float? = null
    private var receivedOpponentBaseline: Float? = null

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

        connection.sessionEndedEvents()
            .onEach { sessionEnded = true }
            .launchIn(viewModelScope)
    }

    fun onReadyClicked() {
        if (sentReady) return
        sentReady = true
        viewModelScope.launch { connection.sendMessage(GameMessage.ReadyForDuel) }
        tryStartCountdown()
    }

    private fun tryStartCountdown() {
        if (phase != CalibrationPhase.AwaitingBothReady || !sentReady || !receivedReady) return
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
            tryFinishCalibration()
        }
    }

    private fun tryFinishCalibration() {
        if (phase != CalibrationPhase.AwaitingOpponentBaseline) return
        val myBaseline = myBaselineHeadingDegrees ?: return
        val opponentBaseline = receivedOpponentBaseline ?: return
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
