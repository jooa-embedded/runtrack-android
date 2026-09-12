package com.runtrack.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.runtrack.ui.comparison.ComparisonScreen
import com.runtrack.ui.detail.RunDetailScreen
import com.runtrack.ui.device.DeviceConnectScreen
import com.runtrack.ui.fatigue.FatigueMeasureScreen
import com.runtrack.ui.fatigue.IntermittentFatigueMeasureScreen
import com.runtrack.ui.history.HistoryScreen
import com.runtrack.ui.home.HomeScreen
import com.runtrack.ui.liverun.LiveRunScreen
import com.runtrack.ui.onboarding.OnboardingScreen
import com.runtrack.ui.settings.SettingsScreen

/** 하단 탭 정의 */
private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Root navigation graph.
 */
@Composable
fun RunTrackNavHost(onboardingComplete: Boolean?) {
    if (onboardingComplete == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val start = if (onboardingComplete) Routes.HOME else Routes.ONBOARDING

    val tabs = listOf(
        BottomTab(Routes.HOME, "홈", Icons.Filled.Home),
        BottomTab(Routes.HISTORY, "기록", Icons.Filled.CalendarMonth),
        BottomTab(Routes.SETTINGS, "설정", Icons.Filled.Settings)
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(innerPadding)
        ) {

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onStartRun = { navController.navigate(Routes.DEVICE) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenRun = { id -> navController.navigate(Routes.detail(id)) },
                    onOpenFatigue = { navController.navigate(Routes.FATIGUE) }
                )
            }

            composable(Routes.DEVICE) {
                DeviceConnectScreen(
                    onBack = { navController.popBackStack() },
                    onStartRun = {
                        navController.navigate(Routes.LIVE_RUN) {
                            popUpTo(Routes.DEVICE) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.LIVE_RUN) {
                LiveRunScreen(
                    onFinished = { sessionId ->
                        navController.navigate(Routes.detail(sessionId)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    onDiscard = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRun = { id -> navController.navigate(Routes.detail(id)) }
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
            ) {
                RunDetailScreen(
                    onBack = { navController.popBackStack() },
                    onCompare = { id -> navController.navigate(Routes.comparison(id)) },
                    onHome = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onOpenFatigue = { navController.navigate(Routes.FATIGUE) }
                )
            }

            composable(
                route = Routes.COMPARISON,
                arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
            ) {
                ComparisonScreen(
                    onBack = { navController.popBackStack() },
                    onHome = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            /** ← 여기서 프로토콜 선택 */
            composable(Routes.FATIGUE) {
                val latestRunVm: com.runtrack.ui.fatigue.LatestRunViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val latestRunId by latestRunVm.latestRunId.collectAsState()
                IntermittentFatigueMeasureScreen(
                    onBack = { navController.popBackStack() },
                    onViewLatestRun = {
                        latestRunId?.let { id ->
                            navController.navigate(Routes.detail(id)) {
                                popUpTo(Routes.HOME)
                            }
                        }
                    }
                )
            }
        }
    }
}