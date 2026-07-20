package com.tolstykh.blindduel.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tolstykh.blindduel.ui.calibration.CalibrationScreen
import com.tolstykh.blindduel.ui.create.CreateScreen
import com.tolstykh.blindduel.ui.duel.DuelScreen
import com.tolstykh.blindduel.ui.join.JoinScreen
import com.tolstykh.blindduel.ui.mainmenu.MainMenuScreen
import com.tolstykh.blindduel.ui.result.ResultScreen
import com.tolstykh.blindduel.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable private object MainMenu
@Serializable private object Create
@Serializable private object Join
@Serializable private object Calibration
@Serializable private object Duel
@Serializable private object Result
@Serializable private object Settings

@Composable
fun Navigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = MainMenu,
        modifier = modifier.fillMaxSize(),
    ) {
        composable<MainMenu> {
            MainMenuScreen(
                onNavigateToCreate = { navController.navigate(Create) },
                onNavigateToJoin = { navController.navigate(Join) },
                onNavigateToSoloTest = {
                    navController.navigate(Calibration) { popUpTo<MainMenu>() }
                },
                onNavigateToSettings = { navController.navigate(Settings) },
            )
        }
        composable<Settings> {
            SettingsScreen(onBackPressed = { navController.popBackStack() })
        }
        composable<Create> {
            CreateScreen(
                onConnected = { navController.navigate(Calibration) { popUpTo<MainMenu>() } },
            )
        }
        composable<Join> {
            JoinScreen(
                onConnected = { navController.navigate(Calibration) { popUpTo<MainMenu>() } },
            )
        }
        composable<Calibration> {
            CalibrationScreen(
                onReadyForDuel = {
                    navController.navigate(Duel) { popUpTo<Calibration> { inclusive = true } }
                },
                onConnectionLost = { navController.returnToMainMenu() },
            )
        }
        composable<Duel> {
            DuelScreen(
                onDuelOver = {
                    navController.navigate(Result) { popUpTo<Duel> { inclusive = true } }
                },
                onConnectionLost = { navController.returnToMainMenu() },
            )
        }
        composable<Result> {
            ResultScreen(
                onRematch = { navController.navigate(Calibration) { popUpTo<MainMenu>() } },
                onQuit = { navController.returnToMainMenu() },
            )
        }
    }
}

private fun NavHostController.returnToMainMenu() {
    navigate(MainMenu) {
        popUpTo<MainMenu> { inclusive = true }
        launchSingleTop = true
    }
}
