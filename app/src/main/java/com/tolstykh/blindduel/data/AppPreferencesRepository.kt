package com.tolstykh.blindduel.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tolstykh.blindduel.appPrefsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val darkModeKey = booleanPreferencesKey("dark_mode")
    private val playerNameKey = stringPreferencesKey("player_name")
    private val soundVolumeKey = intPreferencesKey("sound_volume")
    private val hapticIntensityKey = intPreferencesKey("haptic_intensity")
    private val shakeIntensityKey = intPreferencesKey("shake_intensity")
    private val trailThemeKey = stringPreferencesKey("trail_theme")
    private val particleThemeKey = stringPreferencesKey("particle_theme")

    val isDarkMode: Flow<Boolean> = context.appPrefsDataStore.data
        .map { prefs -> prefs[darkModeKey] ?: DEFAULT_DARK_MODE }
        .distinctUntilChanged()

    val playerName: Flow<String> = context.appPrefsDataStore.data
        .map { prefs -> prefs[playerNameKey] ?: "" }
        .distinctUntilChanged()

    val soundVolume: Flow<Int> = context.appPrefsDataStore.data
        .map { prefs -> prefs[soundVolumeKey] ?: DEFAULT_SOUND_VOLUME }
        .distinctUntilChanged()

    val hapticIntensity: Flow<Int> = context.appPrefsDataStore.data
        .map { prefs -> prefs[hapticIntensityKey] ?: DEFAULT_HAPTIC_INTENSITY }
        .distinctUntilChanged()

    val shakeIntensity: Flow<Int> = context.appPrefsDataStore.data
        .map { prefs -> prefs[shakeIntensityKey] ?: DEFAULT_SHAKE_INTENSITY }
        .distinctUntilChanged()

    // A stored name from a future/renamed enum entry falls back to the default rather than
    // crashing enumValueOf — the same defensive parsing as SessionCode's decodeQrPayload.
    val trailTheme: Flow<TrailTheme> = context.appPrefsDataStore.data
        .map { prefs -> prefs[trailThemeKey]?.let { runCatching { enumValueOf<TrailTheme>(it) }.getOrNull() } ?: DEFAULT_TRAIL_THEME }
        .distinctUntilChanged()

    val particleTheme: Flow<ParticleTheme> = context.appPrefsDataStore.data
        .map { prefs -> prefs[particleThemeKey]?.let { runCatching { enumValueOf<ParticleTheme>(it) }.getOrNull() } ?: DEFAULT_PARTICLE_THEME }
        .distinctUntilChanged()

    suspend fun setDarkMode(dark: Boolean) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[darkModeKey] = dark
        }
    }

    suspend fun setPlayerName(name: String) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[playerNameKey] = name
        }
    }

    suspend fun setSoundVolume(volume: Int) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[soundVolumeKey] = volume
        }
    }

    suspend fun setHapticIntensity(intensity: Int) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[hapticIntensityKey] = intensity
        }
    }

    suspend fun setShakeIntensity(intensity: Int) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[shakeIntensityKey] = intensity
        }
    }

    suspend fun setTrailTheme(theme: TrailTheme) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[trailThemeKey] = theme.name
        }
    }

    suspend fun setParticleTheme(theme: ParticleTheme) {
        context.appPrefsDataStore.edit { prefs ->
            prefs[particleThemeKey] = theme.name
        }
    }

    companion object {
        // BlindDuel's ambient duel visuals were designed dark-first, so dark mode is the
        // default. Shared so callers reading the preference before the first emission (e.g.
        // MainActivity's initial Compose state) agree with the repository's own fallback.
        const val DEFAULT_DARK_MODE = true
        const val DEFAULT_SOUND_VOLUME = 80
        const val DEFAULT_HAPTIC_INTENSITY = 100
        const val DEFAULT_SHAKE_INTENSITY = 100
        val DEFAULT_TRAIL_THEME = TrailTheme.VIOLET
        val DEFAULT_PARTICLE_THEME = ParticleTheme.CLASSIC
    }
}
