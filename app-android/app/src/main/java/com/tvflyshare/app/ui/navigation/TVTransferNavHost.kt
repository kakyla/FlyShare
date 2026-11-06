package com.tvflyshare.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tvflyshare.app.ui.screens.appuninstall.AppUninstallScreen
import com.tvflyshare.app.ui.screens.filemanager.FileManagerScreen
import com.tvflyshare.app.ui.screens.home.HomeScreen
import com.tvflyshare.app.ui.screens.more.MoreScreen
import com.tvflyshare.app.ui.screens.network.NetworkScreen
import com.tvflyshare.app.ui.screens.preview.MediaPreviewScreen
import com.tvflyshare.app.ui.screens.settings.SettingsScreen
import com.tvflyshare.app.ui.screens.transfer.TransferScreen
import com.tvflyshare.app.ui.screens.update.UpdateScreen
import com.tvflyshare.app.ui.screens.splash.PermissionGuideScreen
import com.tvflyshare.app.ui.screens.interconnect.DeviceListScreen
import com.tvflyshare.app.ui.screens.interconnect.RemoteBrowseScreen
import java.net.URLDecoder
import com.tvflyshare.app.ui.screens.about.AboutScreen
import com.tvflyshare.app.ui.screens.about.AgreementScreen
import com.tvflyshare.app.ui.screens.about.PrivacyScreen
import com.tvflyshare.app.util.TvStoragePermissionState

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Home : Screen("home")
    data object Transfer : Screen("transfer")
    data object FileManager : Screen("file_manager/{type}?path={path}") {
        // 2026-10-04 审计 P2 修复：path 补 URL 编码（与 RemoteBrowse 的 encode/decode 对称）——
        // 当前唯一调用点字符集安全，但本工厂是公共入口，未来传入含空格/中文/?/# 的真实目录会当场截断 query
        fun createRoute(type: String, path: String? = null) = if (path != null) {
            "file_manager/$type?path=" + java.net.URLEncoder.encode(path, "UTF-8")
        } else {
            "file_manager/$type"
        }
    }
    data object MediaPreview : Screen("preview?path={path}") {
        // 与 FileManager.createRoute 一致：路径必须 URL 编码，否则含空格/中文/& 的路径会被截断
        fun createRoute(path: String) = "preview?path=" + java.net.URLEncoder.encode(path, "UTF-8")
    }
    data object More : Screen("more")
    data object AppUninstall : Screen("app_uninstall")
    data object Network : Screen("network")
    data object Settings : Screen("settings")
    data object About : Screen("about")
    data object Agreement : Screen("agreement")
    data object Privacy : Screen("privacy")
    data object Update : Screen("update")
    data object DeviceList : Screen("device_list")
    data object RemoteBrowse : Screen("remote_browse/{ip}/{port}/{name}") {
        fun createRoute(ip: String, port: Int, name: String): String {
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
            return "remote_browse/$ip/$port/$encodedName"
        }
    }
}

@Composable
fun TVTransferNavHost() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionState = androidx.compose.runtime.remember { TvStoragePermissionState(context) {} }
    val startDest = androidx.compose.runtime.remember {
        if (permissionState.isGranted) Screen.Home.route else Screen.Splash.route
    }

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable(Screen.Splash.route) {
            PermissionGuideScreen(
                onPermissionGranted = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable(Screen.Transfer.route) {
            TransferScreen(onBack = { 
                if (navController.previousBackStackEntry != null) navController.popBackStack() 
            })
        }
        composable(
            Screen.FileManager.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType; defaultValue = "all" },
                navArgument("path") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val fileType = entry.arguments?.getString("type") ?: "all"
            // 2026-10-04 审计 P2 修复：与 createRoute 的 encode 成对 decode（对当前受控字符集是恒等操作，
            // 未来含保留字符的路径也能正确还原）
            val rootPath = entry.arguments?.getString("path")?.takeIf { it.isNotEmpty() }
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            FileManagerScreen(
                fileType = fileType,
                rootPath = rootPath,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onPreview = { previewPath ->
                    navController.navigate(Screen.MediaPreview.createRoute(previewPath))
                }
            )
        }
        composable(
            Screen.MediaPreview.route,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val rawPath = entry.arguments?.getString("path").orEmpty()
            val previewPath = if (rawPath.isEmpty()) "" else URLDecoder.decode(rawPath, "UTF-8")
            MediaPreviewScreen(
                path = previewPath,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                }
            )
        }
        composable(Screen.More.route) {
            MoreScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                },
                onNavigateToDeviceList = {
                    navController.navigate(Screen.DeviceList.route)
                }
            )
        }
        composable(Screen.AppUninstall.route) {
            AppUninstallScreen(onBack = { 
                if (navController.previousBackStackEntry != null) navController.popBackStack() 
            })
        }
        composable(Screen.Network.route) {
            NetworkScreen(onBack = { 
                if (navController.previousBackStackEntry != null) navController.popBackStack() 
            })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                },
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route)
                }
            )
        }
        composable(Screen.About.route) {
            AboutScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                },
                onNavigateToAgreement = {
                    navController.navigate(Screen.Agreement.route)
                },
                onNavigateToPrivacy = {
                    navController.navigate(Screen.Privacy.route)
                }
            )
        }
        composable(Screen.Agreement.route) {
            AgreementScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                }
            )
        }
        composable(Screen.Privacy.route) {
            PrivacyScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                }
            )
        }
        composable(Screen.Update.route) {
            UpdateScreen(onBack = { 
                if (navController.previousBackStackEntry != null) navController.popBackStack() 
            })
        }
        composable(Screen.DeviceList.route) {
            DeviceListScreen(
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                },
                onDeviceSelected = { device ->
                    navController.navigate(Screen.RemoteBrowse.createRoute(device.ipAddress, device.httpPort, device.deviceName))
                }
            )
        }
        composable(
            Screen.RemoteBrowse.route,
            arguments = listOf(
                navArgument("ip") { type = NavType.StringType },
                navArgument("port") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { entry ->
            val ip = entry.arguments?.getString("ip") ?: ""
            val port = entry.arguments?.getInt("port") ?: 0
            val name = entry.arguments?.getString("name")?.let { URLDecoder.decode(it, "UTF-8") } ?: "TV Device"
            
            RemoteBrowseScreen(
                targetIp = ip,
                targetPort = port,
                targetName = name,
                onBack = { 
                    if (navController.previousBackStackEntry != null) navController.popBackStack() 
                }
            )
        }
    }
}
