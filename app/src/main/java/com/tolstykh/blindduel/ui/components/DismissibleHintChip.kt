package com.tolstykh.blindduel.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Small dismissible hint chip, used for both the sensor-accuracy and calibration-alignment warnings. */
@Composable
fun DismissibleHintChip(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onDismiss,
        label = { Text(message) },
        modifier = modifier,
    )
}

const val COMPASS_ACCURACY_HINT =
    "Compass may be off — try moving away from metal, or wave your phone in a figure-8"
const val CALIBRATION_ALIGNMENT_HINT = "Alignment looks off — aim may be less accurate"
