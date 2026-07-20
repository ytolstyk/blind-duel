package com.tolstykh.blindduel.ui.mainmenu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.game.GameSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainMenuViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
) : ViewModel() {
    var playerName by mutableStateOf("")
        private set

    fun onPlayerNameChanged(name: String) {
        playerName = name
    }

    private fun commitPlayerName() {
        gameSession.recordLocalPlayerName(playerName)
    }

    fun prepareToHost() {
        commitPlayerName()
        activeGameConnection.selectNearby()
    }

    fun prepareToJoin() {
        commitPlayerName()
        activeGameConnection.selectNearby()
    }

    fun startSoloTest() {
        commitPlayerName()
        gameSession.recordOpponentName("Practice Dummy")
        activeGameConnection.selectLoopback()
        // Solo Test Mode skips Create/Join (the screens that normally call startHosting/
        // startJoining), so kick off the loopback "connection" directly here.
        viewModelScope.launch { activeGameConnection.current.startHosting(SOLO_TEST_SESSION_CODE) }
    }

    private companion object {
        const val SOLO_TEST_SESSION_CODE = "SOLO"
    }
}
