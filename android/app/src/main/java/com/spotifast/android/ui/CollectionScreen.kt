package com.spotifast.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifast.android.Item
import com.spotifast.android.Spotifast
import com.spotifast.android.Status
import com.spotifast.android.Track
import com.spotifast.android.bestImage
import com.spotifast.android.objects

/** A playlist, album, artist or Liked Songs. */
@Composable
fun CollectionScreen(route: Route.Collection, status: Status, open: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val player by Spotifast.player.collectAsStateWithLifecycle()
    val playingUri = player.track?.uri
    val listState = rememberLazyListState()

    var image by remember { mutableStateOf(route.image) }
    var subtitle by remember { mutableStateOf("") }
    val context = when (route.kind) {
        "liked" -> "spotify:user:${status.accountId}:collection"
        else -> route.uri
    }

    val tracks: Paged<Track> = remember(route.key) {
        when (route.kind) {
            "liked" -> Paged(scope, "/v1/me/tracks?limit=50", itemOf = { Track.parse(it.optJSONObject("track")) })
            "album" -> Paged(scope, "/v1/albums/${route.id}/tracks?limit=50", itemOf = { Track.parse(it, fallbackImage = image) })
            "artist" -> Paged(
                scope,
                "/v1/artists/${route.id}/top-tracks",
                limit = 10,
                pageOf = { json -> json.also { it.put("items", it.optJSONArray("tracks")) } },
                itemOf = { Track.parse(it) },
            )
            // Playlist entries are `item` since 2026, `track` before.
            else -> Paged(
                scope,
                "/v1/playlists/${route.id}/items?limit=50&additional_types=track,episode",
                itemOf = { Track.parse(it.optJSONObject("item") ?: it.optJSONObject("track")) },
            )
        }
    }
    val releases = remember(route.key) { mutableStateListOf<Item>() }

    LaunchedEffect(route.key) {
        tracks.ensureLoaded()
        runCatching {
            when (route.kind) {
                "playlist" -> {
                    val json = Spotifast.api("/v1/playlists/${route.id}")
                    image = bestImage(json.optJSONArray("images")) ?: image
                    val owner = json.optJSONObject("owner")?.optString("display_name").orEmpty()
                    subtitle = listOf("Playlist", owner).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                "album" -> {
                    val json = Spotifast.api("/v1/albums/${route.id}")
                    image = bestImage(json.optJSONArray("images")) ?: image
                    val artists = json.optJSONArray("artists")?.objects()?.joinToString(", ") { it.optString("name") }.orEmpty()
                    subtitle = listOf(artists, json.optString("release_date").take(4)).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                "artist" -> {
                    val json = Spotifast.api("/v1/artists/${route.id}")
                    image = bestImage(json.optJSONArray("images")) ?: image
                    subtitle = "Artist"
                    val albums = Spotifast.api("/v1/artists/${route.id}/albums?include_groups=album,single&limit=10")
                    releases.clear()
                    albums.optJSONArray("items")?.objects()?.mapNotNull { Item.parse(it) }?.let { releases.addAll(it) }
                }
                "liked" -> subtitle = "Playlist"
            }
            Unit
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Cover(image, 200.dp, round = route.kind == "artist", corner = 10.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    route.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { Spotifast.playContext(context, tracks.items.firstOrNull(), shuffle = false, fallback = tracks.items.toList()) },
                        enabled = context.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { Spotifast.playContext(context, null, shuffle = true, fallback = tracks.items.shuffled()) },
                        enabled = context.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Shuffle")
                    }
                }
            }
        }
        if (route.kind == "artist" && tracks.items.isNotEmpty()) {
            item { SectionTitle("Popular") }
        }
        itemsIndexed(tracks.items, key = { index, track -> "$index:${track.uri}" }) { _, track ->
            TrackRow(track, current = track.uri == playingUri, showImage = route.kind != "album", open = open) {
                // Artist pages and some Liked Songs libraries can't be started as a
                // context through the Web API; the loaded list plays instead.
                Spotifast.playContext(context, track, fallback = tracks.items.toList())
            }
        }
        item {
            when {
                tracks.error != null -> CenterMessage(tracks.error ?: "")
                tracks.loading && tracks.items.isEmpty() -> CenterMessage("", loading = true)
                tracks.items.isEmpty() -> CenterMessage("No songs here.")
            }
        }
        if (releases.isNotEmpty()) {
            item { SectionTitle("Releases") }
            itemsIndexed(releases, key = { _, release -> "release:${release.id}" }) { _, release ->
                ItemRow(release.name, release.subtitle, release.image) {
                    open(Route.Collection(release.kind, release.id, release.uri, release.name, release.image))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
