package com.tolstykh.blindduel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants

@Composable
fun HealthDots(remainingHealth: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(GameConstants.MAX_PLAYER_HEALTH) { index ->
            val filled = index < remainingHealth
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (filled) MaterialTheme.colorScheme.secondary else Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.secondary, CircleShape),
            )
        }
    }
}
