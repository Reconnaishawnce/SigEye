package com.sigeye.experiments.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.JourneyStore
import com.sigeye.core.Permissions
import com.sigeye.core.Recordings
import com.sigeye.core.SavedJourney
import com.sigeye.core.ScanService
import com.sigeye.core.SnapshotStore
import com.sigeye.core.SweepExport
import com.sigeye.core.analysis.presence.ConvoyReport
import com.sigeye.core.analysis.presence.FollowConfidence
import com.sigeye.core.analysis.presence.Follower
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.AlertPicker
import com.sigeye.ui.DeviceActions
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.NewListDialog
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val HUB_TAG = "convoy"
private const val TICK_MS = 3_000L

/** The list devices get filed into when you mark them as your own. */
private const val MINE_LIST = "Mine"

@Composable
fun ConvoyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.CONVOY, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To compare what is around you in one place against another.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted, and no positions are recorded - only which " +
                "devices were audible in each leg.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val feedback = remember { Feedback(context) }
    // Service-owned: a journey is hours long and the screen will not survive it.
    val tracker = Recordings.convoy
    val journeys = remember { JourneyStore.get(context) }
    val snapshotStore = remember { SnapshotStore.get(context) }

    val notes by book.notes.collectAsStateWithLifecycle()
    val saved by journeys.journeys.collectAsStateWithLifecycle()
    val snapshots by snapshotStore.snapshots.collectAsStateWithLifecycle()

    val activeModes by ScanService.activeModes.collectAsStateWithLifecycle()
    val recording = activeModes.contains(ScanService.Mode.CONVOY)
    var report by remember { mutableStateOf(ConvoyReport(emptyList(), emptyList(), 0)) }
    var naming by remember { mutableStateOf(false) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BUZZ) }
    var announced by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var savingJourney by remember { mutableStateOf(false) }
    var pickingSnapshot by remember { mutableStateOf(false) }
    var newListFor by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }

    val mine = remember(notes) {
        notes.filterValues { it.lists.contains(MINE_LIST) }.keys
    }


    // A journey outlives the screen showing it, so the legs are read back on open and
    // written after every change. Losing an hour of walking to a locked phone made the
    // experiment useless for the only thing it is for.
    LaunchedEffect(Unit) {
        if (!restored) {
            val previous = journeys.loadCurrent()
            if (previous.isNotEmpty()) tracker.restore(previous)
            restored = true
            report = tracker.report(mine = mine)
        }
    }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        onDispose { feedback.release() }
    }


    LaunchedEffect(recording, mine) {
        while (true) {
            delay(TICK_MS)
            val fresh = tracker.report(mine = mine)
            report = fresh
            // Alert only on the strongest rating, and only once each. Anything looser
            // fires constantly on a journey and stops meaning anything.
            if (fresh.verdict() == null) {
                val newStrong = fresh.strong.filter { it.address !in announced }
                if (newStrong.isNotEmpty()) {
                    announced = announced + newStrong.map { it.address }
                    feedback.alert(alertStyle, urgent = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ intro

    if (tracker.legCount == 0) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Has anything come with you?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Record a leg in one place, another somewhere else, a third somewhere " +
                        "else again. Anything appearing in all of them was travelling " +
                        "rather than living in any of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Before you start: file your own earbuds, watch and car under a list " +
                        "called \"$MINE_LIST\" in Device Inspector. They follow you " +
                        "perfectly, and without that the list is forty rows of your own " +
                        "belongings.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        AlertPicker(
            style = alertStyle,
            onStyle = { alertStyle = it },
            feedback = feedback,
            title = "Alert on a companion",
            note = "Fires once, and only for something present in every leg over a real " +
                "span of time.",
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Start the first leg")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Legs", "${tracker.legCount}", "recorded")
            Stat("Devices", "${report.devicesSeen}", "heard anywhere")
            Stat("Candidates", "${report.candidates.size}", "in more than one")
        }

        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (recording) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (recording) {
                        "Recording leg ${tracker.legCount}: ${tracker.currentLabel}"
                    } else {
                        "Between legs - nothing is being recorded"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (recording) {
                        "Give it a few minutes so everything nearby has a chance to speak, " +
                            "then finish the leg before you move on. This keeps running " +
                            "with the app closed."
                    } else {
                        "Travel somewhere genuinely different, then start the next leg."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (recording) {
            Button(
                onClick = {
                    ScanService.stop(context, ScanService.Mode.CONVOY)
                    tracker.closeLeg(System.currentTimeMillis())
                    report = tracker.report(mine = mine)
                    journeys.keepCurrent(tracker.export())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish this leg") }
        } else {
            Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Start leg ${tracker.legCount + 1}")
            }
        }

        Spacer(Modifier.height(12.dp))
        DiagnosticsPanel(
            title = "How much this is worth",
            verdict = report.verdict(),
            diagnostics = listOf(
                Diagnostic("Legs", "${report.legs.size}", "closed"),
                Diagnostic(
                    "Span",
                    "${report.totalSpan() / 60_000L}m",
                    "first to last",
                ),
                Diagnostic("Devices", "${report.devicesSeen}", "in total"),
                Diagnostic("Shared", "${report.followers.size}", "in 2+ legs"),
                Diagnostic("Yours", "${mine.size}", "filed as yours"),
                Diagnostic("Flagged", "${report.strong.size}", "in every leg"),
            ),
            footnote = "Two legs recorded close together share most of their contents " +
                "simply because they overlap, so this refuses to draw conclusions until " +
                "there is real time and distance between them. Randomised addresses " +
                "defeat it outright: a phone following you appears as a stranger in every " +
                "leg. What it can catch is fitted equipment, tyre sensors and cheap " +
                "trackers - things whose address never changes.",
        )

        if (report.candidates.isEmpty() && report.legs.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Nothing with a fixed address turned up in more than one leg. That is " +
                        "the answer you want, and it is a partial one - anything rotating " +
                        "its address could not have been caught this way.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        report.candidates.forEach { follower ->
            Spacer(Modifier.height(8.dp))
            FollowerCard(
                follower = follower,
                legs = report.legs.size,
                expanded = expanded == follower.address,
                onToggle = {
                    expanded = if (expanded == follower.address) null else follower.address
                },
                onMine = {
                    book.createList(MINE_LIST)
                    book.toggleList(follower.address, MINE_LIST)
                },
                onRequestNewList = { newListFor = follower.address },
            )
        }

        if (snapshots.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { pickingSnapshot = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add a leg from a saved place") }
            Text(
                "A snapshot taken at home last week is a better first leg than one " +
                    "recorded five minutes ago in this street - the difficulty with this " +
                    "experiment is getting real separation between legs, and a snapshot " +
                    "from another day has it for free.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (tracker.legCount >= 2) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { savingJourney = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save this journey") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    val directory = File(context.getExternalFilesDir(null), "journeys")
                    directory.mkdirs()
                    val file = File(directory, "journey-${System.currentTimeMillis()}.csv")
                    runCatching { file.writeText(tracker.csv()) }
                    exported = file.name
                    SweepExport.share(context, file)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export this journey") }
            exported?.let {
                Text(
                    "Wrote $it - every device in every leg.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Saved journeys", style = MaterialTheme.typography.labelLarge)
            saved.take(6).forEach { journey ->
                Card(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(journey.label, style = MaterialTheme.typography.bodySmall)
                            Text(
                                SimpleDateFormat("d MMM HH:mm", Locale.US)
                                    .format(Date(journey.savedAtMs)) +
                                    " \u00B7 ${journey.legCount} legs, " +
                                    "${journey.deviceCount} devices",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            TextButton(onClick = {
                                ScanService.stop(context, ScanService.Mode.CONVOY)
                                tracker.restore(journey.legs)
                                announced = emptySet()
                                report = tracker.report(mine = mine)
                                journeys.keepCurrent(tracker.export())
                            }) {
                                Text("Open", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { journeys.delete(journey) }) {
                                Text("Delete", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                ScanService.stop(context, ScanService.Mode.CONVOY)
                tracker.reset()
                announced = emptySet()
                report = tracker.report(mine = mine)
                journeys.clearCurrent()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start a new journey") }
    }

    if (savingJourney) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savingJourney = false },
            title = { Text("Name this journey") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("What was this trip?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keeps every leg and everything heard in it, so it can be reopened " +
                            "or exported later.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    journeys.save(
                        SavedJourney(
                            label = label.ifBlank { "Unnamed journey" },
                            savedAtMs = System.currentTimeMillis(),
                            legs = tracker.export(),
                        ),
                    )
                    savingJourney = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { savingJourney = false }) { Text("Cancel") }
            },
        )
    }

    if (pickingSnapshot) {
        AlertDialog(
            onDismissRequest = { pickingSnapshot = false },
            title = { Text("Use a saved place as a leg") },
            text = {
                Column {
                    Text(
                        "Everything in the snapshot counts as having been heard in that " +
                            "leg. Its timestamps come from when the snapshot was taken, " +
                            "which is what gives the journey its separation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    snapshots.take(8).forEach { snapshot ->
                        Text(
                            snapshot.label + "  (" + snapshot.size + " devices)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    tracker.addLegFromSnapshot(
                                        label = snapshot.label,
                                        atMs = snapshot.takenAtMs,
                                        devices = snapshot.devices,
                                    )
                                    ScanService.stop(
                                        context,
                                        ScanService.Mode.CONVOY,
                                    )
                                    report = tracker.report(mine = mine)
                                    journeys.keepCurrent(tracker.export())
                                    pickingSnapshot = false
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingSnapshot = false }) { Text("Cancel") }
            },
        )
    }

    newListFor?.let { address ->
        NewListDialog(
            onCreate = {
                book.createList(it)
                book.toggleList(address, it)
            },
            onDismiss = { newListFor = null },
        )
    }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Where are you?") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name this leg") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only a label for you - no position is recorded. Legs need to be " +
                            "genuinely different places, far enough apart that the same " +
                            "devices would not be audible in both by accident.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tracker.startLeg(
                        label.ifBlank { "Leg ${tracker.legCount + 1}" },
                        System.currentTimeMillis(),
                    )
                    ScanService.start(context, ScanService.Mode.CONVOY)
                    naming = false
                    journeys.keepCurrent(tracker.export())
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FollowerCard(
    follower: Follower,
    legs: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMine: () -> Unit,
    onRequestNewList: () -> Unit,
) {
    val strong = follower.confidence == FollowConfidence.STRONG
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = when (follower.confidence) {
                FollowConfidence.STRONG -> MaterialTheme.colorScheme.errorContainer
                FollowConfidence.NOTABLE -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        follower.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        follower.address + if (follower.isRandom) "  (random)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${follower.legsShared}/$legs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "legs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                follower.confidence.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                follower.reasons().forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                follower.vendor?.let {
                    Text(
                        "Vendor: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    String.format(
                        Locale.US,
                        "Strongest reading %d dBm, across %.0f minutes.",
                        follower.bestRssi,
                        follower.spanMs / 60_000.0,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onMine) {
                    Text(
                        "This is mine - stop flagging it",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                DeviceActions(
                    address = follower.address,
                    displayName = follower.label,
                    isRandomAddress = follower.isRandom,
                    onRequestNewList = onRequestNewList,
                )
            } else {
                Text(
                    "Tap for why",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
