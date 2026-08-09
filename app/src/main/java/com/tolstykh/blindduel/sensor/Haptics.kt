package com.tolstykh.blindduel.sensor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.tolstykh.blindduel.game.GameConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class Haptics @Inject constructor(@ApplicationContext context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    /** Every pattern takes an explicit [intensityFraction] (0f..1f, from the user's haptic
     * intensity setting) rather than [VibrationEffect.DEFAULT_AMPLITUDE], since that sentinel
     * (-1) can't be scaled — 0f skips vibrating entirely instead of clamping to the minimum
     * amplitude, so a user who sets intensity to 0% gets silence, not a faint buzz. */
    private fun vibrate(durationMs: Long, baseAmplitude: Int, intensityFraction: Float) {
        if (intensityFraction <= 0f) return
        val amplitude = (baseAmplitude * intensityFraction).roundToInt().coerceIn(1, 255)
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }

    fun hitFeedback(intensityFraction: Float) =
        vibrate(GameConstants.HAPTIC_HIT_DURATION_MS, GameConstants.HAPTIC_HIT_AMPLITUDE, intensityFraction)

    /** Short/light pulse for a dodge activation — distinct from [hitFeedback]'s longer buzz so
     * the two never feel the same. */
    fun dodgeFeedback(intensityFraction: Float) =
        vibrate(GameConstants.HAPTIC_DODGE_DURATION_MS, GameConstants.HAPTIC_DODGE_AMPLITUDE, intensityFraction)

    /** Very short, light pulse on firing — distinct from both [hitFeedback] and [dodgeFeedback]
     * so all three never feel the same. */
    fun fireFeedback(intensityFraction: Float) =
        vibrate(GameConstants.HAPTIC_FIRE_DURATION_MS, GameConstants.HAPTIC_FIRE_AMPLITUDE, intensityFraction)

    /** "Getting close" pulse — see [com.tolstykh.blindduel.game.ProximityMath]. */
    fun proximityFeedback(intensityFraction: Float) =
        vibrate(GameConstants.PROXIMITY_HAPTIC_DURATION_MS, GameConstants.PROXIMITY_HAPTIC_AMPLITUDE, intensityFraction)

    /** ResultScreen's loss avatar-explosion beat — longer/stronger than [hitFeedback] since it's
     * a one-time dramatic moment rather than a repeated in-duel cue. */
    fun resultExplosionFeedback(intensityFraction: Float) = vibrate(
        GameConstants.HAPTIC_RESULT_EXPLOSION_DURATION_MS,
        GameConstants.HAPTIC_RESULT_EXPLOSION_AMPLITUDE,
        intensityFraction,
    )

    /** ResultScreen's just-for-fun victory balloon pop. */
    fun balloonPopFeedback(intensityFraction: Float) =
        vibrate(GameConstants.HAPTIC_BALLOON_POP_DURATION_MS, GameConstants.HAPTIC_BALLOON_POP_AMPLITUDE, intensityFraction)
}
