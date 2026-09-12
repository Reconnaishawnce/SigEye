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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.sigeye.core.Permissions
import com.sigeye.core.SweepExport
import com.sigeye.core.analysis.Behaviour
import com.sigeye.core.analysis.ForensicFilter
import com.sigeye.core.analysis.ForensicRecorder
import com.sigeye.core.analysis.ForensicSort
import com.sigeye.core.analysis.Track
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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
    val recorder = remember { ForensicRecorder() }

    var recording by remember { mutableStateOf(false) }
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

    // The measurement lives for as long as this screen does, so letting the display sleep
    // would end the recording without saying so.
    KeepScreenOn(recording)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            recorder.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = notes[advert.address.uppercase(Locale.US)]?.nickname
                    ?: advert.name?.takeIf { it.isNotBlank() }
                    ?: advert.vendor,
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
            )
        }
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
                recording = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start recording") }
        Text(
            "The screen stays awake while recording, so plug in for anything long.",
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
                "Recording. Leave it running for as long as the thing you are after might " +
                    "take to go past - three minutes for a street, longer for a car park.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                recorder.stop(System.currentTimeMillis())
                recording = false
                reviewing = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop and review") }
        return
    }

    // ------------------------------------------------------------------ review

    val knownAddresses = remember(notes) { notes.keys }
    val filter = ForensicFilter(
        hideFurniture = hideFurniture,
        hideKnown = hideKnown,
        passesOnly = passesOnly,
        fixedOnly = fixedOnly,
    )
    val shown = recorder.review(filter, sort, knownAddresses)
    val census = recorder.census()

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
    Text(
        when {
            passesOnly -> "Only things that rose to a peak and fell away again. That is " +
                "the shape of something going past rather than arriving, and it is " +
                "usually the shortest useful list."
            hideKnown -> "Anything you have nicknamed or filed is hidden, so what is left " +
                "is what you have never named."
            hideFurniture -> "Anything present the whole time without its signal changing " +
                "is hidden. Usually most of the recording."
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
            "it is tested first. Anything unremarkable falls through to unchanged.",
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
            expanded = selected == track.address,
            onToggle = {
                selected = if (selected == track.address) null else track.address
            },
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
}

@Composable
private fun TrackCard(track: Track, expanded: Boolean, onToggle: () -> Unit) {
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
