package com.tolstykh.blindduel.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    val isDarkMode: Flow<Boolean> = context.appPrefsDataStore.data
        .map { prefs -> prefs[darkModeKey] ?: DEFAULT_DARK_MODE }
        .distinctUntilChanged()

    val playerName: Flow<String> = context.appPrefsDataStore.data
        .map { prefs -> prefs[playerNameKey] ?: "" }
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

    companion object {
        // BlindDuel's ambient duel visuals were designed dark-first, so dark mode is the
        // default. Shared so callers reading the preference before the first emission (e.g.
        // MainActivity's initial Compose state) agree with the repository's own fallback.
        const val DEFAULT_DARK_MODE = true
    }
}
