package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifast.android.Device
import com.spotifast.android.Lyrics
import com.spotifast.android.Spotifast
import com.spotifast.android.Track
import com.spotifast.android.WebPlayer

enum class PlayerPanel(val title: String) { Lyrics("Lyrics"), Queue("Up next"), Devices("Play on") }

/** A panel over the player, with its title and a close button. */
@Composable
fun PanelFrame(panel: PlayerPanel, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Window)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close", tint = TextMain, modifier = Modifier.size(30.dp))
            }
            Text(panel.title, color = TextMain, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** The song's lyrics from Spotify, following the music when they are timed. */
@Composable
fun LyricsPanel() {
    val state by Spotifast.player.collectAsStateWithLifecycle()
    val status by Spotifast.status.collectAsStateWithLifecycle()
    val track = state.track
    var lyrics by remember(track?.uri) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(track?.uri) { mutableStateOf(true) }
    var problem by remember(track?.uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(track?.uri, status.playbackSaved) {
        val uri = track?.uri ?: return@LaunchedEffect
        loading = true
        try {
            lyrics = Spotifast.lyrics(uri)
            problem = null
        } catch (error: Throwable) {
            problem = error.message ?: "Couldn't load the lyrics"
        }
        loading = false
    }
    val now = rememberNow(state.playing)
    val current = lyrics
    val active = current?.activeLine(state.positionNow(now)) ?: -1
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    when {
        track == null -> CenterMessage("Nothing playing.")
        !status.playbackSaved -> SessionCard()
        loading -> CenterMessage("", loading = true)
        problem != null -> CenterMessage(problem ?: "")
        current == null || current.lines.isEmpty() -> CenterMessage("Spotify has no lyrics for this song.")
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(current.lines) { index, line ->
                val color = when {
                    !current.synced || index == active -> TextMain
                    index < active -> TextDim
                    else -> TextSecondary
                }
                Text(
                    line.text,
                    color = color,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = line.atMs != null) { line.atMs?.let { Spotifast.seek(it) } }
                        .padding(vertical = 8.dp),
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** What Spotify plays next. */
@Composable
fun QueuePanel() {
    val state by Spotifast.player.collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.track?.uri) {
        try {
            tracks = Spotifast.queue()
            problem = null
        } catch (error: Throwable) {
            problem = friendlyError(error)
        }
    }
    val list = tracks
    when {
        problem != null -> CenterMessage(problem ?: "")
        list == null -> CenterMessage("", loading = true)
        list.isEmpty() -> CenterMessage("Nothing queued.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { index, track -> "$index:${track.uri}" }) { _, track ->
                TrackRow(track, current = false, showImage = true) {}
            }
        }
    }
}

/** The account's Spotify Connect devices; choosing one moves the music there. */
@Composable
fun DevicesPanel(onChosen: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        try {
            devices = Spotifast.devices()
            problem = null
        } catch (error: Throwable) {
            problem = friendlyError(error)
        }
    }
    val list = devices
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Open Spotify on a computer, speaker or TV to see it here.",
                color = TextDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
            }
        }
        when {
            problem != null -> CenterMessage(problem ?: "")
            list == null -> CenterMessage("", loading = true)
            list.isEmpty() -> CenterMessage("No devices found.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { index, device -> "$index:${device.id}" }) { _, device ->
                    val thisPhone = device.id == WebPlayer.deviceId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                Spotifast.transferTo(device)
                                onChosen()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            deviceIcon(device.type),
                            contentDescription = null,
                            tint = if (device.active) Accent else TextSecondary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (thisPhone) "This phone" else device.name,
                                color = if (device.active) Accent else TextMain,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (device.active) {
                                Text("Playing", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun deviceIcon(type: String): ImageVector = when (type.lowercase()) {
    "smartphone", "tablet" -> Icons.Filled.Smartphone
    "computer" -> Icons.Filled.Computer
    "speaker", "avr", "audiodongle", "castaudio" -> Icons.Filled.Speaker
    "tv", "castvideo", "stb" -> Icons.Filled.Tv
    else -> Icons.Filled.Devices
}
