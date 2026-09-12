package com.sigeye.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What backup means for an app that records other people.
 *
 * Android's automatic backup is on. That is a deliberate choice - it is convenient, and
 * losing a year of device names to a dropped phone is a real cost - but it is a choice
 * with a consequence that is nobody's default assumption, and the app claims on its own
 * home screen that everything stays on the phone.
 *
 * So it is said once, plainly, at the point where the user can still do something about
 * it, and it stays reachable afterwards. Not a consent wall: there is nothing to consent
 * to, because the alternative is a device-wide Android setting this app does not control.
 * It is a disclosure, and the distinction matters - pretending the user has a per-app
 * choice they do not have would be its own small lie.
 */
@Composable
fun BackupWarning(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "This app records other people's devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    "SigEye collects data about phones and other devices near you - what " +
                        "they broadcast, how strong they were, and when you saw them. " +
                        "Names you give devices are stored too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "All of it is kept on this phone, which protects your privacy and the " +
                        "privacy and whereabouts of the people around you.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Android's backup is currently on. That means a copy of the names and " +
                        "settings also goes to your Google account - where it is available " +
                        "to Google, to anyone Google shares it with, to any law " +
                        "enforcement request they answer, and to anyone who breaks into " +
                        "the account.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Recordings and exported files are never backed up - only names and " +
                        "settings. To stop even that, turn off backup in Android's own " +
                        "settings, under Google then Backup. It is a device-wide switch, " +
                        "so it covers every app at once.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I understand") }
        },
    )
}
