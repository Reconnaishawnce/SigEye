package com.sigeye.experiments.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.sigeye.core.CrashLog
import com.sigeye.core.DeviceBook
import com.sigeye.core.IgnoreList
import com.sigeye.core.evidence.Bundler
import com.sigeye.core.evidence.Evidence
import com.sigeye.core.Replay
import com.sigeye.core.MyDevices
import com.sigeye.core.SettingsStore
import com.sigeye.core.SweepExport
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.presence.Density
import com.sigeye.core.analysis.presence.Environment
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.CaptureStore
import com.sigeye.ui.BackupWarning
import com.sigeye.ui.Field
import com.sigeye.ui.Section
import java.util.Locale
import kotlinx.coroutines.delay
import java.io.File

private const val HUB_TAG = "settings-detect"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onMyDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val ignore = remember { IgnoreList.get(context) }
    val book = remember { DeviceBook.get(context) }

    val density by settings.density.collectAsStateWithLifecycle()
    val detected by settings.detected.collectAsStateWithLifecycle()
    val muted by ignore.addresses.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()
    val lists by book.lists.collectAsStateWithLifecycle()

    var showBackup by remember { mutableStateOf(false) }

    if (showBackup) {
        BackupWarning(onDismiss = { showBackup = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("← All experiments") }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "What applies everywhere. Anything that belongs to one experiment lives on " +
                "that experiment's screen, next to the reading it changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        MyDevicesSection(onOpen = onMyDevices)

        DensitySection(
            density = density,
            detected = detected,
            onChoose = { settings.setDensity(it, detected = false) },
            onDetect = { settings.setDensity(it, detected = true) },
        )

        Spacer(Modifier.height(16.dp))
        Text("Muted devices", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Dropped before they are counted, in every experiment. Your own earbuds, the " +
                "fridge, the beacon in the ceiling. Muting happens in Device Inspector.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (muted.isEmpty()) {
            Text(
                "Nothing is muted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            muted.sorted().forEach { address ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            book.nicknameOf(address)
                                ?: Vendors.byAddress(address)
                                ?: "Unnamed",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { ignore.remove(address) }) { Text("Unmute") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Lists", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Made in Device Inspector and used by Signal Watch, Persistent Tracking and " +
                "anything else that needs to know which devices you care about.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (lists.isEmpty()) {
            Text(
                "No lists yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            lists.forEach { list ->
                val count = notes.values.count { it.lists.contains(list) }
                Field(list, "$count device${if (count == 1) "" else "s"}")
            }
        }

        Spacer(Modifier.height(16.dp))
        Section(
            title = "What leaves this phone",
            summary = "Names and settings go to Google backup. Recordings do not.",
            emphasis = true,
        ) {
            Text(
                "This app records what devices around you are broadcasting, including " +
                    "things that identify them and where they were. All of it stays on " +
                    "this phone, apart from the names and settings Android's backup " +
                    "copies to your Google account.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showBackup = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Read the whole thing again")
            }
        }

        Spacer(Modifier.height(16.dp))
        EvidenceSection()

        CaptureSection()

        Spacer(Modifier.height(16.dp))
        CrashSection()

        Spacer(Modifier.height(10.dp))
        Section(
            title = "Where the rest of the settings are",
            summary = "On the experiment they belong to.",
        ) {
            Text(
                "A stride length means nothing outside Doppler Walk, and a burst threshold " +
                    "means nothing outside Train Spotter. Putting them in a list here would " +
                    "separate every number from the reading it changes, which is the one " +
                    "thing that makes it possible to tell whether changing it helped.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --------------------------------------------------------------------------- density

/**
 * The way into marking your own kit.
 *
 * Next to the mute list because they look similar and are not. Muting is a preference
 * about a screen; this is a fact about the world, and the follow experiments treat it as
 * one. See [com.sigeye.core.MyDevices].
 */
@Composable
private fun MyDevicesSection(onOpen: () -> Unit) {
    val context = LocalContext.current
    val mine = remember { MyDevices.get(context) }
    val owned by mine.devices.collectAsStateWithLifecycle()

    Section(
        title = "Your own devices (${owned.size})",
        summary = "What you are carrying, so the app stops counting it as a stranger.",
        emphasis = owned.isEmpty(),
    ) {
        Text(
            if (owned.isEmpty()) {
                "Nothing marked. Every count in the app currently includes your own watch, " +
                    "earbuds and car, and in a follow your own kit never drops out of the " +
                    "pool because it goes everywhere you go."
            } else {
                owned.joinToString(", ") { it.label }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(if (owned.isEmpty()) "Find my devices" else "Manage")
        }
    }
}

@Composable
private fun DensitySection(
    density: Density,
    detected: Boolean,
    onChoose: (Density) -> Unit,
    onDetect: (Density) -> Unit,
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf(0) }
    var elapsed by remember { mutableStateOf(0L) }
    var verdict by remember { mutableStateOf<Density?>(null) }
    val seen = remember { mutableSetOf<String>() }

    if (listening) {
        DisposableEffect(Unit) {
            BleScanHub.init(context)
            BleScanHub.acquire(HUB_TAG)
            onDispose { BleScanHub.release(HUB_TAG) }
        }
        LaunchedEffect(Unit) {
            seen.clear()
            heard = 0
            verdict = null
            val startedAt = System.currentTimeMillis()
            BleScanHub.adverts.collect { advert ->
                if (seen.add(advert.address)) heard = seen.size
                elapsed = System.currentTimeMillis() - startedAt
            }
        }
        LaunchedEffect(Unit) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(500)
                elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= Environment.MINIMUM_SAMPLE_MS) {
                    verdict = Environment.detect(seen.size, elapsed)
                    listening = false
                    verdict?.let(onDetect)
                    break
                }
            }
        }
    }

    Text("Surroundings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "How busy the app should assume it is here. This changes how long several " +
            "experiments wait before they are willing to say something - not what they " +
            "measure. No dB threshold, no path loss term and no specification constant is " +
            "touched by it.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    Density.entries.forEach { option ->
        val chosen = option == density
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onChoose(option) },
            colors = CardDefaults.cardColors(
                containerColor = if (chosen) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (chosen) {
                        Text(
                            if (detected) "measured" else "chosen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    option.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chosen) {
                    val tuning = Environment.tuningFor(option)
                    Spacer(Modifier.height(6.dp))
                    Field("Discovery baseline", "${tuning.baselineSeconds} s")
                    Field(
                        "Train Spotter burst",
                        String.format(
                            Locale.US,
                            "%.1fx baseline, at least %d devices",
                            tuning.burstMultiple,
                            tuning.minimumBurst,
                        ),
                    )
                    Field("Dwell Time resident", "${tuning.residentMinutes} min")
                    Field(
                        "Crowd Counter",
                        String.format(
                            Locale.US,
                            "%.1f devices per person",
                            tuning.devicesPerPerson,
                        ),
                    )
                    Field("Lists drop a device after", "${tuning.freshnessSeconds} s")
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    if (listening) {
        Text(
            Environment.describe(heard, elapsed),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = {
                (elapsed.toFloat() / Environment.MINIMUM_SAMPLE_MS).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Button(onClick = { listening = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Work it out by listening")
        }
        Text(
            "Counts how many distinct addresses arrive in three quarters of a minute. " +
                "Measures radio business rather than crowd size - a street where every " +
                "phone rotates produces more addresses than one where nothing does, at the " +
                "same number of people. For deciding how long to learn a room, that is the " +
                "quantity that matters anyway.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------------------- capture

/**
 * Recording the raw radio, and playing one back.
 *
 * The most useful thing in this screen and the least glamorous. Every experiment reads one
 * flow of advertisements and none of them cares where it came from, so a capture replayed
 * at a desk drives the whole app exactly as the room did - which turns "it looked wrong on
 * the train" from a thing nobody can chase into a file somebody can open.
 */
/**
 * Crash traces, which are written without asking and sent only when asked.
 *
 * This is the whole of the app's crash reporting, and the point of it is that there is no
 * network anywhere in it. A crash on somebody else's phone used to be invisible forever;
 * now there is a file, and they can decide whether to send it.
 */
@Composable
private fun CrashSection() {
    val context = LocalContext.current
    var reports by remember { mutableStateOf(CrashLog.reports(context)) }

    Section(
        title = "If it falls over",
        summary = if (reports.isEmpty()) {
            "Nothing has crashed on this phone."
        } else {
            "${reports.size} crash${if (reports.size == 1) "" else "es"} recorded."
        },
    ) {
        Text(
            "When the app crashes it writes the stack trace to a file on this phone. " +
                "Nothing is sent anywhere and there is no crash reporting service - that " +
                "would mean a network call at the worst possible moment, and this app does " +
                "not make network calls at all. The file sits here until you send it or " +
                "delete it.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (reports.isEmpty()) return@Section

        Spacer(Modifier.height(10.dp))
        reports.take(5).forEach { report ->
            Field(report.stamp(), report.headline)
        }
        if (reports.size > 5) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${reports.size - 5} older ones, kept up to ${CrashLog.KEEP}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "A trace names the line it crashed on, this phone's model and Android version, " +
                "and can contain a Bluetooth address if the crash happened somewhere " +
                "holding one. Read it before you send it - it is a text file and it is " +
                "meant to be read.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                CrashLog.bundle(context)?.let {
                    SweepExport.share(context, it, mime = "text/plain", title = "Send the crash log")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send them somewhere") }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                CrashLog.clear(context)
                reports = CrashLog.reports(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete them") }
    }
}

/**
 * One zip with everything saved in it, plus what the app was doing at the time.
 *
 * See [com.sigeye.core.evidence.Evidence]. The numbers were already exportable; what was
 * not was the conditions they were taken under, and a distance means nothing without the
 * path loss exponent it assumed.
 */
@Composable
private fun EvidenceSection() {
    val context = LocalContext.current
    var built by remember { mutableStateOf<String?>(null) }
    var pickedCapture by remember { mutableStateOf<File?>(null) }

    val captures = remember { Bundler.captures(context) }
    val pieces = remember(pickedCapture) { Bundler.gather(context, pickedCapture) }

    Section(
        title = "Export everything",
        summary = Evidence.summarize(pieces),
    ) {
        Text(
            "One zip holding every saved run, every sweep, and a note of what the app " +
                "was set to at the time. Each file is listed with a checksum, so anyone " +
                "you send it to can tell whether it has been edited since.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (captures.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Include a raw capture",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = pickedCapture == null,
                    onClick = { pickedCapture = null },
                    label = { Text("None") },
                )
                captures.take(4).forEach { capture ->
                    FilterChip(
                        selected = pickedCapture == capture.file,
                        onClick = { pickedCapture = capture.file },
                        label = { Text("${capture.packets} packets") },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val file = Bundler.build(context, pieces, Bundler.provenance(context))
                built = file?.name
                file?.let { SweepExport.share(context, it) }
            },
            enabled = Evidence.worthExporting(pieces),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Build and share") }

        built?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "Wrote $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptureSection() {
    val context = LocalContext.current
    val captures = remember { CaptureStore.get(context) }
    val recording by captures.recording.collectAsStateWithLifecycle()
    val packets by captures.packets.collectAsStateWithLifecycle()

    var saved by remember { mutableStateOf(captures.list()) }
    var replaying by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1.0) }

    Section(
        title = "Capture and replay",
        summary = if (recording) {
            "Recording - $packets packets so far."
        } else {
            "${saved.size} saved capture${if (saved.size == 1) "" else "s"}."
        },
    ) {
        Text(
            "Records every advertisement to a plain text file, and plays one back later as " +
                "though the radio were producing it. Every experiment reads the same flow " +
                "and none of them can tell the difference, so something odd seen on a train " +
                "can be looked at again at a desk - or sent to somebody else to look at.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Recording is a passenger: it never turns the radio on by itself. Start it " +
                "here, then open an experiment, or it will record nothing at all.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (recording) {
            Button(
                onClick = {
                    captures.stop()
                    BleScanHub.record(null)
                    saved = captures.list()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop recording ($packets packets)") }
        } else {
            Button(
                onClick = {
                    BleScanHub.init(context)
                    if (captures.start()) BleScanHub.record { captures.write(it) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start recording") }
        }

        replaying?.let { name ->
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Replaying $name",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "The live radio is muted while this runs, so what every experiment " +
                            "sees is the recording and nothing else. Open one now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            BleScanHub.stopReplay()
                            replaying = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop replaying") }
                }
            }
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Playback speed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Replay.SPEEDS.forEach { option ->
                    FilterChip(
                        selected = option == speed,
                        onClick = { speed = option },
                        enabled = replaying == null,
                        label = { Text(if (option == 1.0) "1x" else "${option.toInt()}x") },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Replay.describe(speed, saved.maxOfOrNull { it.spanMs } ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            saved.forEach { capture ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(capture.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            capture.describe(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !recording && replaying == null && capture.packets > 0,
                        onClick = {
                            BleScanHub.init(context)
                            val adverts = captures.read(capture.file, capture.savedAtMs)
                            replaying = capture.name
                            BleScanHub.startReplay(
                                adverts = adverts,
                                label = capture.name,
                                speed = speed,
                            ) { replaying = null }
                        },
                    ) { Text("Play") }
                    TextButton(
                        enabled = replaying == null,
                        onClick = {
                            SweepExport.share(context, capture.file)
                        },
                    ) { Text("Send") }
                }
            }
        }
    }
}
