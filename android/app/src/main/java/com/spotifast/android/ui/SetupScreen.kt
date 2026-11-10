package com.spotifast.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spotifast.android.Spotifast
import com.spotifast.android.Status
import kotlinx.coroutines.launch

private val HEX = Regex("^[0-9a-fA-F]{32}$")

/** First run: register a personal Spotify app, then sign in with it (Spotifast's own flow). */
@Composable
fun SetupScreen(status: Status) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var clientId by rememberSaveable { mutableStateOf(status.clientId) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    val valid = HEX.matches(clientId.trim())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Window)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Spotifast", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = TextMain)
        Text("Spotify, native and fast. Now on your phone.", color = TextSecondary)

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connect your Spotify app", style = MaterialTheme.typography.titleMedium, color = TextMain, fontWeight = FontWeight.SemiBold)
            Text(
                "Like Spotifast on the desktop, you sign in through your own free Spotify app registration. It takes a minute, once.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Step(1, "Open Spotify's developer dashboard and tap Create app.")
            OutlinedButton(onClick = { openBrowser(context, "https://developer.spotify.com/dashboard") }) {
                Text("Open the dashboard")
            }
            Step(2, "Give it any name and description. Under Redirect URIs, add exactly:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.redirectUri,
                    fontFamily = FontFamily.Monospace,
                    color = Accent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(status.redirectUri))
                    Spotifast.showMessage("Redirect URI copied")
                }) { Text("Copy") }
            }
            Step(3, "Tick Web API, accept the terms and save.")
            Step(4, "Copy the Client ID from your app's page and paste it here.")
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim() },
                label = { Text("Client ID") },
                singleLine = true,
                isError = clientId.isNotEmpty() && !valid,
                supportingText = {
                    if (clientId.isNotEmpty() && !valid) Text("A Client ID is 32 letters and digits.")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        val url = Spotifast.beginWebLogin(clientId.trim())
                        if (url != null) {
                            waiting = true
                            openBrowser(context, url)
                        }
                    }
                },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (waiting) {
                    CircularProgressIndicator(color = OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("Waiting for Spotify… tap to retry", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Sign in with Spotify", fontWeight = FontWeight.SemiBold)
                }
            }
            if (waiting) {
                Text(
                    "If Spotify says \"Invalid redirect URI\", the address in step 2 doesn't match exactly.",
                    color = TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "Playing music in apps other than Spotify's own needs Spotify Premium.",
            color = TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row {
        Text("$number.", color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = TextMain, style = MaterialTheme.typography.bodyMedium)
    }
}
