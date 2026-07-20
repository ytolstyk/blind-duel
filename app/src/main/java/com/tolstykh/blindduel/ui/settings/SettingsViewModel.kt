package com.tolstykh.blindduel.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.data.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    init {
        appPrefs.isDarkMode
            .onEach { isDarkMode = it }
            .launchIn(viewModelScope)
    }

    fun onDarkModeChanged(dark: Boolean) {
        viewModelScope.launch { appPrefs.setDarkMode(dark) }
    }
}
