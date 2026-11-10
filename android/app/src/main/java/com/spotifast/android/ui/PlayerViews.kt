package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotifast.android.Spotifast
import com.spotifast.android.formatTime

/** The bar above the tabs: what's playing, play/pause, and progress. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val state by Spotifast.player.collectAsStateWithLifecycle()
    val track = state.track ?: return
    val now = rememberNow(state.playing)
    val duration = track.durationMs.coerceAtLeast(1)
    val fraction = (state.positionNow(now).toFloat() / duration).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(track.cover, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (!state.active && state.connected && !state.playing) "Paused · ${track.artistLine}" else track.artistLine,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { Spotifast.togglePlay() }) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = TextMain,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = TextMain,
            trackColor = SurfaceActive,
            drawStopIndicator = {},
            gapSize = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/** The full player. */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val state by Spotifast.player.collectAsStateWithLifecycle()
    val track = state.track
    val now = rememberNow(state.playing)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = (track?.durationMs ?: 0).coerceAtLeast(1)
    val position = dragging?.toLong() ?: state.positionNow(now)

    Column(
        Modifier
            .fillMaxSize()
            .background(Window)
            // Takes taps, so nothing behind the player reacts to them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close", tint = TextMain, modifier = Modifier.size(30.dp))
            }
            Text(
                if (state.active) "Playing on this phone" else "Spotify Connect",
                color = TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.weight(0.6f))
        AsyncImage(
            model = track?.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            track?.name ?: "Nothing playing",
            color = TextMain,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track?.artistLine.orEmpty(),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { Spotifast.seek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = track != null,
            colors = SliderDefaults.colors(
                thumbColor = TextMain,
                activeTrackColor = TextMain,
                inactiveTrackColor = SurfaceActive,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), color = TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(formatTime(track?.durationMs ?: 0), color = TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { Spotifast.setShuffle(!state.shuffle) }) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) Accent else TextSecondary)
            }
            IconButton(onClick = { Spotifast.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = TextMain, modifier = Modifier.size(36.dp))
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(TextMain)
                    .clickable { Spotifast.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = Window,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { Spotifast.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = TextMain, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { Spotifast.cycleRepeat() }) {
                Icon(
                    if (state.repeat == "track") Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeat == "off") TextSecondary else Accent,
                )
            }
        }
        if (!state.active && state.connected && track != null) {
            TextButton(onClick = { Spotifast.playHere() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Play on this phone", color = Accent)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
