package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Dodge button — tapping it broadcasts a DodgeEvent that briefly narrows the opponent's
 * effective hit tolerance against you (see DuelViewModel.onDodgeTapped / HitToleranceMath).
 * [cooldownProgress] is 1f when ready, 0f right after use, ticking back up to 1f over
 * GameConstants.DODGE_COOLDOWN_MS — rendered as a simple greyed-out alpha rather than a full
 * progress ring, deliberately kept simple (no moving dial/arc) to keep this feature's scope
 * tight; [ReloadRing] is the fuller-fidelity pattern if this ever needs upgrading.
 *
 * Uses its own [pointerInput]/[detectTapGestures] rather than
 * [androidx.compose.foundation.clickable], same reasoning as [ReloadRing]/[LabeledAmmoDots]: the
 * down event must be consumed here so DuelScreen's full-size drag-to-aim detector (whose
 * `awaitFirstDown` defaults to `requireUnconsumed = true`) never sees a touch that started on
 * this button. Does not gate the tap itself on [cooldownProgress] — DuelViewModel.onDodgeTapped
 * already no-ops during cooldown, the same convention [LabeledAmmoDots] already uses for its own
 * gated action. */
@Composable
fun DodgeButton(
    cooldownProgress: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
) {
    val isReady = cooldownProgress >= 1f
    Column(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (isReady) 0.25f else 0.08f))
                .border(2.dp, color.copy(alpha = if (isReady) 1f else 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↯", // a simple zig-zag "dodge" glyph — no icon dependency needed
                style = MaterialTheme.typography.titleMedium,
                color = color.copy(alpha = if (isReady) 1f else 0.35f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "DODGE",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = if (isReady) 1f else 0.5f),
        )
    }
}
