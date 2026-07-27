package com.tolstykh.blindduel.sensor

import android.media.AudioManager
import android.media.ToneGenerator
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.WeaponType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Placeholder per-weapon fire/hit/miss sound cues. No audio asset files (.wav/.mp3) exist
 * anywhere in this project, so this uses Android's built-in [ToneGenerator] to produce simple
 * distinguishable tones rather than requiring new binary assets — swap in real SFX assets
 * under res/raw later and replace this with a SoundPool/MediaPlayer-based implementation.
 * Plays only locally on the shooter's own device, for feedback/immersion — it is unrelated to
 * the cross-device loud/silent gameplay tell, which travels via GameMessage.FireEvent, not
 * audio (a phone's speaker isn't audible to the opponent's phone).
 */
@Singleton
class WeaponSoundPlayer @Inject constructor() {
    // Guards toneGenerator/currentVolume: setVolume() is deliberately called off the main thread
    // (see its own doc) since it can block on the audio HAL, but every playXSound() call still
    // runs on the main thread from Compose gesture callbacks / viewModelScope.launch bodies —
    // without this lock, setVolume()'s release()+reconstruct could race a concurrent
    // playTone()'s startTone() on the same native ToneGenerator, which is undefined behavior.
    private val lock = Any()
    private var toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    }.getOrNull()
    private var currentVolume = ToneGenerator.MAX_VOLUME

    /** [ToneGenerator]'s volume is fixed at construction, so a runtime volume setting requires
     * recreating the instance. The settings [kotlinx.coroutines.flow.Flow] this is wired from
     * always re-emits its current value once on collection (i.e. on every Duel-screen entry, not
     * just on a real change), so the [currentVolume] check below is load-bearing, not defensive
     * — without it, every duel would pay one release()+construct() of a native audio resource on
     * startup even when the volume never actually changed. Callers must also invoke this off the
     * main thread (see DuelViewModel's flowOn(Dispatchers.Default) on this setting's flow) since
     * both calls talk to the audio HAL and can block for several milliseconds. */
    fun setVolume(fraction: Float) {
        val volume = (fraction * ToneGenerator.MAX_VOLUME).roundToInt().coerceIn(0, ToneGenerator.MAX_VOLUME)
        synchronized(lock) {
            if (volume == currentVolume) return
            runCatching { toneGenerator?.release() }
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()
            currentVolume = volume
        }
    }

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
        playTone(tone, durationMs)
    }

    fun playHitSound() = playTone(ToneGenerator.TONE_CDMA_CONFIRM, GameConstants.TONE_HIT_DURATION_MS)

    fun playMissSound() = playTone(ToneGenerator.TONE_PROP_NACK, GameConstants.TONE_MISS_DURATION_MS)

    private fun playTone(tone: Int, durationMs: Int) {
        synchronized(lock) {
            runCatching { toneGenerator?.startTone(tone, durationMs) }
        }
    }

    private companion object {
        const val SHORT_BEEP_MS = 120
        const val LOUD_BEEP_MS = 220
        const val SHARP_BEEP_MS = 90
        const val BEAM_TONE_MS = 1000
    }
}
