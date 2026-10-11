package com.calegix.mumblevideo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.calegix.mumblevideo.core.ConnectionState
import com.calegix.mumblevideo.core.StreamKey
import com.calegix.mumblevideo.core.UserSession
import com.calegix.mumblevideo.data.servers.SavedServer
import com.calegix.mumblevideo.ui.servers.PublicServersScreen
import com.calegix.mumblevideo.ui.servers.ServerListScreen
import com.calegix.mumblevideo.ui.session.SessionScreen
import com.calegix.mumblevideo.ui.settings.SettingsScreen
import com.calegix.mumblevideo.ui.video.FullscreenVideoScreen
import kotlin.reflect.KClass

@Composable
fun AppNavHost(
    connectionState: ConnectionState,
    pendingLink: SavedServer?,
    onConsumeLink: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    FollowConnection(connectionState, navController)

    NavHost(navController = navController, startDestination = ServersRoute, modifier = modifier) {
        composable<ServersRoute> {
            ServerListScreen(
                onOpenSettings = { navController.navigate(SettingsRoute()) },
                onOpenPublicServers = { navController.navigate(PublicServersRoute) },
                onOpenSession = { navController.navigate(SessionRoute) { launchSingleTop = true } },
                pendingLink = pendingLink,
                onConsumeLink = onConsumeLink,
            )
        }
        composable<PublicServersRoute> {
            PublicServersScreen(onBack = navController::popBackStack)
        }
        composable<SessionRoute> {
            SessionScreen(
                onOpenSettings = { navController.navigate(SettingsRoute()) },
                onOpenStream = { key -> navController.navigate(FullscreenVideoRoute(key.session.value, key.streamId)) },
                onBack = navController::popBackStack,
            )
        }
        composable<FullscreenVideoRoute> { entry ->
            val route = entry.toRoute<FullscreenVideoRoute>()
            FullscreenVideoScreen(
                stream = StreamKey(UserSession(route.session), route.streamId),
                onBack = navController::popBackStack,
            )
        }
        composable<SettingsRoute> { entry ->
            SettingsScreen(
                initialSection = entry.toRoute<SettingsRoute>().section,
                onBack = navController::popBackStack,
            )
        }
    }
}

/** Opens the session screen when a connection starts and leaves it when one ends. */
@Composable
private fun FollowConnection(state: ConnectionState, navController: NavHostController) {
    val active = state !is ConnectionState.Disconnected && state !is ConnectionState.Rejected
    LaunchedEffect(active) {
        val destination = navController.currentDestination
        if (active && destination.isOn(ServersRoute::class)) {
            navController.navigate(SessionRoute) { launchSingleTop = true }
        } else if (!active &&
            (destination.isOn(SessionRoute::class) || destination.isOn(FullscreenVideoRoute::class))
        ) {
            navController.popBackStack(ServersRoute, inclusive = false)
        }
    }
}

private fun NavDestination?.isOn(route: KClass<*>): Boolean = this?.hierarchy?.any { it.hasRoute(route) } == true
