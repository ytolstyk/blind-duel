package com.tolstykh.blindduel.sensor

import android.media.AudioManager
import android.media.ToneGenerator
import com.tolstykh.blindduel.game.WeaponType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder per-weapon fire sound cues. No audio asset files (.wav/.mp3) exist anywhere in
 * this project, so this uses Android's built-in [ToneGenerator] to produce simple
 * distinguishable tones rather than requiring new binary assets — swap in real SFX assets
 * under res/raw later and replace this with a SoundPool/MediaPlayer-based implementation.
 * Plays only locally on the shooter's own device, for feedback/immersion — it is unrelated to
 * the cross-device loud/silent gameplay tell, which travels via GameMessage.FireEvent, not
 * audio (a phone's speaker isn't audible to the opponent's phone).
 */
@Singleton
class WeaponSoundPlayer @Inject constructor() {
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    }.getOrNull()

    /** No per-shot cooldown exists in this game, so a rapid-fire burst can cut one tone off
     * mid-play when the next starts — an accepted, documented placeholder-quality limitation. */
    fun playFireSound(weaponType: WeaponType) {
        val (tone, durationMs) = when (weaponType) {
            WeaponType.PISTOL -> ToneGenerator.TONE_PROP_BEEP to SHORT_BEEP_MS
            WeaponType.SHOTGUN -> ToneGenerator.TONE_PROP_BEEP2 to LOUD_BEEP_MS
            WeaponType.SNIPER -> ToneGenerator.TONE_CDMA_PIP to SHARP_BEEP_MS
            WeaponType.LASER -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to BEAM_TONE_MS
            WeaponType.BOW -> return // silent, by design — no tone at all
        }
        runCatching { toneGenerator?.startTone(tone, durationMs) }
    }

    private companion object {
        const val SHORT_BEEP_MS = 120
        const val LOUD_BEEP_MS = 220
        const val SHARP_BEEP_MS = 90
        const val BEAM_TONE_MS = 1000
    }
}
