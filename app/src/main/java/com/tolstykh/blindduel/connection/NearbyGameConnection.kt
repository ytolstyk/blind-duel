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
import com.tolstykh.blindduel.game.GameConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Set only while hosting; lets [onConnectionInitiated] reject connection requests that
     * didn't come from a device that actually scanned our QR code, instead of auto-accepting
     * any nearby device discovering the fixed [GameConstants.NEARBY_SERVICE_ID]. */
    private var advertisedSessionCode: String? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            runCatching { bytes.toGameMessage() }
                .onSuccess { _incomingMessages.tryEmit(it) }
                .onFailure { Log.w(TAG, "Failed to decode payload from $endpointId", it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads complete in a single update; no streaming progress to track.
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val expectedCode = advertisedSessionCode
            if (expectedCode != null) {
                val incomingCode = info.endpointName.substringBefore(SESSION_CODE_DELIMITER, "")
                if (incomingCode != expectedCode) {
                    client.rejectConnection(endpointId)
                    return
                }
            }
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                opponentEndpointId = endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                _connectionState.value = ConnectionState.Connected(endpointId)
            } else {
                _connectionState.value =
                    ConnectionState.Failed(resolution.status.statusMessage ?: "Connection rejected")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == opponentEndpointId) {
                opponentEndpointId = null
                _connectionState.value = ConnectionState.Disconnected
            }
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
        _connectionState.value = ConnectionState.Connecting
        advertisedSessionCode = sessionCode
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(sessionCode, GameConstants.NEARBY_SERVICE_ID, connectionLifecycleCallback, options)
            .addOnFailureListener {
                _connectionState.value = ConnectionState.Failed(it.message ?: "Advertising failed")
            }
    }

    override suspend fun startJoining(sessionCode: String, localPlayerName: String) {
        _connectionState.value = ConnectionState.Connecting
        targetSessionCode = sessionCode
        // Echo the scanned session code back as our local endpoint name so the host can
        // verify it before accepting (see onConnectionInitiated above).
        this.localPlayerName = "$sessionCode$SESSION_CODE_DELIMITER$localPlayerName"
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startDiscovery(GameConstants.NEARBY_SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener {
                _connectionState.value = ConnectionState.Failed(it.message ?: "Discovery failed")
            }
    }

    override suspend fun sendMessage(message: GameMessage) {
        val endpointId = opponentEndpointId ?: return
        // MotionUpdate is broadcast every ~120ms for the whole match; keep JSON encoding
        // (and payload decoding, symmetrically, in onPayloadReceived) off the main thread.
        val bytes = withContext(Dispatchers.Default) { message.toBytes() }
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    override fun disconnect() {
        client.stopAllEndpoints()
        opponentEndpointId = null
        targetSessionCode = null
        advertisedSessionCode = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private companion object {
        const val TAG = "NearbyGameConnection"
        const val SESSION_CODE_DELIMITER = "::"
    }
}
