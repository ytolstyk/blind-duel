package com.tolstykh.blindduel.ui.mainmenu

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.tolstykh.blindduel.game.GameConstants.WeaponConfigs
import com.tolstykh.blindduel.game.WeaponType
import com.tolstykh.blindduel.permission.GamePermissions

private enum class PendingAction { Create, Join }

@Composable
fun MainMenuScreen(
    onNavigateToCreate: () -> Unit,
    onNavigateToJoin: () -> Unit,
    onNavigateToSoloTest: () -> Unit,
    onNavigateToSettings: () -> Unit,
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

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                SoloTestOptions(
                    viewModel = viewModel,
                    onStartSoloTest = {
                        viewModel.startSoloTest()
                        onNavigateToSoloTest()
                    },
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

/** Debug-only pre-game options for Solo Test Mode: pick a starting weapon (so any weapon can be
 * tested without waiting on a random crate drop) and toggle crate drops on/off, so a practice
 * session can be tuned to test a specific thing while still defaulting to look like a real match. */
@Composable
private fun SoloTestOptions(
    viewModel: MainMenuViewModel,
    onStartSoloTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = "Solo Test Options",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = "Starting weapon",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WeaponType.entries.forEach { weapon ->
                FilterChip(
                    selected = viewModel.soloTestWeapon == weapon,
                    onClick = { viewModel.onSoloTestWeaponChanged(weapon) },
                    label = { Text(WeaponConfigs.forType(weapon).displayName) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Weapon crates", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = viewModel.soloTestCratesEnabled,
                onCheckedChange = viewModel::onSoloTestCratesEnabledChanged,
            )
        }

        TextButton(onClick = onStartSoloTest, modifier = Modifier.padding(top = 8.dp)) {
            Text("Solo Test (Debug)")
        }
    }
}

private fun android.content.Context.openAppSettings() {
    startActivity(
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}
