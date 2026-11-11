package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotifast.android.Spotifast
import com.spotifast.android.Status

private const val SOURCE = "https://github.com/BouleShit/spotifast-for-android"

@Composable
fun SettingsScreen(status: Status) {
    val context = LocalContext.current
    val webMode by Spotifast.webMode.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }
    if (showLog) DebugLogDialog(onDismiss = { showLog = false })
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextMain,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )

        Section("Account") {
            Text(status.displayName.ifEmpty { status.accountId }, color = TextMain, style = MaterialTheme.typography.bodyLarge)
            if (status.product.isNotEmpty()) {
                Text("Spotify ${status.product.replaceFirstChar { it.uppercase() }}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { Spotifast.signOut() }) { Text("Sign out") }
        }

        Section("Player") {
            Choice(
                selected = webMode,
                title = "Spotify's web player",
                detail = "Spotify's own player plays, as Soloist does on the desktop. Works with any Premium account.",
            ) { Spotifast.setWebMode(true) }
            Choice(
                selected = !webMode,
                title = "Built-in player",
                detail = "librespot, as in desktop Spotifast. Up to 320 kbps, but Spotify refuses it for many newer accounts.",
            ) { Spotifast.setWebMode(false) }
        }

        Section("Made for you and lyrics") {
            if (status.playbackSaved) {
                Text("Connected to your Spotify session.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                SessionCard()
            }
        }

        Section("Your Spotify app") {
            Label("Client ID")
            Text(status.clientId, color = TextMain, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Label("Redirect URI")
            Text(status.redirectUri, color = TextMain, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }

        Section("Help") {
            TextButton(onClick = { showLog = true }) { Text("Debug log", color = Accent) }
            TextButton(onClick = { openBrowser(context, "https://developer.spotify.com/dashboard") }) {
                Text("Spotify developer dashboard", color = Accent)
            }
        }

        Section("About") {
            Text("Spotifast for Android $version", color = TextMain, style = MaterialTheme.typography.bodyLarge)
            Text(
                "An unofficial Android port of Spotifast by crmne. Not affiliated with Spotify. MIT licensed.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { openBrowser(context, SOURCE) }) { Text("Source code", color = Accent) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextDim, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Accent),
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
