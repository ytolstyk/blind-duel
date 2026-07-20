package com.tolstykh.blindduel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.tolstykh.blindduel.navigation.Navigation
import com.tolstykh.blindduel.ui.theme.BlindDuelTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlindDuelTheme {
                Navigation(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
