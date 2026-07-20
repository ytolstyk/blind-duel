package com.tolstykh.blindduel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tolstykh.blindduel.data.AppPreferencesRepository
import com.tolstykh.blindduel.navigation.Navigation
import com.tolstykh.blindduel.ui.theme.BlindDuelTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appPrefs: AppPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDark by appPrefs.isDarkMode.collectAsStateWithLifecycle(
                initialValue = AppPreferencesRepository.DEFAULT_DARK_MODE,
            )
            BlindDuelTheme(darkTheme = isDark) {
                Navigation(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
