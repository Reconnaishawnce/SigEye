package com.sigeye.experiments.trainspotter

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.sigeye.core.analysis.TrainLog
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Sparkline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val alerting = state.lastAlertMs > 0 &&
            System.currentTimeMillis() - state.lastAlertMs < 60_000
        Text(
            text = state.currentCount.toString(),
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold,
            color = if (alerting) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "new devices this " + state.config.binSeconds + "s bin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Bins grouped into passes. A train takes longer than one bin, so the raw spike count
    // has never had much to do with the number of things that actually went by.
    val log = remember { TrainLog() }
    val passes = remember(state.bins) {
        log.reset()
        state.bins.forEach { log.add(it) }
        log.passes()
    }

    Spacer(Modifier.height(16.dp))
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
            Text(
                if (log.inProgress) "Something is going past now" else "Passes",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(log.summary(), style = MaterialTheme.typography.bodySmall)
            if (passes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                passes.takeLast(6).reversed().forEach { pass ->
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(pass.startMs)) +
                            "  ·  " + pass.describe(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "A pass is a run of consecutive bursts, so one train counts once however " +
                    "many bins it spans. A brief dip in the middle is tolerated - a gap " +
                    "between carriages should not become two trains.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Sparkline(bins = state.bins, baseline = state.baseline)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Last " + state.config.historyMinutes + " min. Dashed line is the usual rate; " +
            "red dots are bursts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("Active", state.activeUnique.toString(), "in " + state.config.windowMinutes + " min")
        Stat("Usual", format1(state.baseline), "new per bin")
        Stat("Rate", format1(state.advertsPerSecond), "ads per sec")
    }


    Spacer(Modifier.height(20.dp))
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (enrolling) {
                    "Noting what is already here"
                } else {
                    "Learning the normal rate"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (enrolling) {
                    "Every device in range looks new at the start, so nothing is counted " +
                        "yet. This is not a reading."
                } else {
                    "Counting for real now, measuring what a quiet minute looks like " +
                        "before anything can count as a burst."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.armingProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Alerts arm in " + clock(state.secondsUntilArmed),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.activeUnique.toString() + " devices logged",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
