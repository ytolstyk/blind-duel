package com.tolstykh.blindduel.ui.result

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.ui.components.LossFanfare
import com.tolstykh.blindduel.ui.components.VictoryBalloons
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet

@Composable
fun ResultScreen(
    onRematch: () -> Unit,
    onQuit: () -> Unit,
    onWatchReplay: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel.rematchReady) {
        if (viewModel.rematchReady) onRematch()
    }
    LaunchedEffect(viewModel.sessionEnded) {
        if (viewModel.sessionEnded) onQuit()
    }

    val outcome = viewModel.outcome
    val titleColor = when (outcome) {
        DuelOutcome.Win -> DuelAccentEmber
        DuelOutcome.Loss -> MaterialTheme.colorScheme.error
        DuelOutcome.Draw -> DuelAccentViolet
        DuelOutcome.Ongoing -> MaterialTheme.colorScheme.onBackground
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Rises behind the title/buttons below (declared first = bottom of z-order) so a
        // balloon drifting behind a button never steals its tap, but still pops when tapped
        // anywhere else on screen — see VictoryBalloons' own doc.
        if (outcome == DuelOutcome.Win) {
            VictoryBalloons(
                modifier = Modifier.fillMaxSize(),
                onBalloonPopped = viewModel::onBalloonPopped,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (outcome) {
                    DuelOutcome.Loss -> LossFanfare(
                        modifier = Modifier.fillMaxSize(),
                        color = titleColor,
                        onExploded = viewModel::onLossExploded,
                    )
                    DuelOutcome.Win -> Text(
                        text = "You Win",
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = titleColor,
                    )
                    DuelOutcome.Draw -> Text(
                        text = "Draw",
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = titleColor,
                    )
                    DuelOutcome.Ongoing -> {}
                }
            }

            Text(
                text = "vs ${viewModel.opponentName}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 32.dp),
            )

            Button(
                onClick = viewModel::onRematchClicked,
                enabled = !viewModel.sentRematch,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (viewModel.sentRematch) "Waiting for opponent…" else "Rematch") }

            if (viewModel.hasReplay) {
                TextButton(
                    onClick = onWatchReplay,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Watch Replay") }
            }

            TextButton(
                onClick = {
                    viewModel.onQuitClicked()
                    onQuit()
                },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Quit to Menu") }
        }
    }
}
