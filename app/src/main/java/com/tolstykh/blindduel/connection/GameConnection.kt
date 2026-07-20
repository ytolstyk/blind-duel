package com.tolstykh.blindduel.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val opponentEndpointId: String) : ConnectionState
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
