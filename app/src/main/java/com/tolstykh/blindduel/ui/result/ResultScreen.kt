package com.tolstykh.blindduel.ui.result

import androidx.compose.foundation.layout.Arrangement
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
import com.tolstykh.blindduel.ui.theme.DuelAccentEmber
import com.tolstykh.blindduel.ui.theme.DuelAccentViolet

@Composable
fun ResultScreen(
    onRematch: () -> Unit,
    onQuit: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel.rematchReady) {
        if (viewModel.rematchReady) onRematch()
    }
    LaunchedEffect(viewModel.sessionEnded) {
        if (viewModel.sessionEnded) onQuit()
    }

    val (title, titleColor) = when (viewModel.outcome) {
        DuelOutcome.Win -> "You Win" to DuelAccentEmber
        DuelOutcome.Loss -> "You Lose" to MaterialTheme.colorScheme.error
        DuelOutcome.Draw -> "Draw" to DuelAccentViolet
        DuelOutcome.Ongoing -> "" to MaterialTheme.colorScheme.onBackground
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = titleColor,
        )
        Text(
            text = "vs ${viewModel.opponentName}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        Button(
            onClick = viewModel::onRematchClicked,
            enabled = !viewModel.sentRematch,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (viewModel.sentRematch) "Waiting for opponent…" else "Rematch") }

        TextButton(
            onClick = {
                viewModel.onQuitClicked()
                onQuit()
            },
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("Quit to Menu") }
    }
}
