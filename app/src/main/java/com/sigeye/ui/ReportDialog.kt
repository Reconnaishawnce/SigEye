package com.sigeye.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dialog a shake opens: one optional box to type in, and a Send button.
 *
 * Kept to almost nothing on purpose. Somebody whose app just froze will send a report if it
 * costs two taps and will not if it costs a form. Everything useful is gathered automatically.
 */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val recent = remember { Diagnostics.recentFreeze() }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Report a slowdown?") },
        text = {
            Column {
                recent?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What were you doing? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Attaches the freezes SigEye noticed, which screen you were on, how busy " +
                        "the scan was, memory, battery and the app's recent log. Nothing about " +
                        "the devices around you is included.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { Diagnostics.build(note) }
                        val sent = Diagnostics.send(context, note, text)
                        if (!sent) {
                            Toast.makeText(context, "No app on this phone can send email.", Toast.LENGTH_LONG).show()
                        }
                        sending = false
                        onDismiss()
                    }
                },
            ) { Text(if (sending) "Gathering..." else "Send") }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Not now") }
        },
    )
}
