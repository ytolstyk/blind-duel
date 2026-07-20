package com.tolstykh.blindduel.connection

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects which [GameConnection] backs the current session: the real Nearby transport, or
 * (debug builds only) the [LoopbackGameConnection] used by Solo Test Mode. Screens depend on
 * [current] rather than injecting a concrete implementation directly.
 */
@Singleton
class ActiveGameConnection @Inject constructor(
    private val nearby: NearbyGameConnection,
    private val loopback: LoopbackGameConnection,
) {
    private var useLoopback = false

    val current: GameConnection
        get() = if (useLoopback) loopback else nearby

    fun selectNearby() {
        useLoopback = false
    }

    fun selectLoopback() {
        useLoopback = true
    }
}
