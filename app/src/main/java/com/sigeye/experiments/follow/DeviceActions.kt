package com.sigeye.experiments.follow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.FollowStore
import com.sigeye.core.TargetStore
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.ui.Field
import kotlin.math.roundToInt

/**
 * Everything you can do with a device once a follow has found it.
 *
 * This is the gap that made the experiment almost useful rather than useful: it would tell
 * you which phone in a street was the one, and then there was nothing to do about it. A
 * finding has to lead somewhere, and every one of these leads somewhere that already exists
 * - the work is handing the address over rather than making the person find it again in a
 * picker of forty.
 */
@Composable
fun DeviceActionsDialog(
    candidate: FollowCandidate,
    tuning: FollowTuning,
    onDismiss: () -> Unit,
    onKeep: () -> Unit,
    onMine: () -> Unit,
    onHold: () -> Unit,
    onRadar: () -> Unit,
    onRotation: () -> Unit,
    onLocate: () -> Unit,
) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val targetStore = remember { TargetStore.get(context) }
    val followStore = remember { FollowStore.get(context) }

    val targets by targetStore.targets.collectAsStateWithLifecycle()
    val followed by followStore.lists.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()

    var stopping by remember { mutableStateOf(false) }
    val note = notes[candidate.address.uppercase()]
    val isTarget = targets.any { it.address.equals(candidate.address, true) }
    val trackedLists = note?.lists.orEmpty().filter { followed.contains(it) }

    if (stopping) {
        StopTrackingDialog(
            listName = trackedLists.firstOrNull() ?: TargetStore.LIST,
            onDismiss = { stopping = false },
            onStop = {
                trackedLists.forEach { followStore.toggle(it) }
                stopping = false
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.label ?: candidate.vendor ?: "This device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    candidate.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                Field("Address", if (candidate.isRandom) "random, rotates" else "fixed")
                candidate.vendor?.let { Field("Made by", it) }
                Field("Signal now", "${candidate.recentRssi.roundToInt()} dBm")
                Field("Average", "${candidate.meanRssi.roundToInt()} dBm")
                if (candidate.spreadDb < 1_000) {
                    Field("Wandered", "${candidate.spreadDb.roundToInt()} dB")
                }
                Field("Packets", "${candidate.packets}")
                Field("With you for", "${candidate.heldForMs(candidate.lastSeenMs) / 60_000} min")
                if (candidate.rotations > 0) {
                    Field("Address changes", "${candidate.rotations} followed across")
                }
                if (candidate.arrived) Field("Arrived", "after the baseline")
                candidate.orbit?.let { Field("Circle", it.describe()) }
                candidate.walkBy?.let { Field("Walk-by", it.describe()) }

                candidate.carriedReason(tuning)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Probably yours: $it",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                if (trackedLists.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Being followed across address changes on " +
                            trackedLists.joinToString(", "),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // The one that stays on. Persistent Tracking works on device-book lists, so
                // locking a device in means putting it on one and turning that list on -
                // and then it is carried across address changes by the same machinery that
                // does it for everything else, running whether this screen is open or not.
                val tracking = trackedLists.isNotEmpty()
                Button(
                    onClick = {
                        if (tracking) {
                            stopping = true
                        } else {
                            book.createList(TargetStore.LIST)
                            if (!book.listsOf(candidate.address).contains(TargetStore.LIST)) {
                                book.toggleList(candidate.address, TargetStore.LIST)
                            }
                            if (!followStore.isFollowed(TargetStore.LIST)) {
                                followStore.toggle(TargetStore.LIST)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (tracking) {
                            "Stop following it across changes"
                        } else {
                            "Keep following it across address changes"
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (tracking) {
                        "On until you turn it off, including with this screen closed."
                    } else {
                        "Puts it on the ${TargetStore.LIST} list and follows that list " +
                            "through every address change until you say otherwise. This is " +
                            "the one that keeps going on its own."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onHold, modifier = Modifier.fillMaxWidth()) {
                    Text("Hold onto it here")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Watches this one inside the follow, with a countdown to its next " +
                        "address change and an alert if it comes back after going quiet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth()) {
                    Text(if (note?.nickname.isNullOrBlank()) "Name it and list it" else "Lists")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRadar, modifier = Modifier.fillMaxWidth()) {
                    Text("Watch it on the radar")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
                    Text("Walk towards it")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRotation, modifier = Modifier.fillMaxWidth()) {
                    Text("Watch it change address")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Defeating Randomization follows it through its own rotations and tells " +
                        "you whether it worked, which is the whole demonstration.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onMine, modifier = Modifier.fillMaxWidth()) {
                    Text("This one is mine, ignore it")
                }
                if (isTarget) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Already a target.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Turning persistent tracking off, with a question first.
 *
 * Tracking that stays on until it is deliberately turned off is the point of it - a follow
 * that ends when the screen closes is not tracking, it is watching. The confirmation is
 * there because the opposite failure is the expensive one: somebody taps the wrong thing,
 * the trail goes cold, and the device is a stranger again within the quarter of an hour.
 */
@Composable
fun StopTrackingDialog(listName: String, onDismiss: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop following $listName?") },
        text = {
            Text(
                "Nothing on this list will be carried across its next address change. A " +
                    "phone changes address roughly every quarter of an hour, so after that " +
                    "it is a stranger again and there is no way back to it.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onStop) { Text("Stop following") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep following") } },
    )
}
