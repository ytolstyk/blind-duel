package com.tolstykh.blindduel.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.ui.components.KeepScreenOn

@Composable
fun CalibrationScreen(
    onReadyForDuel: () -> Unit,
    onConnectionLost: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    KeepScreenOn()

    LaunchedEffect(viewModel.phase) {
        if (viewModel.phase == CalibrationPhase.Ready) onReadyForDuel()
    }
    LaunchedEffect(viewModel.sessionEnded) {
        if (viewModel.sessionEnded) onConnectionLost()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Matched with ${viewModel.opponentName}", style = MaterialTheme.typography.headlineSmall)

        when (val phase = viewModel.phase) {
            is CalibrationPhase.AwaitingBothReady -> {
                if (viewModel.sentReady) {
                    Text(text = "Waiting for opponent…", modifier = Modifier.padding(top = 24.dp))
                } else {
                    Button(
                        onClick = viewModel::onReadyClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                    ) { Text("Ready") }
                }
            }
            is CalibrationPhase.CountingDown -> {
                Text(
                    text = "Face your opponent!",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    text = if (phase.secondsRemaining > 0) phase.secondsRemaining.toString() else "Go!",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            CalibrationPhase.AwaitingOpponentBaseline -> {
                Text(text = "Waiting for opponent…", modifier = Modifier.padding(top = 24.dp))
            }
            CalibrationPhase.Ready -> Unit // navigating away via LaunchedEffect above
        }
    }
}
