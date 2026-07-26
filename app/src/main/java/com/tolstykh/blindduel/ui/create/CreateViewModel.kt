package com.tolstykh.blindduel.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.ConnectionState
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.SessionCode
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.MatchLength
import com.tolstykh.blindduel.game.WeaponType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
) : ViewModel() {

    val sessionCode: String = SessionCode.generate()
    val qrPayload: String = SessionCode.encodeQrPayload(sessionCode, gameSession.localPlayerName)

    var isConnected by mutableStateOf(false)
        private set

    /** Host-only pre-duel choice (see CreateScreen's non-debug match-length picker) — the
     * joiner always adopts whatever the host picked, via GameMessage.Hello, so this state is
     * meaningless/unused on the Join path. Not persisted, reset each process start. Chosen here
     * (rather than the main menu) so it stays available to change while waiting for a peer to
     * scan the QR code — there's no rush before the connection completes and the Hello is sent.
     * Mirrored into [GameSession.matchMaxHealth] on every change (see [onMatchLengthSelected])
     * rather than committed once, since that's the single value actually read when Hello is sent. */
    var matchLength by mutableStateOf(MatchLength.STANDARD)
        private set

    /** Solo Test-only options, shown in the debug-gated section of this screen. Not persisted —
     * these are testing conveniences, reset each process start. */
    var soloTestWeapon by mutableStateOf(WeaponType.PISTOL)
        private set
    var soloTestCratesEnabled by mutableStateOf(true)
        private set

    private val connection get() = activeGameConnection.current

    /** True once this screen has handed its connection off to Calibration — either the real
     * Nearby handshake completed ([isConnected]) or [startSoloTest] switched to the loopback
     * transport. [onCleared] must not tear down either of those live connections; it only exists
     * to stop the real Nearby advertising this screen started in [init] if the user backs out of
     * Create without ever connecting or starting a Solo Test. */
    private var handedOffToDuel = false

    init {
        gameSession.recordMatchMaxHealth(matchLength.maxHealth)
        observeConnectionEstablished()
        observeOpponentHello()
        startHostingAdvertisement()
    }

    private fun observeConnectionEstablished() {
        connection.connectionState
            .onEach { state ->
                if (state is ConnectionState.Connected) {
                    connection.sendMessage(GameMessage.Hello(gameSession.localPlayerName, gameSession.matchMaxHealth))
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeOpponentHello() {
        connection.incomingMessages
            .onEach { message ->
                if (message is GameMessage.Hello) {
                    gameSession.recordOpponentName(message.name)
                    // Deliberately does NOT call gameSession.recordMatchMaxHealth(message.maxHealth)
                    // here: the host already set its own real choice above, and the joiner's Hello
                    // carries only a meaningless default. Adopting it here would silently clobber
                    // the host's real pick — see JoinViewModel's own Hello-received handler, which
                    // is the one side that's actually supposed to adopt this value. A future
                    // refactor that unifies this near-identical handler with JoinViewModel's should
                    // not "simplify" this difference away.
                    isConnected = true
                    handedOffToDuel = true
                }
            }
            .launchIn(viewModelScope)
    }

    // Starts unconditionally, even for a user about to tap "Solo Test (Debug)" below: this
    // screen shows the QR/session-code section and the debug Solo Test section at once, with no
    // "user has committed to a real match" signal to gate on before advertising needs to be live
    // for a real opponent's scan. The cost is bounded (onCleared()/startSoloTest() tear it down
    // promptly either way) but it does mean Solo Test now briefly touches the real Nearby
    // transport it never used to, which is worth knowing if this ever needs to run somewhere
    // Nearby Connections doesn't work (e.g. the emulator — see CLAUDE.md's Testing section).
    private fun startHostingAdvertisement() {
        viewModelScope.launch { connection.startHosting(sessionCode) }
    }

    fun onMatchLengthSelected(length: MatchLength) {
        matchLength = length
        gameSession.recordMatchMaxHealth(length.maxHealth)
    }

    fun onSoloTestWeaponChanged(weapon: WeaponType) {
        soloTestWeapon = weapon
    }

    fun onSoloTestCratesEnabledChanged(enabled: Boolean) {
        soloTestCratesEnabled = enabled
    }

    /** Bails out of the real Nearby hosting this screen started in [init] and switches to the
     * local loopback "connection" used by Solo Test Mode instead — see
     * [ActiveGameConnection.switchToLoopback] for why that teardown-then-switch sequence is
     * owned there rather than performed by hand against [connection] here. */
    fun startSoloTest() {
        isConnected = false
        handedOffToDuel = true
        gameSession.recordOpponentName(PRACTICE_DUMMY_NAME)
        gameSession.enterPracticeMode(startingWeapon = soloTestWeapon, cratesEnabled = soloTestCratesEnabled)
        viewModelScope.launch { activeGameConnection.switchToLoopback(SOLO_TEST_SESSION_CODE) }
    }

    override fun onCleared() {
        if (!handedOffToDuel) connection.disconnect()
    }

    private companion object {
        const val SOLO_TEST_SESSION_CODE = "SOLO"
        const val PRACTICE_DUMMY_NAME = "Practice Dummy"
    }
}
