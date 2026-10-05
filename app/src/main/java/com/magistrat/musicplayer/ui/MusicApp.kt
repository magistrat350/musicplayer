package com.magistrat.musicplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.update.Updater
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("songs", "Songs", Icons.Default.LibraryMusic),
    Tab("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    Tab("audiobooks", "Hörbücher", Icons.AutoMirrored.Filled.MenuBook),
    Tab("download", "Download", Icons.Default.Download),
)

@Composable
fun MusicApp(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    fun goTab(r: String) {
        nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) goTab("download")
    }

    // Beim Start auf neue App-Version pruefen
    val context = LocalContext.current
    val update by Updater.available.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Updater.checkIfDue(context) }
    update?.let { UpdateDialog(it) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (route != "player" && route != "queue") {
                Column {
                    MiniPlayer(onOpen = { nav.navigate("player") { launchSingleTop = true } })
                    NavigationBar {
                        TABS.forEach { tab ->
                            val selected = when (tab.route) {
                                "playlists" -> route == "playlists" || route?.startsWith("playlist/") == true
                                "audiobooks" -> route == "audiobooks" || route?.startsWith("audiobook/") == true
                                else -> route == tab.route
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { goTab(tab.route) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "songs", modifier = Modifier.padding(padding)) {
            composable("songs") { SongsScreen() }
            composable("playlists") { PlaylistsScreen(onOpen = { nav.navigate("playlist/$it") }) }
            composable("audiobooks") { AudiobooksScreen(onOpen = { nav.navigate("audiobook/$it") }) }
            composable("download") { DownloadScreen(sharedUrl, onSharedUrlConsumed) }
            composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PlaylistDetailScreen(it.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
            }
            composable("audiobook/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                AudiobookDetailScreen(it.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
            }
            composable("player") {
                PlayerScreen(onClose = { nav.popBackStack() }, onOpenQueue = { nav.navigate("queue") { launchSingleTop = true } })
            }
            composable("queue") { QueueScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
