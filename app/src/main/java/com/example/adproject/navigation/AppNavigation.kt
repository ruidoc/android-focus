package com.example.adproject.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.adproject.ui.screens.AppListScreen
import com.example.adproject.ui.screens.HomeScreen

// 定义导航路由
object AppRoutes {
    const val HOME = "home"
    const val APP_LIST = "app_list"
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppRoutes.HOME,
    vpnLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    vpnDuration: String = "00:00:00"
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // 首页
        composable(AppRoutes.HOME) {
            HomeScreen(
                onNavigateToAppList = {
                    navController.navigate(AppRoutes.APP_LIST)
                },
                vpnLauncher = vpnLauncher,
                vpnDuration = vpnDuration
            )
        }
        
        // 应用列表页
        composable(AppRoutes.APP_LIST) {
            AppListScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
} 