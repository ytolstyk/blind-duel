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

    private val connection get() = activeGameConnection.current

    init {
        connection.connectionState
            .onEach { state ->
                if (state is ConnectionState.Connected) {
                    connection.sendMessage(GameMessage.Hello(gameSession.localPlayerName))
                }
            }
            .launchIn(viewModelScope)

        connection.incomingMessages
            .onEach { message ->
                if (message is GameMessage.Hello) {
                    gameSession.recordOpponentName(message.name)
                    isConnected = true
                }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch { connection.startHosting(sessionCode) }
    }
}
