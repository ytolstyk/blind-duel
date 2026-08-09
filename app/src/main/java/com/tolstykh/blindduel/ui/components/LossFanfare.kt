package com.tolstykh.blindduel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tolstykh.blindduel.game.GameConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Loss beat for ResultScreen (also reached from Solo Test/practice mode, which shares the same
 * screen): a central avatar bursts into radiating shards (reusing [drawExplosion]/
 * [drawOmnidirectionalParticles], the same burst primitives DuelScreen's hit/crate explosions
 * use), then "YOU" flies up and "LOSE" flies down to rest near the top/bottom of [modifier]'s
 * bounds, arcade-style. Plays once per composition — ResultScreen only composes this while the
 * outcome is Loss, so there's no replay trigger to key off. [onExploded] fires once, right as the
 * burst starts, so the caller can pair a haptic pulse with the visual beat without this
 * composable needing to know about [com.tolstykh.blindduel.sensor.Haptics] itself.
 */
@Composable
fun LossFanfare(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    onExploded: () -> Unit = {},
) {
    val avatarScale = remember { Animatable(0f) }
    val avatarAlpha = remember { Animatable(1f) }
    val explosionProgress = remember { Animatable(0f) }
    val youProgress = remember { Animatable(0f) }
    val loseProgress = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    val explosionSeeds = remember {
        randomSpraySeeds(
            seed = Random.nextLong(),
            count = GameConstants.RESULT_EXPLOSION_SHARD_COUNT,
            spreadDegrees = GameConstants.PARTICLE_BURST_FULL_CIRCLE_SPREAD_DEGREES,
        )
    }

    LaunchedEffect(Unit) {
        avatarScale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
        delay(GameConstants.RESULT_AVATAR_HOLD_DURATION_MS)

        onExploded()
        launch { explosionProgress.animateTo(1f, tween(GameConstants.RESULT_EXPLOSION_DURATION_MS, easing = LinearOutSlowInEasing)) }
        launch {
            avatarScale.animateTo(
                GameConstants.RESULT_AVATAR_OVERSHOOT_SCALE,
                tween(GameConstants.RESULT_AVATAR_OVERSHOOT_DURATION_MS, easing = LinearOutSlowInEasing),
            )
            avatarAlpha.animateTo(
                0f,
                tween(GameConstants.RESULT_EXPLOSION_DURATION_MS - GameConstants.RESULT_AVATAR_OVERSHOOT_DURATION_MS),
            )
        }

        delay(GameConstants.RESULT_TEXT_FLY_START_DELAY_MS)
        launch { textAlpha.animateTo(1f, tween(GameConstants.RESULT_TEXT_ALPHA_FADE_MS)) }
        launch {
            youProgress.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
        }
        launch {
            delay(GameConstants.RESULT_LOSE_TEXT_STAGGER_MS)
            loseProgress.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val restingOffsetPx = with(LocalDensity.current) {
            (maxHeight / 2f - GameConstants.RESULT_TEXT_RESTING_INSET_DP.dp).toPx()
        }

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            if (avatarAlpha.value > 0f) {
                drawCircle(
                    color = color.copy(alpha = avatarAlpha.value),
                    radius = GameConstants.RESULT_AVATAR_SIZE_DP.dp.toPx() / 2f * avatarScale.value,
                    center = center,
                )
            }
            if (explosionProgress.value > 0f) {
                drawExplosion(center, GameConstants.RESULT_AVATAR_SIZE_DP.dp.toPx() / 2f, explosionProgress.value, color)
                drawOmnidirectionalParticles(
                    center = center,
                    progress = explosionProgress.value,
                    maxDistancePx = GameConstants.RESULT_EXPLOSION_MAX_DISTANCE_DP.dp.toPx(),
                    particleRadiusPx = GameConstants.RESULT_EXPLOSION_SHARD_SIZE_DP.dp.toPx(),
                    color = color,
                    seeds = explosionSeeds,
                )
            }
        }

        Text(
            text = "YOU",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black),
            color = color,
            modifier = Modifier.graphicsLayer {
                alpha = textAlpha.value
                translationY = -youProgress.value * restingOffsetPx
            },
        )
        Text(
            text = "LOSE",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black),
            color = color,
            modifier = Modifier.graphicsLayer {
                alpha = textAlpha.value
                translationY = loseProgress.value * restingOffsetPx
            },
        )
    }
}
