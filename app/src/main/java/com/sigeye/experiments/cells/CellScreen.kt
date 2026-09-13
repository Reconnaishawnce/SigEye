package com.sigeye.experiments.cells

import android.Manifest
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sigeye.core.Experiments
import com.sigeye.core.cell.CellReader
import com.sigeye.core.cell.CellSample
import com.sigeye.core.cell.CellStats
import com.sigeye.core.cell.CellTracker
import com.sigeye.core.cell.Handover
import com.sigeye.ui.CountUp
import com.sigeye.ui.DetailField
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val POLL_MS = 3_000L

@Composable
fun CellScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.CELLS, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
            ),
            blocking = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            reasons = listOf(
                PermissionReason(
                    "Location",
                    "Android classes cell identity as location data, which it is - the " +
                        "tower you are on says roughly where you are. SigEye reads the " +
                        "identity only, never GPS.",
                ),
                PermissionReason(
                    "Phone state",
                    "Some phones withhold the cell identity without it. Optional - the " +
                        "experiment still runs, with blanks where the modem is coy.",
                ),
            ),
            footnote = "Nothing leaves the phone. No Bluetooth is used by this experiment.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val reader = remember { CellReader(context) }
    val tracker = remember { CellTracker() }

    var stats by remember { mutableStateOf<CellStats?>(null) }
    var blankReads by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val sample = reader.read(now)
            if (sample == null) blankReads++ else tracker.observe(sample)
            stats = tracker.stats(now)
            delay(POLL_MS)
        }
    }

    if (!reader.isAvailable) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                "No telephony on this device.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        return
    }

    val current = stats?.current
    if (current == null) {
        Text(
            if (blankReads > 2) {
                "The modem is not reporting a cell. That happens with no SIM, in " +
                    "aeroplane mode, or on phones that withhold identity without the " +
                    "phone state permission."
            } else {
                "Reading the modem..."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val snap = stats!!

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = snap.handovers.size, fontSize = 88.sp)
        Text(
            "handovers in " + formatDuration(snap.observedForMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snap.handoversPerHour?.let {
            Text(
                String.format(Locale.US, "%.1f per hour at this rate", it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Cells", snap.distinctCells.toString(), "distinct seen")
        Stat("On this one", formatDuration(snap.currentHeldMs), "so far")
        Stat("Neighbors", current.neighbors.toString(), "visible now")
    }

    Spacer(Modifier.height(16.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current.technology,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.fillMaxWidth(0.02f))
                current.dbm?.let {
                    Text(
                        "  $it dBm",
                        style = MaterialTheme.typography.titleMedium,
                        color = signalColour(current.level),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            current.operator?.let { DetailField("Operator (MCC+MNC)", it) }
            DetailField("Cell ID", current.cellId?.toString() ?: "withheld")
            DetailField("Area code", current.areaCode?.toString() ?: "withheld")
            DetailField("Physical cell ID", current.pci?.toString() ?: "withheld")
            DetailField("Channel", current.channel?.toString() ?: "withheld")
            DetailField("Bars", "${current.level} of 4")
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "What to look for",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Standing still, handovers should be rare. On a train they come every " +
                    "minute or two, and the count per hour is a rough measure of how " +
                    "dense the operator's network is along that line.\n\n" +
                    "The interesting case is a handover while signal was still strong - " +
                    "that is usually load balancing rather than you running out of " +
                    "coverage. Each row below shows the signal at the moment of the move.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { tracker.reset() }, modifier = Modifier.fillMaxWidth()) {
        Text("Restart count")
    }

    Spacer(Modifier.height(16.dp))
    Text("Handovers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))

    if (snap.handovers.isEmpty()) {
        Text(
            "None yet. Standing still, that is the expected answer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    snap.handovers.take(60).forEach { handover -> HandoverRow(handover, clock) }
}

@Composable
private fun HandoverRow(handover: Handover, clock: SimpleDateFormat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            clock.format(Date(handover.at.atMs)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                describe(handover.previous) + "  →  " + describe(handover.at),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            val detail = buildList {
                add("held " + formatDuration(handover.heldPreviousMs))
                handover.previous.dbm?.let { add("left at $it dBm") }
                if (handover.areaChanged) add("area changed")
                if (handover.previous.technology != handover.at.technology) {
                    add(handover.previous.technology + " to " + handover.at.technology)
                }
            }
            Text(
                detail.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = if (handover.areaChanged) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun describe(sample: CellSample): String =
    sample.cellId?.toString() ?: sample.pci?.let { "pci $it" } ?: "?"

@Composable
private fun signalColour(level: Int) = when {
    level >= 3 -> MaterialTheme.colorScheme.primary
    level == 2 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
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
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
