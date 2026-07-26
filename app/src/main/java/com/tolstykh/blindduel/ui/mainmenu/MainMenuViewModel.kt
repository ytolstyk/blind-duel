package com.tolstykh.blindduel.ui.mainmenu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.data.AppPreferencesRepository
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.MatchLength
import com.tolstykh.blindduel.game.WeaponType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainMenuViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {
    var playerName by mutableStateOf("")
        private set

    /** Host-only pre-duel choice (see MainMenuScreen's non-debug match-length picker) — the
     * joiner always adopts whatever the host picked, via GameMessage.Hello, so this state is
     * meaningless/unused on the Join path. Not persisted, reset each process start. */
    var selectedMatchLength by mutableStateOf(MatchLength.STANDARD)
        private set

    /** Solo Test-only options, shown in the debug-gated section of the main menu. Not
     * persisted — these are testing conveniences, reset each process start, unlike [playerName]. */
    var soloTestWeapon by mutableStateOf(WeaponType.PISTOL)
        private set
    var soloTestCratesEnabled by mutableStateOf(true)
        private set

    private var saveNameJob: Job? = null

    init {
        appPrefs.playerName
            .onEach { playerName = it }
            .launchIn(viewModelScope)
    }

    fun onPlayerNameChanged(name: String) {
        playerName = name
        // Debounced: a raw DataStore edit{} on every keystroke would be a full-file
        // read/serialize/write per character for a value only the final result of matters.
        saveNameJob?.cancel()
        saveNameJob = viewModelScope.launch {
            delay(NAME_SAVE_DEBOUNCE_MS)
            appPrefs.setPlayerName(name)
        }
    }

    private fun commitPlayerName() {
        gameSession.recordLocalPlayerName(playerName)
    }

    fun prepareToHost() {
        commitPlayerName()
        gameSession.exitPracticeMode()
        gameSession.recordMatchMaxHealth(selectedMatchLength.maxHealth)
        activeGameConnection.selectNearby()
    }

    fun prepareToJoin() {
        commitPlayerName()
        gameSession.exitPracticeMode()
        // The joiner's real match length arrives later via the host's Hello — reset to the
        // constant default here so a value left over from a previous match (as either host or
        // joiner) can never be shown/used before that Hello lands, mirroring exitPracticeMode()
        // just above for the same "clear stale state from a prior session" reason.
        gameSession.recordMatchMaxHealth(GameConstants.MAX_PLAYER_HEALTH)
        activeGameConnection.selectNearby()
    }

    fun onMatchLengthSelected(length: MatchLength) {
        selectedMatchLength = length
    }

    fun onSoloTestWeaponChanged(weapon: WeaponType) {
        soloTestWeapon = weapon
    }

    fun onSoloTestCratesEnabledChanged(enabled: Boolean) {
        soloTestCratesEnabled = enabled
    }

    fun startSoloTest() {
        commitPlayerName()
        gameSession.recordOpponentName(PRACTICE_DUMMY_NAME)
        gameSession.enterPracticeMode(startingWeapon = soloTestWeapon, cratesEnabled = soloTestCratesEnabled)
        activeGameConnection.selectLoopback()
        // Solo Test Mode skips Create/Join (the screens that normally call startHosting/
        // startJoining), so kick off the loopback "connection" directly here.
        viewModelScope.launch { activeGameConnection.current.startHosting(SOLO_TEST_SESSION_CODE) }
    }

    private companion object {
        const val SOLO_TEST_SESSION_CODE = "SOLO"
        const val PRACTICE_DUMMY_NAME = "Practice Dummy"
        const val NAME_SAVE_DEBOUNCE_MS = 500L
    }
}
