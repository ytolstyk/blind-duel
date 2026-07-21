package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.HealthMath
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
    private var opponentHealth = GameConstants.MAX_PLAYER_HEALTH

    /** Only one ambient-motion loop may run at a time — each rematch/reconnect must cancel
     * the previous one, otherwise every new session stacks another infinite coroutine. */
    private var ambientMotionJob: Job? = null

    /** Same one-at-a-time rule as [ambientMotionJob]. Periodically simulates an unprompted
     * opponent shot so the incoming-projectile/hit-flash visuals are previewable in Solo Test
     * Mode without waiting on [maybeFireBack]'s reactive chance. */
    private var ambientFireJob: Job? = null

    override suspend fun startHosting(sessionCode: String) = simulateConnect()

    override suspend fun startJoining(sessionCode: String, localPlayerName: String) = simulateConnect()

    private fun simulateConnect() {
        resetOpponentHealth()
        _connectionState.value = ConnectionState.Connecting
        scope.launch {
            delay(CONNECT_SIMULATION_DELAY_MS)
            _connectionState.value = ConnectionState.Connected("loopback-opponent")
            _incomingMessages.tryEmit(GameMessage.Hello("Practice Dummy"))
            _incomingMessages.tryEmit(GameMessage.ReadyForDuel)
        }
    }

    override suspend fun sendMessage(message: GameMessage) {
        when (message) {
            is GameMessage.CalibrationComplete -> {
                // Fires exactly once per duel, including rematches — the only reliable
                // "a new duel is starting" signal available on this connection, so it also
                // doubles as the reset point for the dummy's simulated health.
                resetOpponentHealth()
                scope.launch {
                    delay(CALIBRATION_ECHO_DELAY_MS)
                    _incomingMessages.tryEmit(GameMessage.CalibrationComplete(baselineHeadingDegrees = 180f))
                    startAmbientOpponentMotion()
                }
            }
            is GameMessage.FireEvent -> {
                if (message.hit) applyHitToOpponent()
                maybeFireBack()
            }
            is GameMessage.RematchRequest -> scope.launch {
                delay(REMATCH_ECHO_DELAY_MS)
                _incomingMessages.tryEmit(GameMessage.RematchRequest)
            }
            is GameMessage.Quit -> disconnect()
            else -> Unit
        }
    }

    private fun resetOpponentHealth() {
        opponentHealth = GameConstants.MAX_PLAYER_HEALTH
    }

    private fun startAmbientOpponentMotion() {
        ambientMotionJob?.cancel()
        ambientMotionJob = scope.launch {
            while (true) {
                delay(GameConstants.HEADING_BROADCAST_INTERVAL_MS)
                opponentHeadingDegrees = (
                    opponentHeadingDegrees +
                        Random.nextFloat() * GameConstants.PRACTICE_HEADING_JITTER_DEGREES -
                        GameConstants.PRACTICE_HEADING_JITTER_DEGREES / 2f
                    ).mod(360f)
                if (Random.nextFloat() < GameConstants.PRACTICE_STEP_TRIGGER_PROBABILITY) {
                    opponentStepXMeters += (Random.nextFloat() * 2f - 1f) * GameConstants.STEP_LENGTH_METERS
                    opponentStepYMeters += (Random.nextFloat() * 2f - 1f) * GameConstants.STEP_LENGTH_METERS
                }
                _incomingMessages.tryEmit(
                    GameMessage.MotionUpdate(opponentHeadingDegrees, opponentStepXMeters, opponentStepYMeters)
                )
            }
        }
        startAmbientFireLoop()
    }

    private fun startAmbientFireLoop() {
        ambientFireJob?.cancel()
        ambientFireJob = scope.launch {
            while (true) {
                delay(
                    Random.nextLong(
                        GameConstants.PRACTICE_AMBIENT_FIRE_MIN_INTERVAL_MS,
                        GameConstants.PRACTICE_AMBIENT_FIRE_MAX_INTERVAL_MS,
                    )
                )
                _incomingMessages.tryEmit(
                    GameMessage.FireEvent(hit = Random.nextFloat() < GameConstants.PRACTICE_AMBIENT_FIRE_HIT_PROBABILITY)
                )
            }
        }
    }

    // The real transport applies this on the receiving peer's own device; here the peer is
    // simulated, so we apply it directly and echo back the resulting health like a real
    // opponent's own HealthUpdate broadcast would.
    private fun applyHitToOpponent() {
        opponentHealth = HealthMath.applyDamage(opponentHealth, GameConstants.HIT_DAMAGE, GameConstants.MAX_PLAYER_HEALTH)
        scope.launch {
            delay(SIMULATED_HEALTH_UPDATE_DELAY_MS)
            _incomingMessages.tryEmit(GameMessage.HealthUpdate(opponentHealth))
        }
    }

    private fun maybeFireBack() {
        if (Random.nextFloat() < GameConstants.PRACTICE_FIRE_BACK_PROBABILITY) {
            scope.launch {
                delay(GameConstants.PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS)
                _incomingMessages.tryEmit(
                    GameMessage.FireEvent(hit = Random.nextFloat() < GameConstants.PRACTICE_FIRE_BACK_HIT_PROBABILITY)
                )
            }
        }
    }

    override fun disconnect() {
        ambientMotionJob?.cancel()
        ambientMotionJob = null
        ambientFireJob?.cancel()
        ambientFireJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private companion object {
        const val CONNECT_SIMULATION_DELAY_MS = 400L
        const val CALIBRATION_ECHO_DELAY_MS = 200L
        const val REMATCH_ECHO_DELAY_MS = 300L
        const val SIMULATED_HEALTH_UPDATE_DELAY_MS = 100L
    }
}
