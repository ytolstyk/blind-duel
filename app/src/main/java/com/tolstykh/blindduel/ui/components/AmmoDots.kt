package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants

/** Ammo-pip readout for the magazine mechanic — tapping it requests an early, voluntary reload
 * (forfeiting any rounds still in the magazine) via [onTap]. Uses its own [pointerInput]/
 * [detectTapGestures] rather than [androidx.compose.foundation.clickable] so the tap is consumed
 * the same way [ReloadRing]'s does — the underlying DuelScreen drag-to-aim detector's
 * `awaitFirstDown(requireUnconsumed = true)` never sees a down event that landed here. */
@Composable
fun LabeledAmmoDots(
    label: String,
    roundsRemaining: Int,
    dotColor: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        horizontalAlignment = horizontalAlignment,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = dotColor)
        Spacer(modifier = Modifier.height(4.dp))
        DotsRow(filledCount = roundsRemaining, totalCount = GameConstants.MAGAZINE_SIZE, dotColor = dotColor)
    }
}
