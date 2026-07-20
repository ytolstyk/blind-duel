package com.tolstykh.blindduel.ui.mainmenu

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.BuildConfig
import com.tolstykh.blindduel.permission.GamePermissions

private enum class PendingAction { Create, Join }

@Composable
fun MainMenuScreen(
    onNavigateToCreate: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToSoloTest: () -> Unit,
    viewModel: MainMenuViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val action = pendingAction
        pendingAction = null
        if (action == null) return@rememberLauncherForActivityResult
        val hardRequired = GamePermissions.hardRequired(includeCamera = action == PendingAction.Join)
        val granted = hardRequired.all { results[it] == true }
        if (granted) {
            when (action) {
                PendingAction.Create -> {
                    viewModel.prepareToHost()
                    onNavigateToCreate()
                }
                PendingAction.Join -> {
                    viewModel.prepareToJoin()
                    onNavigateToJoin()
                }
            }
        } else {
            permissionDenied = true
        }
    }

    fun requestAndProceed(action: PendingAction) {
        permissionDenied = false
        pendingAction = action
        val includeCamera = action == PendingAction.Join
        permissionLauncher.launch(GamePermissions.required(includeCamera).toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "BlindDuel", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = viewModel.playerName,
            onValueChange = viewModel::onPlayerNameChanged,
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp),
        )

        Button(
            onClick = { requestAndProceed(PendingAction.Create) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create Duel") }

        Button(
            onClick = { requestAndProceed(PendingAction.Join) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) { Text("Join Duel") }

        if (permissionDenied) {
            Text(
                text = "BlindDuel needs Bluetooth/Nearby permissions (and camera, to join) to connect.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = { context.openAppSettings() }) { Text("Open Settings") }
        }

        if (BuildConfig.DEBUG) {
            TextButton(
                onClick = {
                    viewModel.startSoloTest()
                    onNavigateToSoloTest()
                },
                modifier = Modifier.padding(top = 24.dp),
            ) { Text("Solo Test (Debug)") }
        }
    }
}

private fun android.content.Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}
