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

    /** Atomic "abandon whatever real transport is active and switch to Solo Test Mode's
     * loopback" used by [current]'s Nearby-hosting callers to bail into practice mode. Owning
     * the disconnect-then-select-then-host sequence here (rather than each caller doing it by
     * hand against [current], which re-resolves after [selectLoopback] flips it) keeps the two
     * steps that must happen against two different underlying connections from silently
     * resolving to the same one depending on call order.
     *
     * Relies on neither [GameConnection.disconnect] nor [GameConnection.startHosting] actually
     * suspending for either implementation (true today — see both classes), so this whole
     * sequence completes within one cooperative scheduling slice with no cancellation window in
     * the middle. If either implementation ever gains a real suspension point, a caller
     * launching this from a scope that can be cancelled mid-call (e.g. a ViewModel's
     * viewModelScope) could be torn down partway through. */
    suspend fun switchToLoopback(sessionCode: String) {
        if (!useLoopback) nearby.disconnect()
        useLoopback = true
        loopback.startHosting(sessionCode)
    }
}
