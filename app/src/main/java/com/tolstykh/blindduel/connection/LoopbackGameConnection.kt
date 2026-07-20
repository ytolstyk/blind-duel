package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Debug-only [GameConnection] that simulates an opponent locally, so the full
 * aim/fire/cooldown/health/result loop is exercisable without a second physical device.
 * Selected only via [ActiveGameConnection.selectLoopback] from the debug-gated Solo Test
 * Mode entry point; never used in release builds.
 */
@Singleton
class LoopbackGameConnection @Inject constructor() : GameConnection {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<GameMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<GameMessage> = _incomingMessages.asSharedFlow()

    private var opponentHeadingDegrees = 180f
    private var opponentStepXMeters = 0f
    private var opponentStepYMeters = 0f

    /** Only one ambient-motion loop may run at a time — each rematch/reconnect must cancel
     * the previous one, otherwise every new session stacks another infinite coroutine. */
    private var ambientMotionJob: Job? = null

    override suspend fun startHosting(sessionCode: String) = simulateConnect()

    override suspend fun startJoining(sessionCode: String, localPlayerName: String) = simulateConnect()

    private fun simulateConnect() {
        _connectionState.value = ConnectionState.Connecting
        scope.launch {
            delay(400)
            _connectionState.value = ConnectionState.Connected("loopback-opponent")
            _incomingMessages.tryEmit(GameMessage.Hello("Practice Dummy"))
            _incomingMessages.tryEmit(GameMessage.ReadyForDuel)
        }
    }

    override suspend fun sendMessage(message: GameMessage) {
        when (message) {
            is GameMessage.CalibrationComplete -> scope.launch {
                delay(200)
                _incomingMessages.tryEmit(GameMessage.CalibrationComplete(baselineHeadingDegrees = 180f))
                startAmbientOpponentMotion()
            }
            is GameMessage.FireEvent -> maybeFireBack()
            is GameMessage.RematchRequest -> scope.launch {
                delay(300)
                _incomingMessages.tryEmit(GameMessage.RematchRequest)
            }
            is GameMessage.Quit -> disconnect()
            else -> Unit
        }
    }

    private fun startAmbientOpponentMotion() {
        ambientMotionJob?.cancel()
        ambientMotionJob = scope.launch {
            while (true) {
                delay(GameConstants.HEADING_BROADCAST_INTERVAL_MS)
                opponentHeadingDegrees = (opponentHeadingDegrees + Random.nextFloat() * 4f - 2f).mod(360f)
                if (Random.nextFloat() < 0.03f) {
                    opponentStepXMeters += (Random.nextFloat() * 2f - 1f) * GameConstants.STEP_LENGTH_METERS
                    opponentStepYMeters += (Random.nextFloat() * 2f - 1f) * GameConstants.STEP_LENGTH_METERS
                }
                _incomingMessages.tryEmit(
                    GameMessage.MotionUpdate(opponentHeadingDegrees, opponentStepXMeters, opponentStepYMeters)
                )
            }
        }
    }

    private fun maybeFireBack() {
        if (Random.nextFloat() < 0.35f) {
            scope.launch {
                delay(GameConstants.PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS)
                _incomingMessages.tryEmit(GameMessage.FireEvent(hit = Random.nextFloat() < 0.5f))
            }
        }
    }

    override fun disconnect() {
        ambientMotionJob?.cancel()
        ambientMotionJob = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
