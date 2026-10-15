package com.fibrocoir.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fibrocoir.app.ecviewer.EcViewerScreen
import com.fibrocoir.app.eclog.EcLogScreen
import com.fibrocoir.app.fleet.FleetScreen
import com.fibrocoir.app.hub.HubHome
import com.fibrocoir.app.hub.HubViewModel
import com.fibrocoir.app.hub.LoginScreen
import com.fibrocoir.app.hub.ManageScreen
import com.fibrocoir.app.hub.Screen
import com.fibrocoir.app.husk.HuskScreen
import com.fibrocoir.app.inventory.InventoryScreen
import com.fibrocoir.app.ui.Fcp
import com.fibrocoir.app.ui.FcpTheme
import com.fibrocoir.app.water.WaterScreen

class MainActivity : ComponentActivity() {

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Topics.resubscribeAll(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        openNotificationLink(intent)

        setContent {
            FcpTheme {
                Box(Modifier.fillMaxSize().background(Fcp.husk)) { FcpApp() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openNotificationLink(intent)
    }

    /** A notification can carry a link; web links open in the browser. */
    private fun openNotificationLink(intent: Intent?) {
        val url = intent?.getStringExtra(Notifications.EXTRA_URL) ?: return
        intent.removeExtra(Notifications.EXTRA_URL)
        if (url.startsWith("http")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
    }
}

@Composable
fun FcpApp() {
    val hub: HubViewModel = viewModel()
    val signedIn by hub.signedIn.collectAsState()
    if (!signedIn) { LoginScreen(hub); return }

    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val slide = tween<androidx.compose.ui.unit.IntOffset>(260)
    NavHost(
        navController = nav, startDestination = "hub",
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide, targetOffset = { it / 4 }) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, slide, initialOffset = { it / 4 }) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slide) },
    ) {
        composable("hub") { HubHome(hub, openScreen = { nav.go(it.key) }, openManage = { nav.go("manage") }) }
        composable("manage") { ManageScreen(hub, back) }
        composable(Screen.EC_LOG.key) { EcLogScreen(back) }
        composable(Screen.EC_VIEWER.key) { EcViewerScreen(back) }
        composable(Screen.WATER.key) { WaterScreen(back) }
        composable(Screen.HUSK.key) { HuskScreen(back) }
        composable(Screen.INVENTORY.key) { InventoryScreen(back) }
        composable(Screen.FLEET.key) { FleetScreen(back) }
    }
}

private fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }
