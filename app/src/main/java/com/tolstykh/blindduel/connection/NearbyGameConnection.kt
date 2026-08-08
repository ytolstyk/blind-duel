package com.tolstykh.blindduel.connection

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import com.tolstykh.blindduel.game.GameConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real peer-to-peer transport: Google Nearby Connections API, no server. Exactly one
 * connection is expected (P2P_POINT_TO_POINT). The QR-scan/session-code match is treated as
 * the out-of-band trust step, so connections are auto-accepted — see the plan's
 * "Connection Flow" section.
 */
@Singleton
class NearbyGameConnection @Inject constructor(
    @ApplicationContext context: Context,
) : GameConnection {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<GameMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<GameMessage> = _incomingMessages.asSharedFlow()

    private var opponentEndpointId: String? = null
    private var targetSessionCode: String? = null
    private var localPlayerName: String = ""

    // Reconnect-on-drop grace period state. A plain lock + monotonic generation counter, not a
    // coroutine Mutex — `disconnect()` is a plain non-suspend interface method (making it
    // suspend would break ActiveGameConnection.switchToLoopback's documented non-suspension
    // invariant and CreateViewModel.onCleared's synchronous call site), so every writer of
    // `_connectionState`/`opponentEndpointId`/`pendingAttemptEpoch` goes through one of the
    // named, lock-owning mutator methods below (see "Reconnect state mutators") — never an
    // ad-hoc inline `synchronized` block. That consolidation isn't just tidiness: an earlier
    // version of this code had the failure path read "is a resume in flight" and write `Failed`
    // in two separate critical sections, leaving a window where a resume attempt could start in
    // between and a stale `Failed` would still land, defeating the whole grace period — every
    // mutator below is one atomic critical section specifically to make that class of bug
    // structurally impossible to reintroduce. Every reconnect attempt captures the epoch in
    // effect when it started; before writing any state each mutator re-checks under the lock
    // that the epoch hasn't moved on (superseded by a real reconnect or an explicit disconnect()
    // elsewhere) and no-ops if so.
    private val stateLock = Any()
    private var connectionEpoch = 0
    // The epoch in effect when the currently in-flight connection attempt (fresh or resumed)
    // began — lets onConnectionResult reject a callback for an attempt that's since been
    // superseded, on both its success AND failure branches.
    private var pendingAttemptEpoch: Int? = null
    // Set while a resume attempt (inside runReconnectLoop) is awaiting onConnectionResult;
    // completed from markConnected. Also lets markFailedUnlessResuming tell "a resume's
    // handshake was rejected" (suppress Failed, let the loop retry) apart from "a fresh
    // connection attempt failed" (report Failed as today).
    private var pendingResumeSignal: CompletableDeferred<Unit>? = null
    private val reconnectScope = CoroutineScope(Dispatchers.Default + Job())

    /** Set only while hosting; lets [onConnectionInitiated] reject connection requests that
     * didn't come from a device that actually scanned our QR code, instead of auto-accepting
     * any nearby device discovering the fixed [GameConstants.NEARBY_SERVICE_ID]. */
    private var advertisedSessionCode: String? = null

    /** Tracked explicitly (rather than inferring "not hosting" from [advertisedSessionCode] being
     * null) so [onConnectionInitiated] fails closed the instant [disconnect] is called, without
     * waiting on the async, fire-and-forget [ConnectionsClient.stopAdvertising] to actually take
     * effect. Solo Test Mode's entry point disconnects this real transport out from under a
     * still-live advertisement (see CreateViewModel.startSoloTest) — without this, that teardown
     * window would briefly auto-accept any nearby device's connection request. */
    private var role: Role = Role.IDLE

    private enum class Role { IDLE, HOSTING, JOINING }

    // --- Reconnect state mutators: every writer of connectionEpoch/pendingAttemptEpoch/
    // pendingResumeSignal/_connectionState/opponentEndpointId for the reconnect flow lives here,
    // each owning exactly one atomic synchronized(stateLock) critical section. Nothing outside
    // this block should touch those fields directly.

    private fun isRelevantAttemptLocked(): Boolean = pendingAttemptEpoch == null || pendingAttemptEpoch == connectionEpoch

    /** Atomically records the epoch in effect when a fresh (non-resume) connection attempt
     * begins, and transitions to Connecting — used by [startHosting]/[startJoining]. Folding the
     * state write into the same critical section as capturing the epoch (rather than two
     * separate statements) closes the same class of gap the rest of this mutator set exists to
     * prevent, even though the practical risk here is small (this only ever runs once, at the
     * very start of a fresh session, before any callback could plausibly fire yet). */
    private fun beginFreshAttempt(): Int = synchronized(stateLock) {
        pendingAttemptEpoch = connectionEpoch
        _connectionState.value = ConnectionState.Connecting
        connectionEpoch
    }

    /** Records [epoch] as the in-flight attempt, but only if it's still current — checked
     * *inside* this same critical section (see [markConnectedIfRelevant]'s doc for why a
     * separate pre-check isn't safe here either). Without this guard, a resume attempt already
     * abandoned (the epoch moved on via a concurrent `disconnect()` followed by a fresh
     * `startHosting`/`startJoining`) could still land this write after the fact and clobber
     * `pendingAttemptEpoch` back to the old, wrong epoch — silently stranding the *new*,
     * legitimate connection attempt's own `onConnectionResult` callback, which would then fail
     * its own relevance check and never report success. Found on a third review pass of this
     * file; every other mutator here already learned this lesson, this one hadn't yet. Returns
     * whether the write was applied. */
    private fun trySetPendingAttemptEpoch(epoch: Int): Boolean = synchronized(stateLock) {
        if (connectionEpoch != epoch) return@synchronized false
        pendingAttemptEpoch = epoch
        true
    }

    /** Records [signal] as the pending resume signal for [epoch]'s attempt, but only if [epoch]
     * is still current — checked inside the same critical section, same reasoning as
     * [trySetPendingAttemptEpoch]. Replaces what was previously a separate `isCurrentEpoch(epoch)`
     * check followed by an unconditional write in [runReconnectLoop] — the same TOCTOU shape
     * found (and fixed) everywhere else in this file, caught here proactively while auditing the
     * rest. Returns whether the write was applied. */
    private fun trySetPendingResumeSignal(epoch: Int, signal: CompletableDeferred<Unit>): Boolean = synchronized(stateLock) {
        if (connectionEpoch != epoch) return@synchronized false
        pendingResumeSignal = signal
        true
    }

    /** Clears [signal] as the pending resume signal, but only if it's still the current one — a
     * later attempt or a successful connect may have already replaced/cleared it. */
    private fun clearPendingResumeSignalIfCurrent(signal: CompletableDeferred<Unit>) {
        synchronized(stateLock) { if (pendingResumeSignal === signal) pendingResumeSignal = null }
    }

    /** Atomically transitions to Reconnecting after a drop — bumps the epoch (invalidating any
     * earlier in-flight attempt from a prior drop/reconnect cycle) and returns the new value for
     * the caller's [runReconnectLoop] invocation. */
    private fun markReconnecting(): Int = synchronized(stateLock) {
        connectionEpoch++
        opponentEndpointId = null
        _connectionState.value = ConnectionState.Reconnecting(System.currentTimeMillis())
        connectionEpoch
    }

    /**
     * Atomically transitions to Connected — but only if this callback still belongs to a
     * relevant attempt. Returns whether the transition was applied.
     *
     * **Fix (found on a follow-up architecture review): the relevance check and the write used
     * to be two separate critical sections** — `onConnectionResult` called a standalone
     * `isRelevantAttempt()` (its own `synchronized` block), then called this mutator (a second,
     * later `synchronized` block) — leaving a window where an explicit `disconnect()` landing in
     * between the two would still let this write through, resurrecting a session the user
     * already quit. Exactly the same TOCTOU shape as the bug this whole mutator design was
     * introduced to close, just one layer up. Fixed by checking relevance *inside* this same
     * critical section instead of trusting a separate pre-check — bumps the epoch (superseding
     * any other in-flight attempt), records the endpoint, and completes any pending resume
     * signal so a waiting [runReconnectLoop] can observe the real reconnection.
     */
    private fun markConnectedIfRelevant(endpointId: String): Boolean = synchronized(stateLock) {
        if (!isRelevantAttemptLocked()) return@synchronized false
        connectionEpoch++
        pendingAttemptEpoch = null
        opponentEndpointId = endpointId
        _connectionState.value = ConnectionState.Connected(endpointId)
        pendingResumeSignal?.complete(Unit)
        pendingResumeSignal = null
        true
    }

    /** Atomically reports a connection failure — but only if this callback still belongs to a
     * relevant attempt (see [markConnectedIfRelevant]'s doc for why the relevance check must be
     * inside this same critical section, not a separate pre-check), and only if no resume
     * attempt is currently in flight (`pendingResumeSignal == null`) — a non-OK result while a
     * resume is in flight must NOT write `Failed`, since that would trip `sessionEndedEvents()`
     * and end the session mid-grace-window, exactly what the grace period exists to prevent;
     * leaving the signal uncompleted lets `runReconnectLoop`'s own `withTimeoutOrNull` fall
     * through to a retry (or the deadline) on its own instead. */
    private fun markFailedIfRelevantAndNotResuming(reason: String) {
        synchronized(stateLock) {
            if (!isRelevantAttemptLocked()) return@synchronized
            if (pendingResumeSignal == null) {
                pendingAttemptEpoch = null
                _connectionState.value = ConnectionState.Failed(reason)
            }
        }
    }

    /** Atomically ends the reconnect loop for [epoch] once its grace deadline has passed, but
     * only if nothing else has already resolved this attempt (a success or a disconnect would
     * have already moved the epoch on). Also bumps the epoch and clears [pendingAttemptEpoch] —
     * mirroring [markDisconnected] — so a late `onConnectionResult` for the now-abandoned
     * attempt (advertising/discovery may still be winding down asynchronously) can't resurrect
     * `Connected` after the grace period has already given up; without this, that asymmetry
     * between this mutator and [markDisconnected] reopened the same class of stale-write bug the
     * rest of this design exists to close. */
    private fun markDisconnectedIfCurrentEpoch(epoch: Int) {
        synchronized(stateLock) {
            if (connectionEpoch == epoch) {
                connectionEpoch++
                pendingAttemptEpoch = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /** Atomically ends the session on an explicit user quit — bumps the epoch (making any
     * in-flight [runReconnectLoop] or in-flight `onConnectionResult` callback for a now-
     * abandoned attempt a no-op on its next check) and clears connection state. Stays
     * non-suspend deliberately (see this class's field-doc above [stateLock]). */
    private fun markDisconnected() {
        synchronized(stateLock) {
            connectionEpoch++
            pendingAttemptEpoch = null
            opponentEndpointId = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            runCatching { bytes.toGameMessage() }
                .onSuccess { message ->
                    if (message.hasFiniteNumericFields()) {
                        _incomingMessages.tryEmit(message)
                    } else {
                        Log.w(TAG, "Dropped payload from $endpointId with a non-finite numeric field")
                    }
                }
                .onFailure { Log.w(TAG, "Failed to decode payload from $endpointId", it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads complete in a single update; no streaming progress to track.
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            when (role) {
                Role.IDLE -> client.rejectConnection(endpointId)
                // The joiner never sets advertisedSessionCode — it's already the side that
                // supplied the code, via startJoining's echoed endpoint name.
                Role.JOINING -> client.acceptConnection(endpointId, payloadCallback)
                Role.HOSTING -> {
                    val incomingCode = info.endpointName.substringBefore(SESSION_CODE_DELIMITER, "")
                    if (incomingCode == advertisedSessionCode) {
                        client.acceptConnection(endpointId, payloadCallback)
                    } else {
                        client.rejectConnection(endpointId)
                    }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            // A callback for an attempt that's since been superseded (an explicit disconnect(),
            // or a newer attempt starting) is ignored entirely — without this, a late success
            // could resurrect a session the user already quit, and a late failure could
            // incorrectly end a session that already reconnected via a different attempt. The
            // relevance check happens INSIDE each mutator's own critical section (see their
            // docs), not as a separate pre-check here, so there's no gap for a concurrent
            // disconnect()/newer attempt to land in between.
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                if (markConnectedIfRelevant(endpointId)) {
                    client.stopAdvertising()
                    client.stopDiscovery()
                }
            } else {
                markFailedIfRelevantAndNotResuming(resolution.status.statusMessage ?: "Connection rejected")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == opponentEndpointId) {
                val myEpoch = markReconnecting()
                reconnectScope.launch { runReconnectLoop(myEpoch) }
            }
        }
    }

    /**
     * Silently retries reconnection for up to [GameConstants.RECONNECT_GRACE_PERIOD_MS] after a
     * transport-level drop, instead of ending the session immediately. Resumes advertising
     * (host) or discovery (joiner) using the role/session-code state [onDisconnected] leaves
     * untouched, so both peers naturally re-find each other without the user re-scanning
     * anything. Exits the moment [epoch] is superseded (a real reconnect landed via a different
     * path, or the user explicitly disconnected), never writing stale state.
     *
     * **Accepted, documented trade-off (found on security review, not fixed here):** before this
     * feature, any drop failed closed — [advertisedSessionCode] was only ever "live" during the
     * brief initial pairing window. Now it's re-advertised in cleartext on every drop for up to
     * the whole grace period, and [onConnectionInitiated] accepts whichever endpoint connects
     * first with a matching code prefix — there's no check that it's the *same* endpoint that
     * dropped (Nearby endpoint IDs are re-generated per advertising session, so that check isn't
     * straightforwardly available). An attacker in Bluetooth/WiFi range who already knows the
     * session code (visible in cleartext to anyone running discovery, not just a QR scanner)
     * could race to reconnect during this window and get seated as "the opponent" for the rest
     * of the match. Damage still can't be forged (fixed local `HIT_DAMAGE` + `coerceIn` holds
     * regardless of who's connected), so this is a session-takeover/DoS vector against the
     * legitimate player, not a scoring exploit. A proper fix needs a per-session reconnect
     * token exchanged once over the established connection (rather than reusing the static
     * QR-scanned code as the reconnect credential) — a wire-protocol change out of scope for
     * this pass. Consistent with this game's existing accepted 2-peer trust model (see
     * `FireEvent`'s doc in `GameMessage.kt`), and requires local RF proximity to exploit.
     */
    private suspend fun runReconnectLoop(epoch: Int) {
        val deadline = System.currentTimeMillis() + GameConstants.RECONNECT_GRACE_PERIOD_MS
        while (System.currentTimeMillis() < deadline) {
            val signal = CompletableDeferred<Unit>()
            if (!trySetPendingResumeSignal(epoch, signal)) return // superseded by a real reconnect or an explicit disconnect() elsewhere
            // registerResumeAttempt resolving true means advertising/discovery re-registered
            // successfully — NOT that the peer reconnected. Only signal.await() completing
            // (from markConnected, called by onConnectionResult's real success callback) means
            // an actual reconnection.
            val registered = runCatching { registerResumeAttempt(epoch) }.getOrDefault(false)
            val reconnected = registered &&
                withTimeoutOrNull(GameConstants.RECONNECT_RETRY_INTERVAL_MS) { signal.await() } != null
            if (reconnected) return // markConnected already wrote Connected + bumped the epoch under the lock
            clearPendingResumeSignalIfCurrent(signal)
            delay(GameConstants.RECONNECT_RETRY_INTERVAL_MS)
        }
        markDisconnectedIfCurrentEpoch(epoch)
    }

    /**
     * One resume re-registration: stop then re-start advertising/discovery for this device's
     * existing role — repeated re-invocation without an intervening stop is the "already
     * advertising/discovering" risk this design must avoid, since [runReconnectLoop] can call
     * this several times within one grace window. Resolves from the `Task`'s own
     * success/failure listeners — registration only, never the eventual peer-connection outcome
     * (that's [pendingResumeSignal], completed separately from `onConnectionResult`).
     */
    private suspend fun registerResumeAttempt(epoch: Int): Boolean {
        if (!trySetPendingAttemptEpoch(epoch)) return false // superseded since runReconnectLoop's last check
        val task: Task<Void> = when (role) {
            Role.HOSTING -> {
                client.stopAdvertising()
                val sessionCode = advertisedSessionCode ?: return false
                val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                client.startAdvertising(sessionCode, GameConstants.NEARBY_SERVICE_ID, connectionLifecycleCallback, options)
            }
            Role.JOINING -> {
                client.stopDiscovery()
                val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
                client.startDiscovery(GameConstants.NEARBY_SERVICE_ID, endpointDiscoveryCallback, options)
            }
            Role.IDLE -> return false
        }
        return suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
            task.addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName == targetSessionCode) {
                client.requestConnection(localPlayerName, endpointId, connectionLifecycleCallback)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Keep discovering; if it was our target we simply haven't connected yet.
        }
    }

    override suspend fun startHosting(sessionCode: String) {
        role = Role.HOSTING
        advertisedSessionCode = sessionCode
        beginFreshAttempt() // also transitions to Connecting, atomically with capturing the epoch
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(sessionCode, GameConstants.NEARBY_SERVICE_ID, connectionLifecycleCallback, options)
            .addOnFailureListener {
                // Routed through the same relevance-checked mutator onConnectionResult uses —
                // this listener can fire asynchronously after a disconnect() already landed
                // (e.g. the user backed out before advertising registration finished), and an
                // unguarded direct write here would silently overwrite a legitimate Disconnected
                // with a stale Failed.
                markFailedIfRelevantAndNotResuming(it.message ?: "Advertising failed")
            }
    }

    override suspend fun startJoining(sessionCode: String, localPlayerName: String) {
        role = Role.JOINING
        targetSessionCode = sessionCode
        // Echo the scanned session code back as our local endpoint name so the host can
        // verify it before accepting (see onConnectionInitiated above).
        this.localPlayerName = "$sessionCode$SESSION_CODE_DELIMITER$localPlayerName"
        beginFreshAttempt() // also transitions to Connecting, atomically with capturing the epoch
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startDiscovery(GameConstants.NEARBY_SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener {
                // See startHosting's matching listener above for why this goes through the
                // relevance-checked mutator instead of an unguarded direct write.
                markFailedIfRelevantAndNotResuming(it.message ?: "Discovery failed")
            }
    }

    // Multiple independent coroutines call sendMessage concurrently now (motion broadcast,
    // per-shot delayed sends, the laser's NonCancellable finally-block send) — the mutex
    // guarantees each message's encode+send happens atomically as a unit, so wire order
    // always matches the order sendMessage was actually called, not whichever caller's
    // off-thread JSON encode happened to finish first.
    private val sendMutex = Mutex()

    override suspend fun sendMessage(message: GameMessage) {
        val endpointId = opponentEndpointId ?: return
        sendMutex.withLock {
            // MotionUpdate is broadcast every ~120ms for the whole match; keep JSON encoding
            // (and payload decoding, symmetrically, in onPayloadReceived) off the main thread.
            val bytes = withContext(Dispatchers.Default) { message.toBytes() }
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
        }
    }

    override fun disconnect() {
        role = Role.IDLE
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        targetSessionCode = null
        advertisedSessionCode = null
        markDisconnected()
    }

    private companion object {
        const val TAG = "NearbyGameConnection"
        const val SESSION_CODE_DELIMITER = "::"
    }
}
