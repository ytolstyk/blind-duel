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

    private companion object {
        const val NAME_SAVE_DEBOUNCE_MS = 500L
    }
}
