package com.spotifast.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifast.android.Item
import com.spotifast.android.Spotifast
import com.spotifast.android.Track
import com.spotifast.android.objects
import kotlinx.coroutines.delay
import java.net.URLEncoder

private data class Results(
    val tracks: List<Track> = emptyList(),
    val artists: List<Item> = emptyList(),
    val albums: List<Item> = emptyList(),
    val playlists: List<Item> = emptyList(),
)

@Composable
fun SearchScreen(open: (Route) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Results?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val player by Spotifast.player.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty()) {
            results = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        // Wait for a pause in typing; a newer query cancels this one.
        delay(250)
        loading = true
        try {
            val q = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            // Personal apps get ten results per type (Development Mode).
            val json = Spotifast.api("/v1/search?q=$q&type=track,artist,album,playlist&limit=10")
            results = Results(
                tracks = json.optJSONObject("tracks")?.optJSONArray("items")?.objects()?.mapNotNull { Track.parse(it) }.orEmpty(),
                artists = json.optJSONObject("artists")?.optJSONArray("items")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty(),
                albums = json.optJSONObject("albums")?.optJSONArray("items")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty(),
                playlists = json.optJSONObject("playlists")?.optJSONArray("items")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty(),
            )
            error = null
        } catch (failure: Throwable) {
            error = friendlyError(failure)
        } finally {
            loading = false
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Songs, artists, albums, playlists") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedContainerColor = Panel,
                    focusedContainerColor = Panel,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
        val found = results
        when {
            error != null -> item { CenterMessage(error ?: "") }
            found == null -> item { CenterMessage(if (loading) "" else "Find something to play.", loading = loading) }
            else -> {
                if (found.tracks.isNotEmpty()) {
                    item { SectionTitle("Songs") }
                    itemsIndexed(found.tracks, key = { index, track -> "t:$index:${track.uri}" }) { _, track ->
                        TrackRow(track, current = track.uri == player.track?.uri, showImage = true) {
                            // Play the song inside its album, so the next ones follow.
                            if (track.albumUri.isNotEmpty()) Spotifast.playContext(track.albumUri, track)
                            else Spotifast.playTracks(listOf(track), 0)
                        }
                    }
                }
                section("Artists", found.artists, open, round = true)
                section("Albums", found.albums, open)
                section("Playlists", found.playlists, open)
                if (found.tracks.isEmpty() && found.artists.isEmpty() && found.albums.isEmpty() && found.playlists.isEmpty()) {
                    item { CenterMessage("No results.") }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    list: List<Item>,
    open: (Route) -> Unit,
    round: Boolean = false,
) {
    if (list.isEmpty()) return
    item { SectionTitle(title) }
    itemsIndexed(list, key = { index, item -> "$title:$index:${item.id}" }) { _, item ->
        ItemRow(item.name, item.subtitle, item.image, round = round) {
            open(Route.Collection(item.kind, item.id, item.uri, item.name, item.image))
        }
    }
}
