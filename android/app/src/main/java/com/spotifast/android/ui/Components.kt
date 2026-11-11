package com.spotifast.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.spotifast.android.ApiException
import com.spotifast.android.Spotifast
import com.spotifast.android.Track
import com.spotifast.android.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

fun openBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (error: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: ActivityNotFoundException) {
            Spotifast.showMessage("No browser found to sign in with.")
        }
    }
}

fun friendlyError(error: Throwable): String = when {
    error is ApiException && (error.status == 403 || error.status == 404) ->
        "Spotify doesn't let personal apps read this. (${error.status})"
    else -> error.message ?: "Something went wrong"
}

/** The wall clock, ticking four times a second while [running]. */
@Composable
fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        now = System.currentTimeMillis()
        while (running) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun Cover(url: String?, size: Dp, round: Boolean = false, corner: Dp = 6.dp) {
    val shape = if (round) CircleShape else RoundedCornerShape(corner)
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Surface),
    )
}

@Composable
fun TrackRow(
    track: Track,
    current: Boolean,
    showImage: Boolean,
    open: ((Route) -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showImage) {
            Cover(track.image, 48.dp)
        } else {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (track.number > 0) track.number.toString() else "",
                    color = if (current) Accent else TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                color = if (current) Accent else TextMain,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistLine,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(formatTime(track.durationMs), color = TextDim, style = MaterialTheme.typography.bodySmall)
        TrackMenu(track, open)
    }
}

/** Queue, like, and where the song comes from. */
@Composable
fun TrackMenu(track: Track, open: ((Route) -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val liked by Spotifast.liked.collectAsStateWithLifecycle()
    Box {
        IconButton(onClick = {
            expanded = true
            Spotifast.refreshLiked(track.uri)
        }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TextDim)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                expanded = false
                Spotifast.addToQueue(track)
            })
            val isLiked = liked[track.uri] == true
            DropdownMenuItem(
                text = { Text(if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs") },
                onClick = {
                    expanded = false
                    Spotifast.toggleLike(track.uri)
                },
            )
            if (open != null && track.albumUri.startsWith("spotify:album:")) {
                DropdownMenuItem(text = { Text("Go to album") }, onClick = {
                    expanded = false
                    open(Route.Collection("album", track.albumUri.substringAfterLast(':'), track.albumUri, track.albumName, track.image))
                })
            }
            val artist = track.artists.firstOrNull { it.uri.startsWith("spotify:artist:") }
            if (open != null && artist != null) {
                DropdownMenuItem(text = { Text("Go to ${artist.name}") }, onClick = {
                    expanded = false
                    open(Route.Collection("artist", artist.uri.substringAfterLast(':'), artist.uri, artist.name, null))
                })
            }
        }
    }
}

@Composable
fun ItemRow(title: String, subtitle: String, image: String?, round: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(image, 56.dp, round = round)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = TextMain,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
fun CenterMessage(text: String, loading: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        if (text.isNotEmpty()) Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A Web API list read page by page: the first page shows at once, the rest
 * follow in the background, up to [limit] items.
 */
class Paged<T>(
    private val scope: CoroutineScope,
    private val firstPath: String,
    private val limit: Int = 2000,
    /** The paging object inside an answer (the answer itself for most endpoints). */
    private val pageOf: (JSONObject) -> JSONObject? = { it },
    private val itemOf: (JSONObject) -> T?,
) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var total by mutableIntStateOf(0)
        private set

    private var started = false

    /** Loads the first time only. */
    fun ensureLoaded() {
        if (!started) load()
    }

    fun load(fresh: Boolean = false) {
        started = true
        scope.launch {
            loading = true
            error = null
            var path: String? = firstPath
            var first = true
            try {
                while (path != null && (first || items.size < limit)) {
                    val page = pageOf(Spotifast.api(path, fresh = fresh)) ?: break
                    val parsed = page.optJSONArray("items")?.let { array ->
                        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(itemOf) }
                    } ?: emptyList()
                    if (first) {
                        items.clear()
                        total = page.optInt("total", parsed.size)
                        first = false
                    }
                    items.addAll(parsed)
                    loading = false
                    path = if (page.isNull("next")) null else page.optString("next").ifEmpty { null }
                }
            } catch (failure: Throwable) {
                error = friendlyError(failure)
            } finally {
                loading = false
            }
        }
    }
}
