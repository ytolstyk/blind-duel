package com.tolstykh.blindduel.sensor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Haptics @Inject constructor(@ApplicationContext context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    fun hitFeedback() {
        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Short/light pulse for a dodge activation — distinct from [hitFeedback]'s longer buzz so
     * the two never feel the same. */
    fun dodgeFeedback() {
        vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
