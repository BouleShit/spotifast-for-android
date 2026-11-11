package com.spotifast.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.ViewGroup
import androidx.compose.material.icons.filled.Home
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.spotifast.android.Spotifast
import com.spotifast.android.WebPlayer

sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
    }

    data object Library : Route {
        override val key = "library"
    }

    data object Settings : Route {
        override val key = "settings"
    }

    data object Search : Route {
        override val key = "search"
    }

    data class Collection(
        val kind: String,
        val id: String,
        val uri: String,
        val title: String,
        val image: String?,
    ) : Route {
        override val key get() = "$kind:$id"
    }
}

@Composable
fun AppRoot() {
    val status by Spotifast.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        Spotifast.messages.collect { message ->
            if (message.isNotBlank()) snackbar.showSnackbar(message)
        }
    }
    Box(Modifier.fillMaxSize().background(Window)) {
        when {
            !status.loaded -> Unit
            !status.web -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Window) { padding ->
                Box(Modifier.padding(padding)) { SetupScreen(status) }
            }
            else -> MainScreen(snackbar)
        }
    }
}

@Composable
private fun MainScreen(snackbar: SnackbarHostState) {
    val status by Spotifast.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    // One back stack per tab, so switching tabs keeps your place.
    val stacks = remember {
        listOf(
            mutableStateListOf<Route>(Route.Home),
            mutableStateListOf<Route>(Route.Library),
            mutableStateListOf<Route>(Route.Search),
        )
    }
    val stack = stacks[tab]
    val current = stack.last()
    val holder = rememberSaveableStateHolder()
    val open: (Route) -> Unit = { route -> stack.add(route) }

    BackHandler(enabled = showPlayer || stack.size > 1 || tab != 0) {
        if (showPlayer) {
            showPlayer = false
        } else if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            tab = 0
        }
    }

    val webPlayerView by WebPlayer.view.collectAsStateWithLifecycle()
    // While the app is on screen, follow music playing on the account's
    // other devices, so it can be shown and controlled from here.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Spotifast.pollRemote()
                delay(if (Spotifast.remoteDevice.value != null) 5_000 else 10_000)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Spotify's web player, attached (one pixel, behind everything)
        // while the app is open; it keeps playing detached in the background.
        webPlayerView?.let { web ->
            key(web) {
                AndroidView(
                    factory = {
                        (web.parent as? ViewGroup)?.removeView(web)
                        web
                    },
                    modifier = Modifier.size(1.dp),
                )
            }
        }
        Scaffold(
            containerColor = Window,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column(Modifier.background(Window)) {
                    MiniPlayer(onOpen = { showPlayer = true })
                    NavigationBar(containerColor = Window) {
                        listOf("Home" to Icons.Filled.Home, "Library" to Icons.Filled.LibraryMusic, "Search" to Icons.Filled.Search)
                            .forEachIndexed { index, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = {
                                        // Tapping the open tab again goes back to its start.
                                        if (tab == index) {
                                            while (stacks[index].size > 1) stacks[index].removeAt(stacks[index].lastIndex)
                                        }
                                        tab = index
                                    },
                                    icon = { Icon(icon, contentDescription = null) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = TextMain,
                                        selectedTextColor = TextMain,
                                        indicatorColor = SurfaceActive,
                                        unselectedIconColor = TextDim,
                                        unselectedTextColor = TextDim,
                                    ),
                                )
                            }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                holder.SaveableStateProvider("$tab/${current.key}") {
                    when (current) {
                        Route.Home -> HomeScreen(status, open)
                        Route.Library -> LibraryScreen(status, open)
                        Route.Settings -> SettingsScreen(status)
                        Route.Search -> SearchScreen(open)
                        is Route.Collection -> CollectionScreen(current, status, open)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(onClose = { showPlayer = false })
        }
    }
}
