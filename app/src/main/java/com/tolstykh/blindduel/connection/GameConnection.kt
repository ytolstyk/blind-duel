package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.ConnectionQuality
import com.tolstykh.blindduel.game.ConnectionQualityMath
import com.tolstykh.blindduel.game.GameConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val opponentEndpointId: String) : ConnectionState
    /** A transport-level drop is being silently retried for up to
     * [GameConstants.RECONNECT_GRACE_PERIOD_MS] before falling back to [Disconnected] — see
     * NearbyGameConnection.runReconnectLoop. [sinceMs] is captured once, at the moment of the
     * drop, so any UI can compute elapsed time locally rather than needing separate bookkeeping. */
    data class Reconnecting(val sinceMs: Long) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * Transport-agnostic peer connection used by the duel screens. [NearbyGameConnection] is the
 * real implementation (Google Nearby Connections API, no server); [LoopbackGameConnection] is a
 * debug-only stand-in used by Solo Test Mode. All messages are delivered reliably and in order
 * up to the point of a full disconnect — see the plan's "Direction / Hit Model" step 6.
 */
interface GameConnection {
    val connectionState: Flow<ConnectionState>
    val incomingMessages: Flow<GameMessage>

    /** Advertises under [sessionCode] until a peer connects or [disconnect] is called. */
    suspend fun startHosting(sessionCode: String)

    /** Searches for and connects to a host advertising [sessionCode]. */
    suspend fun startJoining(sessionCode: String, localPlayerName: String)

    suspend fun sendMessage(message: GameMessage)

    fun disconnect()
}

/**
 * Fires once for either a real transport drop or the opponent explicitly quitting — the two
 * conditions every in-session screen (Calibration/Duel/Result) needs to react to the same way
 * (return to the main menu). Centralized here so the three screens' ViewModels don't each
 * re-derive the same two-condition check.
 */
fun GameConnection.sessionEndedEvents(): Flow<Unit> = merge(
    connectionState
        .filter { it is ConnectionState.Disconnected || it is ConnectionState.Failed }
        .map { },
    incomingMessages
        .filter { it is GameMessage.Quit }
        .map { },
)

/**
 * The standard "I'm leaving" sequence used by every screen's quit/leave action — notify the
 * peer, then tear down. Sequenced in one suspend function so [disconnect] can never race ahead
 * of the outgoing [GameMessage.Quit] send.
 */
/** [isReconnecting] mirrors `connectionState is ConnectionState.Reconnecting`; [sinceMs] carries
 * that state's timestamp through, or null when not reconnecting. */
data class ReconnectStatus(val isReconnecting: Boolean, val sinceMs: Long?)

/**
 * Derives [ReconnectStatus] from [GameConnection.connectionState] — centralized here (alongside
 * [sessionEndedEvents]/[connectionQuality]) rather than left for each screen's ViewModel to
 * re-derive the same `state is ConnectionState.Reconnecting` mapping independently, which is
 * exactly the duplication [sessionEndedEvents]'s own doc comment already warns against.
 */
fun GameConnection.reconnectStatus(): Flow<ReconnectStatus> = connectionState.map { state ->
    ReconnectStatus(
        isReconnecting = state is ConnectionState.Reconnecting,
        sinceMs = (state as? ConnectionState.Reconnecting)?.sinceMs,
    )
}

suspend fun GameConnection.quit() {
    sendMessage(GameMessage.Quit)
    disconnect()
}

private sealed interface QualitySignal {
    data class Arrival(val atMs: Long) : QualitySignal
    data class Tick(val atMs: Long) : QualitySignal
}

private data class QualityAccumulator(val windowGaps: List<Long>, val lastArrivalMs: Long?)

/**
 * Good/Fair/Poor connection quality, derived from jitter in `MotionUpdate` inter-arrival timing
 * — the only real signal available, since Nearby Connections exposes no RSSI/bandwidth API.
 * Duel-only in practice (no `MotionUpdate` traffic exists during Calibration to measure).
 *
 * Deliberately merges message arrivals with an independent periodic ticker: a flow built purely
 * from `incomingMessages` can only re-evaluate when a *new* message arrives, but a real drop
 * produces silence — no emission at all — so without the ticker the indicator would freeze at
 * its last-computed value for the whole outage instead of degrading. The ticker forces
 * re-evaluation even when nothing has arrived, so silence itself resolves to [ConnectionQuality.POOR]
 * once [GameConstants.CONNECTION_QUALITY_HARD_TIMEOUT_MS] has elapsed since the last arrival.
 *
 * An extension function (not an interface member, not a new class) so both [NearbyGameConnection]
 * and [LoopbackGameConnection] get it automatically with zero per-implementation code —
 * `LoopbackGameConnection`'s steady, jitter-free ambient `MotionUpdate` loop organically
 * classifies as [ConnectionQuality.GOOD] through the same generic math.
 */
fun GameConnection.connectionQuality(): Flow<ConnectionQuality> {
    val arrivals: Flow<QualitySignal> = incomingMessages
        .filterIsInstance<GameMessage.MotionUpdate>()
        .map { QualitySignal.Arrival(System.currentTimeMillis()) }
    val ticks: Flow<QualitySignal> = flow {
        while (true) {
            delay(GameConstants.CONNECTION_QUALITY_HARD_TIMEOUT_MS)
            emit(QualitySignal.Tick(System.currentTimeMillis()))
        }
    }
    return merge(arrivals, ticks)
        .runningFold(QualityAccumulator(windowGaps = emptyList(), lastArrivalMs = null)) { acc, signal ->
            when (signal) {
                is QualitySignal.Arrival -> {
                    val gap = acc.lastArrivalMs?.let { signal.atMs - it }
                    val newGaps = if (gap != null) {
                        (acc.windowGaps + gap).takeLast(GameConstants.CONNECTION_QUALITY_WINDOW_SIZE)
                    } else {
                        acc.windowGaps
                    }
                    QualityAccumulator(newGaps, signal.atMs)
                }
                // A tick never itself changes the accumulator — it exists purely to force the
                // downstream .map below to re-evaluate "how long has it actually been" even
                // when no new MotionUpdate has arrived.
                is QualitySignal.Tick -> acc
            }
        }
        .map { acc ->
            val silentTooLong = acc.lastArrivalMs != null &&
                System.currentTimeMillis() - acc.lastArrivalMs >= GameConstants.CONNECTION_QUALITY_HARD_TIMEOUT_MS
            if (silentTooLong) {
                ConnectionQuality.POOR
            } else {
                ConnectionQualityMath.classify(
                    interArrivalGapsMs = acc.windowGaps,
                    expectedIntervalMs = GameConstants.HEADING_BROADCAST_INTERVAL_MS,
                    goodDeviationMs = GameConstants.CONNECTION_QUALITY_GOOD_DEVIATION_MS,
                    fairDeviationMs = GameConstants.CONNECTION_QUALITY_FAIR_DEVIATION_MS,
                )
            }
        }
}
