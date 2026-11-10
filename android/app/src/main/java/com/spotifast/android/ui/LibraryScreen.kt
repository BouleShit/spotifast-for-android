package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotifast.android.Item
import com.spotifast.android.Spotifast
import com.spotifast.android.Status
import kotlinx.coroutines.launch

private val FILTERS = listOf("Playlists", "Albums", "Artists")

@Composable
fun LibraryScreen(status: Status, open: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val playlists = remember { Paged(scope, "/v1/me/playlists?limit=50", itemOf = { Item.parse(it) }) }
    val albums = remember {
        Paged(scope, "/v1/me/albums?limit=50", itemOf = { Item.parse(it.optJSONObject("album")) })
    }
    val artists = remember {
        Paged(
            scope,
            "/v1/me/following?type=artist&limit=50",
            pageOf = { it.optJSONObject("artists") },
            itemOf = { Item.parse(it) },
        )
    }
    val current = when (filter) {
        1 -> albums
        2 -> artists
        else -> playlists
    }
    // Albums and artists load the first time their chip is chosen.
    LaunchedEffect(filter) { current.ensureLoaded() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { current.load(fresh = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Account", tint = TextSecondary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Signed in as ${status.displayName.ifEmpty { status.accountId }}", color = TextSecondary) },
                            onClick = { menu = false },
                            enabled = false,
                        )
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                            menu = false
                            Spotifast.signOut()
                        })
                    }
                }
            }
        }
        item { PlaybackCard(status) }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FILTERS.forEachIndexed { index, label ->
                    FilterChip(
                        selected = filter == index,
                        onClick = { filter = index },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = OnAccent,
                        ),
                    )
                }
            }
        }
        if (filter == 0) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(Route.Collection("liked", "liked", "", "Liked Songs", null)) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceActive),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, tint = Accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Liked Songs", color = TextMain, style = MaterialTheme.typography.bodyLarge)
                        Text("Playlist", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        itemsIndexed(current.items, key = { index, item -> "${item.kind}:$index:${item.id}" }) { _, item ->
            ItemRow(item.name, item.subtitle, item.image, round = item.kind == "artist") {
                open(Route.Collection(item.kind, item.id, item.uri, item.name, item.image))
            }
        }
        item {
            when {
                current.error != null -> CenterMessage(current.error ?: "")
                current.loading && current.items.isEmpty() -> CenterMessage("", loading = true)
                current.items.isEmpty() -> CenterMessage("Nothing here yet.")
            }
        }
    }
}

/** Sets up or reconnects librespot playback on this phone. */
@Composable
private fun PlaybackCard(status: Status) {
    if (status.playback) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            status.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Connecting playback…", color = TextMain)
                    if (status.connectingStep.isNotEmpty()) {
                        Text(
                            status.connectingStep.replaceFirstChar { it.uppercase() },
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            status.playbackSaved -> {
                Text("Playback is disconnected", color = TextMain, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { Spotifast.reconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                ) { Text("Reconnect") }
            }
            else -> {
                Text("Play music on this phone", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(
                    "Spotify asks once more, to allow streaming. After that, this phone also shows up as a Spotify Connect device.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.product.isNotEmpty() && status.product != "premium") {
                    Text(
                        "This account isn't Premium, and Spotify only allows playback in other apps with Premium.",
                        color = Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            Spotifast.beginPlaybackLogin()?.let { openBrowser(context, it) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                ) { Text("Turn on playback") }
            }
        }
    }
}
