package com.sigeye.experiments.sweep

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
import androidx.compose.material3.CardDefaults
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
import com.sigeye.core.Clock
import com.sigeye.core.CsvExport
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.SweepStore
import com.sigeye.core.analysis.surveillance.Certainty
import com.sigeye.core.analysis.surveillance.Found
import com.sigeye.core.analysis.surveillance.Surveillance
import com.sigeye.core.analysis.surveillance.Sweep
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.sensors.Where
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HUB_TAG = "sweep"

/**
 * Walking or driving around finding surveillance cameras, and writing down where they were.
 *
 * The detection already existed and lived inside the device inspector, which is the wrong
 * shape for the job: finding cameras means covering ground and coming back with a list, not
 * scrolling a table of everything in range hoping to spot one.
 *
 * What makes the list worth keeping is the serial. A Flock camera battery broadcasts the
 * number printed on it, and that number does not change when its Bluetooth address does, so
 * the same pole next week updates the same row instead of becoming a second camera.
 *
 * Only surveillance hardware is ever written down. Everything else in range is heard and
 * discarded, because a file of every phone that passed a car is a record of people's
 * movements, and this exists to record where the cameras are.
 */
@Composable
fun SweepScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.SWEEP, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To hear the cameras. SigEye never connects to anything.",
                ),
                PermissionReason("Location", "To write down where each one was."),
            ),
            footnote = "Only surveillance hardware is saved. Nothing else is recorded.",
        ) {
            Hunt()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Hunt() {
    val context = LocalContext.current
    val store = remember { SweepStore.get(context) }
    val where = remember { Where(context) }

    val found by store.found.collectAsStateWithLifecycle()
    val fix by where.fix.collectAsStateWithLifecycle()
    var sweeping by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf(0) }

    if (sweeping) {
        DisposableEffect(Unit) {
            BleScanHub.init(context)
            BleScanHub.acquire(HUB_TAG)
            where.start()
            onDispose {
                where.stop()
                BleScanHub.release(HUB_TAG)
            }
        }

        LaunchedEffect(Unit) {
            BleScanHub.adverts.collect { advert ->
                val sighting = Surveillance.fromBle(advert) ?: return@collect
                heard++
                val key = Sweep.keyFor(sighting, advert.address)
                val here = where.fix.value
                store.put(
                    Sweep.fold(
                        existing = store.get(key),
                        sighting = sighting,
                        address = advert.address,
                        rssi = advert.rssi,
                        atMs = Clock.nowMs(),
                        latitude = here?.latitude,
                        longitude = here?.longitude,
                        accuracyM = here?.accuracyM,
                    ),
                )
            }
        }
    }

    Button(
        onClick = { sweeping = !sweeping },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (sweeping) "Stop" else "Start sweeping") }

    Spacer(Modifier.height(8.dp))
    Text(
        when {
            !sweeping -> Sweep.summarize(found.values)
            fix == null -> "Listening. Waiting for GPS."
            fix?.goodEnoughToMap == false -> "Listening. GPS is rough, so positions will be too."
            else -> "Listening at ${fix?.pretty()}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (found.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Found",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Sweep.ordered(found.values).forEach { one ->
            FoundRow(one, onForget = { store.forget(one.key) })
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    CsvExport.shareText(
                        context = context,
                        folder = "sweeps",
                        prefix = "sweep",
                        content = Sweep.csv(found.values),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export") }
            TextButton(onClick = { store.clear() }) { Text("Clear") }
        }
    }
}

@Composable
private fun FoundRow(one: Found, onForget: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (one.certainty == Certainty.CONFIRMED) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    one.product,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    one.certainty.label,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            one.serial?.let {
                Text(
                    "Serial $it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                if (one.hasPlace) {
                    "%.5f, %.5f".format(one.latitude, one.longitude) +
                        (one.accuracyM?.let { " ±${it.toInt()} m" } ?: "")
                } else {
                    "No location. GPS had not caught up."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Closest ${one.bestRssi} dBm · ${one.sightings} packets · " +
                    WHEN.format(Date(one.lastSeenMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!one.durable) {
                Text(
                    "No serial, so this one will show up again as a new row once its " +
                        "address changes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onForget) { Text("Remove") }
        }
    }
}

private val WHEN = SimpleDateFormat("d MMM HH:mm", Locale.US)
