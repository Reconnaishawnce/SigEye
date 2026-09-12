package com.sigeye.experiments.forensics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.ForensicStore
import com.sigeye.core.Permissions
import com.sigeye.core.Recordings
import com.sigeye.core.ScanService
import com.sigeye.core.SnapshotStore
import com.sigeye.core.SweepExport
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.Behaviour
import com.sigeye.core.analysis.ForensicFilter
import com.sigeye.core.analysis.ForensicHistory
import com.sigeye.core.analysis.ForensicSort
import com.sigeye.core.analysis.Provenance
import com.sigeye.core.analysis.SavedSession
import com.sigeye.core.analysis.SessionDiff
import com.sigeye.core.analysis.Track
import com.sigeye.core.ble.BleScanHub
import com.sigeye.experiments.watchlist.MatchKind
import com.sigeye.experiments.watchlist.WatchStore
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

private const val HUB_TAG = "forensics"
private const val TICK_MS = 1_000L

@Composable
fun ForensicsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.FORENSICS, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To record everything advertising, for review afterwards.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted. The recording stays on this phone.",
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
    val notes by book.notes.collectAsStateWithLifecycle()
    // Owned by the service, not by this composition. Closing the screen no longer
    // ends the recording, and reopening it reattaches to whatever is running.
    val recorder = Recordings.forensics
    val store = remember { ForensicStore.get(context) }
    val snapshotStore = remember { SnapshotStore.get(context) }
    val watchStore = remember { WatchStore.get(context) }

    val saved by store.sessions.collectAsStateWithLifecycle()
    val snapshots by snapshotStore.snapshots.collectAsStateWithLifecycle()
    val watchRules by watchStore.rules.collectAsStateWithLifecycle()

    val activeModes by ScanService.activeModes.collectAsStateWithLifecycle()
    val recording = activeModes.contains(ScanService.Mode.FORENSICS)
    var reviewing by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var devices by remember { mutableStateOf(0) }
    var packets by remember { mutableStateOf(0) }
    var startedAtMs by remember { mutableStateOf(0L) }

    var hideFurniture by remember { mutableStateOf(false) }
    var hideKnown by remember { mutableStateOf(false) }
    var passesOnly by remember { mutableStateOf(false) }
    var fixedOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ForensicSort.ARRIVAL) }
    var selected by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var comparingTo by remember { mutableStateOf<SavedSession?>(null) }
    var newListFor by remember { mutableStateOf<String?>(null) }
    var onlyFamiliar by remember { mutableStateOf(false) }
    var onlyUnfamiliar by remember { mutableStateOf(false) }

    // Reattach to a recording that was already running when this screen opened.
    LaunchedEffect(recording) {
        if (recording && startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
    }

    // No claim on the radio from here: the service holds it while recording, and this
    // screen only ever reads what the recorder has already accumulated.
    DisposableEffect(Unit) {
        BleScanHub.init(context)
        onDispose { }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(TICK_MS)
            elapsedMs = System.currentTimeMillis() - startedAtMs
            devices = recorder.deviceCount
            packets = recorder.packetCount
        }
    }

    if (!recording && !reviewing) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Record now, look afterwards",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Watching a busy street live is hopeless - two hundred devices scroll " +
                        "past and the one that mattered is three screens up by the time " +
                        "you notice. So this records the lot and does the looking " +
                        "afterwards, when you know what you are looking for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                startedAtMs = System.currentTimeMillis()
                recorder.start(startedAtMs)
                elapsedMs = 0L
                selected = null
                exported = null
                ScanService.start(context, ScanService.Mode.FORENSICS)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start recording") }
        Text(
            "This keeps running with the screen off and the app closed - there is a " +
                "notification while it does, with a Stop button on it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }

    if (recording) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Elapsed", formatSpan(elapsedMs), "recording")
            Stat("Devices", "$devices", "seen")
            Stat("Packets", "$packets", "captured")
        }
        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                "Recording. This carries on with the screen off, so put the phone in a " +
                    "pocket and walk. Three minutes for a street, longer for a car park.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                ScanService.stop(context, ScanService.Mode.FORENSICS)
                recorder.stop(System.currentTimeMillis())
                reviewing = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop and review") }
        return
    }

    // ------------------------------------------------------------------ review

    val knownAddresses = remember(notes) { notes.keys }
    val watchedAddresses = remember(watchRules) {
        watchRules.filter { it.kind == MatchKind.ADDRESS }
            .map { it.value.uppercase(Locale.US) }
            .toSet()
    }
    val filter = ForensicFilter(
        hideFurniture = hideFurniture,
        hideKnown = hideKnown,
        passesOnly = passesOnly,
        fixedOnly = fixedOnly,
    )

    // Provenance for everything in the recording, worked out once rather than per row -
    // a busy street is hundreds of devices against dozens of saved recordings.
    val tracks = recorder.tracks()
    val provenances = remember(tracks.size, saved, snapshots, notes, watchRules) {
        tracks.associate { track ->
            val note = notes[track.address]
            track.address to ForensicHistory.provenance(
                address = track.address,
                sessions = saved,
                snapshots = snapshots,
                nickname = note?.nickname,
                lists = note?.lists.orEmpty(),
                watched = watchedAddresses.contains(track.address),
            )
        }
    }

    val shown = recorder.review(filter, sort, knownAddresses)
        .filter { track ->
            val familiar = provenances[track.address]?.familiar == true
            when {
                onlyFamiliar -> familiar
                onlyUnfamiliar -> !familiar
                else -> true
            }
        }
    val census = recorder.census()
    val familiarCount = provenances.values.count { it.familiar }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Recorded", formatSpan(recorder.spanMs), "of radio")
        Stat("Devices", "${recorder.deviceCount}", "in total")
        Stat("Showing", "${shown.size}", "after filters")
    }

    Spacer(Modifier.height(12.dp))
    Text("Narrow it down", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = hideFurniture,
            onClick = { hideFurniture = !hideFurniture },
            label = { Text("Hide unchanged", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = hideKnown,
            onClick = { hideKnown = !hideKnown },
            label = { Text("Hide known", style = MaterialTheme.typography.labelSmall) },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = passesOnly,
            onClick = { passesOnly = !passesOnly },
            label = { Text("Went past only", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = fixedOnly,
            onClick = { fixedOnly = !fixedOnly },
            label = { Text("Fixed address", style = MaterialTheme.typography.labelSmall) },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = onlyUnfamiliar,
            onClick = {
                onlyUnfamiliar = !onlyUnfamiliar
                if (onlyUnfamiliar) onlyFamiliar = false
            },
            label = { Text("Never seen before", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = onlyFamiliar,
            onClick = {
                onlyFamiliar = !onlyFamiliar
                if (onlyFamiliar) onlyUnfamiliar = false
            },
            label = { Text("Met before", style = MaterialTheme.typography.labelSmall) },
        )
    }
    Text(
        when {
            passesOnly -> "Only things that rose to a peak and fell away again. That is " +
                "the shape of something going past rather than arriving, and it is " +
                "usually the shortest useful list."
            hideKnown -> "Anything you have nicknamed or filed is hidden, so what is left " +
                "is what you have never named."
            hideFurniture -> "Anything present the whole time without its signal changing " +
                "is hidden. Usually most of the recording."
            onlyUnfamiliar -> "Only devices this app has never recorded, snapshotted or " +
                "had a name for. On familiar ground that is the interesting half; on a " +
                "street it is nearly everything."
            onlyFamiliar -> "Only devices with a history - seen in an earlier recording " +
                "or snapshot, named, listed or watched."
            else -> "Everything heard. Start subtracting."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(10.dp))
    Text("Order by", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ForensicSort.entries.forEach { option ->
            FilterChip(
                selected = sort == option,
                onClick = { sort = option },
                label = {
                    Text(option.label, style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What the recording contains",
        diagnostics = Behaviour.entries.map { behaviour ->
            Diagnostic(
                behaviour.label.take(14),
                "${census[behaviour] ?: 0}",
                "devices",
            )
        },
        footnote = "A pass is the most specific thing that can be said about a device, so " +
            "it is tested first. Anything unremarkable falls through to unchanged. " +
            "$familiarCount of ${tracks.size} devices here have a history in this app.",
    )

    if (shown.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing matches. Loosen a filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    shown.take(60).forEach { track ->
        Spacer(Modifier.height(8.dp))
        TrackCard(
            track = track,
            provenance = provenances[track.address],
            expanded = selected == track.address,
            onToggle = {
                selected = if (selected == track.address) null else track.address
            },
            onRequestNewList = { newListFor = track.address },
        )
    }
    if (shown.size > 60) {
        Text(
            "and ${shown.size - 60} more - narrow it further",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    comparingTo?.let { previous ->
        Spacer(Modifier.height(12.dp))
        ComparisonCard(ForensicHistory.diff(previous, tracks))
    }

    Spacer(Modifier.height(14.dp))
    Text("Keep and compare", style = MaterialTheme.typography.labelLarge)
    Text(
        "Saving keeps a summary of every device, so the next recording can ask what has " +
            "changed. The readings themselves are what the export is for.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Save this recording")
    }

    if (saved.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Compare against",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        saved.take(8).forEach { session ->
            val chosen = comparingTo?.recordedAtMs == session.recordedAtMs
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable { comparingTo = if (chosen) null else session },
                colors = CardDefaults.cardColors(
                    containerColor = if (chosen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(session.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            SimpleDateFormat("d MMM HH:mm", Locale.US)
                                .format(Date(session.recordedAtMs)) +
                                " \u00B7 ${session.size} devices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { store.delete(session) }) {
                        Text("Delete", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            val directory = File(context.getExternalFilesDir(null), "forensics")
            directory.mkdirs()
            val file = File(directory, "forensics-${recorder.spanMs / 1000}s.csv")
            runCatching { file.writeText(recorder.csv()) }
            exported = file.name
            SweepExport.share(context, file)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the whole recording") }
    exported?.let {
        Text(
            "Wrote $it - every reading with its device and behaviour.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { reviewing = false },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Record something else") }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this recording") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Where was this?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Every device in this recording becomes part of its history, so a " +
                            "later one can tell you which of them you had met before.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.save(
                        ForensicHistory.save(
                            label = label.ifBlank { "Unnamed" },
                            atMs = System.currentTimeMillis(),
                            spanMs = recorder.spanMs,
                            tracks = tracks,
                        ),
                    )
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel") }
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
}

/** New, gone and unchanged between this recording and a saved one. */
@Composable
private fun ComparisonCard(diff: SessionDiff) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Against \"" + diff.before.label + "\"",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                diff.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DiffSection("New since then", diff.onlyAfter.map { it.label to it.address })
            DiffSection("Gone since then", diff.onlyBefore.map { it.label to it.address })
            DiffSection("In both", diff.inBoth.map { it.label to it.address })
        }
    }
}

@Composable
private fun DiffSection(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(
        "$title (${rows.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    rows.take(15).forEach { (label, address) ->
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                address,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (rows.size > 15) {
        Text(
            "and ${rows.size - 15} more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackCard(
    track: Track,
    provenance: Provenance?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRequestNewList: () -> Unit,
) {
    val notable = track.behaviour == Behaviour.PASSED
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (notable) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
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
                        track.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        track.address + if (track.isRandom) "  (random)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${track.peakRssi}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "peak dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Timeline(track)

            Text(
                track.behaviour.label + " · " + track.summary(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            provenance?.let {
                Text(
                    it.headline(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (it.familiar) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        it.watched -> MaterialTheme.colorScheme.error
                        it.familiar -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    track.behaviour.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Detail("Vendor", track.vendor ?: "not in the registry")
                Detail("Packets", "${track.packets}")
                Detail("Signal", "${track.floorRssi} to ${track.peakRssi} dBm")
                Detail("Swing", "${track.rangeDb} dB")
                Detail("Mean", String.format(Locale.US, "%.1f dBm", track.meanRssi))
                Detail(
                    "Visible",
                    String.format(
                        Locale.US,
                        "%.0f%% to %.0f%% of the recording",
                        track.entryFraction * 100,
                        track.exitFraction * 100,
                    ),
                )
                Detail(
                    "Peak position",
                    String.format(
                        Locale.US,
                        "%.0f%% through its own visit",
                        track.peakFraction * 100,
                    ),
                )
                Detail("Rate", String.format(Locale.US, "%.1f/s", track.packetsPerSecond))
                Detail("Typical gap", "${track.medianGapMs} ms")
                Detail("Cadence", track.cadence.label)
                track.detail.companyId?.let {
                    Detail("Company ID", Vendors.companyIdHex(it) +
                        (Vendors.byCompanyId(it)?.let { name -> "  $name" } ?: ""))
                }
                track.detail.beaconProtocol?.let { Detail("Format", it) }
                track.detail.txPower?.let { Detail("TX power", "$it dBm") }
                if (track.detail.serviceUuids.isNotEmpty()) {
                    Detail("Services", "${track.detail.serviceUuids.size} advertised")
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    track.cadence.meaning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                track.detail.surveillanceNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                provenance?.encounters?.takeIf { it.isNotEmpty() }?.let { encounters ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Where this has been seen before",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    encounters.take(8).forEach { encounter ->
                        Text(
                            SimpleDateFormat("d MMM HH:mm", Locale.US)
                                .format(Date(encounter.atMs)) +
                                "  ·  " + encounter.where + "  ·  " + encounter.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                DeviceActions(
                    address = track.address,
                    displayName = track.label,
                    isRandomAddress = track.isRandom,
                    onRequestNewList = onRequestNewList,
                )
            } else {
                Text(
                    "Tap for detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Where in the recording it was heard, and how strong it was throughout.
 *
 * The bar is the whole recording; the filled part is when this device was audible, and the
 * line inside it is its signal. Reading a column of these down the screen is how you spot
 * the one that arrived halfway through and swelled in the middle.
 */
@Composable
private fun Timeline(track: Track) {
    val window = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val present = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val line = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxWidth().height(34.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = window, size = Size(size.width, size.height))
            if (track.pings.isEmpty()) return@Canvas

            val span = (track.recordingEndMs - track.recordingStartMs).coerceAtLeast(1L)
            val left = size.width * track.entryFraction
            val right = size.width * track.exitFraction
            drawRect(
                color = present,
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(2f), size.height),
            )

            val strongest = track.peakRssi.toFloat()
            val weakest = track.floorRssi.toFloat()
            // A floor on the range, so a dead-steady device draws a flat line rather than
            // a full-height zigzag of its own quantisation.
            val range = (strongest - weakest).coerceAtLeast(10f)

            val path = Path()
            track.pings.forEachIndexed { index, ping ->
                val x = size.width *
                    ((ping.atMs - track.recordingStartMs).toFloat() / span).coerceIn(0f, 1f)
                val y = size.height *
                    (1f - ((ping.rssi - weakest) / range)).coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = line, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
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

private fun formatSpan(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
