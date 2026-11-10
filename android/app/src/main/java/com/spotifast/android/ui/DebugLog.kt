package com.spotifast.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifast.android.Native
import com.spotifast.android.Spotifast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The engine's recent log, to copy into a bug report. */
@Composable
fun DebugLogDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val vertical = rememberScrollState()
    LaunchedEffect(refresh) {
        text = withContext(Dispatchers.IO) { runCatching { Native.logs() }.getOrDefault("") }
            .ifEmpty { "Nothing logged yet." }
        vertical.scrollTo(vertical.maxValue)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug log") },
        text = {
            Box(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(vertical)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Spotifast.showMessage("Log copied")
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = Panel,
    )
}
