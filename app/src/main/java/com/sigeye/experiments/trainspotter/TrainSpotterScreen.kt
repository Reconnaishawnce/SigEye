package com.sigeye.experiments.trainspotter

import com.sigeye.core.Clock
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.ScanService
import com.sigeye.core.analysis.presence.TrainLog
import com.sigeye.ui.CountUp
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.LiveBars
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.Sparkline
import com.sigeye.ui.TileColumn
import com.sigeye.ui.tile
import com.sigeye.ui.tiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun TrainSpotterScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.TRAIN_SPOTTER, onBack)
        Spacer(Modifier.height(20.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To listen for Bluetooth advertisements. SigEye never connects to anything.",
                ),
                PermissionReason(
                    "Location",
                    "Android treats an unfiltered Bluetooth scan as location-capable and " +
                        "returns zero results without it. Your location is never read or stored.",
                ),
                PermissionReason(
                    "Notifications",
                    "To show the ongoing scan and buzz you when a burst looks like a train.",
                ),
            ),
            footnote = "Nothing leaves the phone. There is no network code in this app.",
        ) {
            Monitor()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Monitor() {
    val context = LocalContext.current
    val state by PulseState.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    state.error?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    if (state.running && state.secondsUntilArmed > 0) {
        ArmingCard(state)
        Spacer(Modifier.height(16.dp))
    }

    if (state.starved) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = "Scan starved - the radio is delivering " +
                    format1(state.advertsPerSecond) + "/s against " +
                    format1(state.referenceRate) + "/s earlier. Restarting automatically.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    val countAndChart: @Composable () -> Unit = {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val alerting = state.lastAlertMs > 0 &&
            Clock.nowMs() - state.lastAlertMs < 60_000
        // Runs up to the new count rather than jumping to it. Forty new devices arriving
        // in one five second bin is a train, and it should look like one.
        CountUp(
            value = state.rollingNew,
            color = if (alerting || state.rollingSpike) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "new devices in the last " + state.config.binSeconds + " seconds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Straight under the number rather than below three sections of prose. The count
        // and the shape of the last half hour are one picture, and separating them meant
        // that the thing worth pointing a camera at was never on screen at once.
        Spacer(Modifier.height(12.dp))
        Sparkline(bins = state.bins, baseline = state.baseline)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Last " + state.config.historyMinutes + " min. Dashed line is the usual " +
                "rate; red dots are bursts.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }

    // Bins grouped into passes. A train takes longer than one bin, so the raw spike count
    // has never had much to do with the number of things that actually went by.
    val log = remember { TrainLog() }
    val passes = remember(state.bins) {
        log.reset()
        state.bins.forEach { log.add(it) }
        log.passes()
    }

    val passesCard: @Composable () -> Unit = {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (log.inProgress) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            // The headline answers the question the screen exists for, before any detail.
            Text(
                when {
                    log.inProgress -> "Something is going past now"
                    log.count == 0 -> "Nothing has gone past yet"
                    log.count == 1 -> "One pass"
                    else -> "${log.count} passes"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                log.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (passes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Section(
                    title = "Recent passes",
                    summary = "Last ${minOf(passes.size, 6)} of ${passes.size}",
                ) {
                    passes.takeLast(6).reversed().forEach { pass ->
                        Field(
                            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(pass.startMs)),
                            pass.describe(),
                        )
                    }
                }
                Section(
                    title = "How this is counted",
                    summary = "A run of bursts is one pass",
                ) {
                    Text(
                        "A train takes longer than one bin, so consecutive bursts are " +
                            "grouped - one train counts once however many bins it spans. " +
                            "A brief dip in the middle is tolerated, because a gap " +
                            "between carriages should not become two trains.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }


    }

    val stats: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Stat(
                "Active",
                state.activeUnique.toString(),
                "in " + state.config.windowMinutes + " min",
            )
            Stat("Usual", format1(state.baseline), "new per bin")
            Stat("Rate", format1(state.advertsPerSecond), "ads per sec")
        }
    }

    // Arranged how you want them, and remembered. The count and the chart are one tile
    // rather than two: they are a single picture and separating them would let somebody
    // put the chart somewhere the number is not.
    TileColumn(
        screen = Experiments.TRAIN_SPOTTER,
        tiles = tiles {
            tile("count", "Count and chart", countAndChart)
            tile("passes", "Passes", passesCard)
            tile("stats", "Numbers", stats)
        },
    )

    if (state.running) {
        Button(
            onClick = { ScanService.stop(context, ScanService.Mode.TRAIN_SPOTTER) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop scanning") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                ScanService.label(context)
                Toast.makeText(context, "Marked this bin as TRAIN", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Train now — mark this bin") }
    } else {
        Button(
            onClick = { ScanService.start(context, ScanService.Mode.TRAIN_SPOTTER) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start scanning") }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { shareCsv(context) },
            modifier = Modifier.weight(1f),
        ) { Text("Export CSV") }
        OutlinedButton(
            onClick = { showSettings = true },
            modifier = Modifier.weight(1f),
        ) { Text("Settings") }
    }

    state.csvPath?.let { path ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Logging to " + path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.scanRestarts > 0) {
        Text(
            text = "Scan auto-restarted " + state.scanRestarts + " time(s).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Days of recording, which also went into the CSV and nowhere else. The live chart
    // shows the last ten minutes; this shows what the place does, which is what leaving a
    // phone on a windowsill for a fortnight was for.
    Spacer(Modifier.height(12.dp))
    DayStripCard(state.config)

    // What the trains you marked say the threshold should be. The ground-truth button has
    // been here since this was written and the labels went into the CSV and nowhere else.
    Spacer(Modifier.height(12.dp))
    ThresholdCard(
        config = state.config,
        onApply = { factor ->
            val store = SettingsStore(context)
            store.save(store.load().copy(spikeFactor = factor))
            ScanService.reloadConfig(context)
        },
    )

    if (showSettings) {
        SettingsDialog(
            store = remember { SettingsStore(context) },
            onDismiss = { showSettings = false },
            onSaved = { ScanService.reloadConfig(context) },
        )
    }
}

/**
 * The opening stretch of a run, made legible.
 *
 * Out of the box the app used to look broken here: a huge first reading, then a long
 * silence with no explanation. Saying which stage it is in, and how long is left, is the
 * difference between "it is learning" and "it is not working".
 */
@Composable
private fun ArmingCard(state: ScanUiState) {
    val enrolling = state.phase == Phase.ENROLL

    // Ticked here rather than taken from the engine. Arming is counted in closed bins, so
    // the engine's own countdown moves once every several seconds and sits still in
    // between - which on screen reads as a stopped clock. This runs off the wall clock
    // against the same deadline, and the two cannot disagree by more than one bin.
    //
    // The rate is sampled on the same loop rather than keyed on the rate changing. Keying
    // on the value means a steady rate stops producing bars, so the one time the chart
    // would go still is the one time the radio is behaving.
    val latest by rememberUpdatedState(state)
    var nowMs by remember { mutableStateOf(Clock.nowMs()) }
    val found = remember(state.startedAtMs) { mutableStateListOf<Float>() }
    var lastSampleSeq by remember(state.startedAtMs) { mutableStateOf(0) }

    // Driven by the engine's own per-publish counts rather than by a timer of its own, so
    // every bar is a distinct measurement. The first version of this sampled the scan
    // hub's advertisements-per-second, which is recomputed once every ten seconds - so
    // twenty consecutive bars were the same number, which is why the chart looked like it
    // was not measuring anything. It was not.
    LaunchedEffect(state.sampleSeq) {
        nowMs = Clock.nowMs()
        if (state.sampleSeq > lastSampleSeq) {
            lastSampleSeq = state.sampleSeq
            found.add(state.sampleNew.toFloat())
            while (found.size > BASELINE_BARS) found.removeAt(0)
        }
    }

    // The clock, separately, because it has to move whether or not the radio is delivering.
    LaunchedEffect(state.startedAtMs) {
        while (true) {
            nowMs = Clock.nowMs()
            delay(SAMPLE_MS)
        }
    }

    val totalMs = (state.armsAtMs - state.startedAtMs).coerceAtLeast(1L)
    val elapsedMs = (nowMs - state.startedAtMs).coerceIn(0L, totalMs)
    // Never shows zero while it is still waiting. The bins decide when it is armed, not
    // this clock, and a countdown that reaches zero and then carries on is worse than one
    // that holds at one second.
    val remainingSeconds = (((state.armsAtMs - nowMs) + 999L) / 1000L)
        .coerceAtLeast(1L)
        .toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "ESTABLISHING A BASELINE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = clock(remainingSeconds) + " left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CountUp(value = state.activeUnique, fontSize = 72.sp)
                Text(
                    text = "devices logged so far",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            LiveBars(
                values = found.toList(),
                spoken = "Devices heard for the first time, twice a second. " +
                    "${found.sumOf { it.toInt() }} across the last ${found.size} readings.",
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Each bar is how many devices were heard for the first time in that " +
                    "half second. It starts tall and flattens off, and when it has " +
                    "flattened the census has found what is here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            // Smooth, because it is fed a wall clock rather than a bin count.
            val progress by animateFloatAsState(
                targetValue = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f),
                label = "baseline",
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (enrolling) {
                    "Writing down everything already in range. Every device looks new at " +
                        "the start, so none of it counts yet - otherwise the first bin " +
                        "would be the biggest burst of the day."
                } else {
                    "Now measuring what a quiet minute looks like here. A burst can only " +
                        "mean something once there is a normal to compare it against, and " +
                        "normal on a station platform is nothing like normal in a kitchen."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Enough bars to read as a shape, few enough that each one is still worth looking at. */
private const val BASELINE_BARS = 30

/** Matches how often the service publishes, so every bar is a fresh reading. */
private const val SAMPLE_MS = 500L

/** Seconds as m:ss, or plain seconds under a minute. */
private fun clock(seconds: Int): String {
    if (seconds < 60) return seconds.toString() + "s"
    val minutes = seconds / 60
    val rest = seconds % 60
    return minutes.toString() + ":" + rest.toString().padStart(2, '0')
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun shareCsv(context: Context) {
    val dir: File = context.getExternalFilesDir(null) ?: run {
        Toast.makeText(context, "External storage unavailable.", Toast.LENGTH_SHORT).show()
        return
    }
    val files = dir.listFiles { f -> f.name.startsWith("sigeye-") && f.name.endsWith(".csv") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) {
        Toast.makeText(context, "No logs yet. Start scanning first.", Toast.LENGTH_SHORT).show()
        return
    }

    val uris = ArrayList(
        files.map {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
        },
    )

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/csv"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Export SigEye logs"))
    }.onFailure {
        Toast.makeText(context, "No app available to receive the file.", Toast.LENGTH_SHORT).show()
    }
}
