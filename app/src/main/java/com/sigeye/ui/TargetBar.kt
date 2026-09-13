package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CurrentTarget
import com.sigeye.core.TargetStore

/** Where a pinned target can be taken. */
data class TargetRoutes(
    val onLocate: (String) -> Unit,
    val onRadar: (String) -> Unit,
    val onRotation: (String) -> Unit,
)

/**
 * The pinned target, on every screen, one tap from anywhere it is useful.
 *
 * Finding a device was the hard part and this app made it the easy part. A follow narrowed
 * a street to one phone and the only way to look at it anywhere else was to read the
 * address off the screen and type it into a filter box - for a value that changes every
 * fifteen minutes, in an app whose entire subject is that it changes.
 *
 * A thin bar rather than anything larger. It is above every screen in the app, so it has to
 * cost almost nothing when it is not what you are looking at, and vanish entirely when
 * nothing is pinned.
 */
@Composable
fun TargetBar(routes: TargetRoutes, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = remember { CurrentTarget.get(context) }
    val pinned by current.pinned.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    val held = pinned ?: return

    Surface(
        modifier.fillMaxWidth().clickable { open = true },
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    held.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildString {
                        append(held.address)
                        if (held.rotations > 0) append("  ·  ${held.rotations} rotations")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("Open", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (open) {
        val targets = remember { TargetStore.get(context) }

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(held.name) },
            text = {
                Column {
                    Text(
                        "Pinned from ${held.source}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (held.rotations > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Followed through ${held.rotations} address " +
                                (if (held.rotations == 1) "change" else "changes") +
                                " since it was pinned: " + held.addresses.joinToString(" → "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Route("Walk it down", "Locate, with the signal as a hot and cold game.") {
                        open = false
                        routes.onLocate(held.address)
                    }
                    Route("Watch it rotate", "Defeating Randomization, already tracking it.") {
                        open = false
                        routes.onRotation(held.address)
                    }
                    Route("Put it on the radar", "Proximity Radar, filtered to this one.") {
                        open = false
                        routes.onRadar(held.address)
                    }

                    Spacer(Modifier.height(12.dp))
                    // Keeping it is a different act from working on it. A pin is what you
                    // are looking at now and is meant to be replaced constantly; a target
                    // is a finding and is meant to be kept.
                    Route(
                        "Keep it as a target",
                        "Adds it to the Targets list and to the device book, which is what " +
                            "Signal Watch and Persistent Tracking read.",
                    ) {
                        targets.add(
                            com.sigeye.core.TargetDevice(
                                address = held.address,
                                label = held.label,
                                vendor = held.vendor,
                                evidence = "Pinned from ${held.source}",
                                fromFollow = held.source,
                                addedAtMs = held.pinnedAtMs,
                                addresses = held.addresses,
                            ),
                        )
                        open = false
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        current.clear()
                        open = false
                    },
                ) { Text("Unpin") }
            },
        )
    }
}

@Composable
private fun Route(title: String, blurb: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            blurb,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
