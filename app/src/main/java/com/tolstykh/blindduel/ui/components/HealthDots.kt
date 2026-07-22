package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants

@Composable
fun HealthDots(
    remainingHealth: Int,
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.secondary,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(GameConstants.MAX_PLAYER_HEALTH) { index ->
            val filled = index < remainingHealth
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (filled) dotColor else Color.Transparent)
                    .border(1.dp, dotColor, CircleShape),
            )
        }
    }
}

/** A labeled [HealthDots] row — used to tell my own health apart from the opponent's, since
 * both are now shown on screen at once. */
@Composable
fun LabeledHealthDots(
    label: String,
    remainingHealth: Int,
    dotColor: Color,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = dotColor)
        Spacer(modifier = Modifier.height(4.dp))
        HealthDots(remainingHealth = remainingHealth, dotColor = dotColor)
    }
}
