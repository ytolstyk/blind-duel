package com.tolstykh.blindduel.ui.result

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.quit
import com.tolstykh.blindduel.connection.sessionEndedEvents
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.GameSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
) : ViewModel() {

    val outcome: DuelOutcome get() = gameSession.finalOutcome
    val opponentName: String get() = gameSession.opponentName

    var sentRematch by mutableStateOf(false)
        private set
    var rematchReady by mutableStateOf(false)
        private set

    /** Covers both a real transport drop and the opponent explicitly quitting. */
    var sessionEnded by mutableStateOf(false)
        private set

    private val connection get() = activeGameConnection.current
    private var receivedRematch = false

    init {
        connection.incomingMessages
            .onEach { message ->
                if (message is GameMessage.RematchRequest) {
                    receivedRematch = true
                    checkRematchReady()
                }
            }
            .launchIn(viewModelScope)

        connection.sessionEndedEvents()
            .onEach { sessionEnded = true }
            .launchIn(viewModelScope)
    }

    fun onRematchClicked() {
        if (sentRematch) return
        sentRematch = true
        viewModelScope.launch { connection.sendMessage(GameMessage.RematchRequest) }
        checkRematchReady()
    }

    fun onQuitClicked() {
        viewModelScope.launch { connection.quit() }
    }

    private fun checkRematchReady() {
        if (sentRematch && receivedRematch) {
            rematchReady = true
        }
    }
}
