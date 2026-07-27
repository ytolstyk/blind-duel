package com.tolstykh.blindduel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tolstykh.blindduel.data.ParticleTheme
import com.tolstykh.blindduel.data.TrailTheme

@Composable
fun SettingsScreen(
    onBackPressed: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackPressed) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        SettingsSectionHeader("Appearance")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dark mode", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = viewModel.isDarkMode, onCheckedChange = viewModel::onDarkModeChanged)
        }

        SettingsSectionHeader("Audio")
        LabeledPercentSlider(
            label = "Sound volume",
            percent = viewModel.soundVolume,
            onPercentChanged = viewModel::onSoundVolumeChanged,
        )

        SettingsSectionHeader("Haptics")
        LabeledPercentSlider(
            label = "Haptic intensity",
            percent = viewModel.hapticIntensity,
            onPercentChanged = viewModel::onHapticIntensityChanged,
        )

        SettingsSectionHeader("Screen shake")
        LabeledPercentSlider(
            label = "Shake intensity",
            percent = viewModel.shakeIntensity,
            onPercentChanged = viewModel::onShakeIntensityChanged,
            // Allows a punchier-than-default option, not just a dampener.
            maxPercent = 200,
        )

        SettingsSectionHeader("Trail theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrailTheme.entries.forEach { theme ->
                FilterChip(
                    selected = viewModel.trailTheme == theme,
                    onClick = { viewModel.onTrailThemeSelected(theme) },
                    label = { Text(theme.displayName) },
                )
            }
        }

        SettingsSectionHeader("Particle theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParticleTheme.entries.forEach { theme ->
                FilterChip(
                    selected = viewModel.particleTheme == theme,
                    onClick = { viewModel.onParticleThemeSelected(theme) },
                    label = { Text(theme.displayName) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun LabeledPercentSlider(
    label: String,
    percent: Int,
    onPercentChanged: (Int) -> Unit,
    maxPercent: Int = 100,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("$percent%", style = MaterialTheme.typography.bodyLarge)
    }
    Slider(
        value = percent.toFloat(),
        onValueChange = { onPercentChanged(it.toInt()) },
        valueRange = 0f..maxPercent.toFloat(),
    )
}
