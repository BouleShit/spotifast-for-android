package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotifast.android.Item
import com.spotifast.android.Spotifast
import com.spotifast.android.Status
import com.spotifast.android.Track
import com.spotifast.android.objects
import kotlinx.coroutines.launch

/** A song from the listening history, with what it was played from. */
private data class Played(val track: Track, val context: String?)

@Composable
fun HomeScreen(status: Status, open: (Route) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var shelves by remember { mutableStateOf<Spotifast.HomeShelves?>(null) }
    var shelvesError by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<Played>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<Item>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(refresh, status.playbackSaved) {
        val fresh = refresh > 0
        launch {
            try {
                shelves = Spotifast.homeShelves(fresh)
                shelvesError = null
            } catch (error: Throwable) {
                shelvesError = error.message
            }
        }
        launch {
            runCatching {
                val json = Spotifast.api("/v1/me/player/recently-played?limit=30", fresh = fresh)
                recent = json.optJSONArray("items")?.objects()?.mapNotNull { item ->
                    Track.parse(item.optJSONObject("track"))?.let { Played(it, item.optJSONObject("context")?.optString("uri")) }
                }?.distinctBy { it.track.uri }.orEmpty()
            }
        }
        launch {
            runCatching {
                val json = Spotifast.api("/v1/me/top/artists?limit=12&time_range=short_term", fresh = fresh)
                topArtists = json.optJSONArray("items")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty()
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Home",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                }
            }
        }
        if (!status.playbackSaved) {
            item { SessionCard() }
        }
        val found = shelves
        if (found != null && found.madeForYou.isNotEmpty()) {
            item { Shelf("Made for you", found.madeForYou, open = open) }
        }
        if (found != null && found.topMixes.isNotEmpty()) {
            item { Shelf("Your top mixes", found.topMixes, open = open) }
        }
        if (found == null && status.playbackSaved && shelvesError != null) {
            item {
                Text(
                    "Made for you didn't load: $shelvesError",
                    color = TextDim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (recent.isNotEmpty()) {
            item {
                SectionTitle("Recently played")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(recent, key = { index, played -> "$index:${played.track.uri}" }) { _, played ->
                        ShelfCard(played.track.name, played.track.artistLine, played.track.image) {
                            val context = played.context?.takeIf {
                                it.startsWith("spotify:playlist:") || it.startsWith("spotify:album:") || it.startsWith("spotify:artist:")
                            } ?: played.track.albumUri
                            Spotifast.playContext(context, played.track, fallback = listOf(played.track))
                        }
                    }
                }
            }
        }
        if (topArtists.isNotEmpty()) {
            item { Shelf("Your top artists", topArtists, round = true, open = open) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Shelf(title: String, items: List<Item>, round: Boolean = false, open: (Route) -> Unit) {
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$index:${item.id}" }) { _, item ->
                ShelfCard(item.name, item.subtitle, item.image, round) {
                    open(Route.Collection(item.kind, item.id, item.uri, item.name, item.image))
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(title: String, subtitle: String, image: String?, round: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Cover(image, 140.dp, round = round, corner = 8.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Asks for the Spotify session that lyrics and Made for you need. */
@Composable
fun SessionCard() {
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
        Text("Made for you and lyrics", color = TextMain, fontWeight = FontWeight.SemiBold)
        Text(
            "Spotify asks once more, so Spotifast can read your mixes and song lyrics.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { scope.launch { Spotifast.beginPlaybackLogin()?.let { openBrowser(context, it) } } },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
        ) { Text("Allow") }
    }
}
