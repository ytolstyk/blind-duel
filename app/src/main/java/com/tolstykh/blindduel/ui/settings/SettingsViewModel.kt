package com.tolstykh.blindduel.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.data.AppPreferencesRepository
import com.tolstykh.blindduel.data.ParticleTheme
import com.tolstykh.blindduel.data.TrailTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    var isDarkMode by mutableStateOf(AppPreferencesRepository.DEFAULT_DARK_MODE)
        private set
    var soundVolume by mutableIntStateOf(AppPreferencesRepository.DEFAULT_SOUND_VOLUME)
        private set
    var hapticIntensity by mutableIntStateOf(AppPreferencesRepository.DEFAULT_HAPTIC_INTENSITY)
        private set
    var shakeIntensity by mutableIntStateOf(AppPreferencesRepository.DEFAULT_SHAKE_INTENSITY)
        private set
    var trailTheme by mutableStateOf(AppPreferencesRepository.DEFAULT_TRAIL_THEME)
        private set
    var particleTheme by mutableStateOf(AppPreferencesRepository.DEFAULT_PARTICLE_THEME)
        private set

    private var saveSoundVolumeJob: Job? = null
    private var saveHapticIntensityJob: Job? = null
    private var saveShakeIntensityJob: Job? = null

    // Sliders fire on every drag frame — a raw DataStore edit{} per frame would be a full-file
    // read/serialize/write for a value only the final result of which matters, so writes are
    // debounced the same way MainMenuViewModel.onPlayerNameChanged debounces keystrokes. Shared
    // by all three slider handlers below so the debounce shape (and any future fix to it) lives
    // in exactly one place instead of three copies.
    private fun debouncedSave(previousJob: Job?, save: suspend () -> Unit): Job {
        previousJob?.cancel()
        return viewModelScope.launch {
            delay(SLIDER_SAVE_DEBOUNCE_MS)
            save()
        }
    }

    init {
        appPrefs.isDarkMode
            .onEach { isDarkMode = it }
            .launchIn(viewModelScope)
        appPrefs.soundVolume
            .onEach { soundVolume = it }
            .launchIn(viewModelScope)
        appPrefs.hapticIntensity
            .onEach { hapticIntensity = it }
            .launchIn(viewModelScope)
        appPrefs.shakeIntensity
            .onEach { shakeIntensity = it }
            .launchIn(viewModelScope)
        appPrefs.trailTheme
            .onEach { trailTheme = it }
            .launchIn(viewModelScope)
        appPrefs.particleTheme
            .onEach { particleTheme = it }
            .launchIn(viewModelScope)
    }

    fun onDarkModeChanged(dark: Boolean) {
        viewModelScope.launch { appPrefs.setDarkMode(dark) }
    }

    // The displayed value updates immediately; only the DataStore write is debounced (see
    // debouncedSave's doc).
    fun onSoundVolumeChanged(volume: Int) {
        soundVolume = volume
        saveSoundVolumeJob = debouncedSave(saveSoundVolumeJob) { appPrefs.setSoundVolume(volume) }
    }

    fun onHapticIntensityChanged(intensity: Int) {
        hapticIntensity = intensity
        saveHapticIntensityJob = debouncedSave(saveHapticIntensityJob) { appPrefs.setHapticIntensity(intensity) }
    }

    fun onShakeIntensityChanged(intensity: Int) {
        shakeIntensity = intensity
        saveShakeIntensityJob = debouncedSave(saveShakeIntensityJob) { appPrefs.setShakeIntensity(intensity) }
    }

    // Discrete FilterChip taps, not continuous drags — written immediately, same as onDarkModeChanged.
    fun onTrailThemeSelected(theme: TrailTheme) {
        trailTheme = theme
        viewModelScope.launch { appPrefs.setTrailTheme(theme) }
    }

    fun onParticleThemeSelected(theme: ParticleTheme) {
        particleTheme = theme
        viewModelScope.launch { appPrefs.setParticleTheme(theme) }
    }

    private companion object {
        const val SLIDER_SAVE_DEBOUNCE_MS = 500L
    }
}
