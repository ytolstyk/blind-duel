package com.tolstykh.blindduel.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.ui.theme.DuelParticle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    val xFraction: Float,
    val yFraction: Float,
    val radiusPx: Float,
    val twinkleSpeed: Float,
    val phase: Float,
)

/** Ambient dust/star backdrop behind the duel character — purely decorative, no gameplay meaning. */
@Composable
fun ParticleField(modifier: Modifier = Modifier) {
    val particles = remember {
        List(GameConstants.AMBIENT_PARTICLE_COUNT) {
            Particle(
                xFraction = Random.nextFloat(),
                yFraction = Random.nextFloat(),
                radiusPx = 2f + Random.nextFloat() * 4f,
                twinkleSpeed = 0.5f + Random.nextFloat() * 1.5f,
                phase = Random.nextFloat() * (2 * PI).toFloat(),
            )
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "ambientParticles")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(20_000, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawParticles(particles, phase)
    }
}

private fun DrawScope.drawParticles(particles: List<Particle>, phase: Float) {
    particles.forEach { particle ->
        val twinkle = (sin(phase * particle.twinkleSpeed + particle.phase) + 1f) / 2f
        val alpha = (0.25f + 0.6f * twinkle).coerceIn(0.1f, 1f)
        drawCircle(
            color = DuelParticle.copy(alpha = alpha),
            radius = particle.radiusPx,
            center = Offset(particle.xFraction * size.width, particle.yFraction * size.height),
        )
    }
}
