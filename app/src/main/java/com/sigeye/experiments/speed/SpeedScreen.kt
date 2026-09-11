package com.sigeye.experiments.speed

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.PassQuality
import com.sigeye.core.analysis.PassResult
import com.sigeye.core.analysis.PassSample
import com.sigeye.core.analysis.SpeedEstimator
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "speed"
private const val TICK_MS = 1_000L

/** A device is considered gone, and its track analysed, after this long unheard. */
private const val PASS_TIMEOUT_MS = 6_000L
private const val TRACK_MAX_MS = 90_000L

private enum class Units(val label: String) { KMH("km/h"), MPH("mph"), MPS("m/s") }

@Composable
fun SpeedScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.SPEED, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To follow the signal of something going past. SigEye never connects.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it.",
                ),
            ),
            footnote = "The speed comes from geometry you supply - how far away the track " +
                "is. It is only ever as good as that number.",
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

    val notes by book.notes.collectAsStateWithLifecycle()
    val health by BleScanHub.health.collectAsStateWithLifecycle()

    val tracks = remember { mutableMapOf<String, MutableList<PassSample>>() }

    var watching by remember { mutableStateOf(false) }
    var distance by remember { mutableStateOf(15f) }
    var pathLoss by remember { mutableStateOf(2.0f) }
    var units by remember { mutableStateOf(Units.KMH) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BUZZ) }
    var passes by remember { mutableStateOf<List<PassResult>>(emptyList()) }
    var tracking by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            feedback.release()
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(watching) {
        if (!watching) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            val samples = tracks.getOrPut(advert.address) { mutableListOf() }
            samples.add(PassSample(advert.atMs, advert.rssi))
            // A device that has been around for ages is furniture, not a pass.
            if (samples.size > 2 && advert.atMs - samples.first().atMs > TRACK_MAX_MS) {
                samples.clear()
                samples.add(PassSample(advert.atMs, advert.rssi))
            }
        }
    }

    // A pass is only recognisable once it has finished, so the work happens when a device
    // stops being heard rather than while it is still going by.
    LaunchedEffect(watching, distance, pathLoss) {
        while (watching) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val finished = tracks.filter {
                it.value.isNotEmpty() && now - it.value.last().atMs > PASS_TIMEOUT_MS
            }
            finished.forEach { (address, samples) ->
                val result = SpeedEstimator.analyse(
                    address = address,
                    samples = samples.toList(),
                    distanceMetres = distance.toDouble(),
                    pathLossExponent = pathLoss.toDouble(),
                )
                tracks.remove(address)
                if (result.quality != PassQuality.REJECTED) {
                    passes = (listOf(result) + passes).take(40)
                    feedback.alert(alertStyle)
                }
            }
            tracking = tracks.count { it.value.size >= 3 }
        }
    }

    health.error?.let { message ->
        Card(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                message,
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    val latest = passes.firstOrNull()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            latest?.let { format(it, units) } ?: "—",
            fontSize = 76.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (latest == null) "no pass yet" else units.label + " · last pass",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Units.entries.forEach { option ->
            FilterChip(
                selected = units == option,
                onClick = { units = option },
                label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Watching", if (watching) "$tracking" else "off", "devices in flight")
        Stat("Passes", passes.size.toString(), "recorded")
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = {
            watching = !watching
            if (!watching) tracks.clear()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (watching) "Stop watching" else "Watch for passes") }

    Spacer(Modifier.height(16.dp))
    Text(
        "Distance to the track: ${distance.roundToInt()} m",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Slider(value = distance, onValueChange = { distance = it }, valueRange = 3f..80f, steps = 76)
    Text(
        "The one number that sets the scale. Pace it out or measure it on a map - the " +
            "speed is directly proportional, so guessing twice the distance reports twice " +
            "the speed. Perpendicular distance from where the phone sits to the middle of " +
            "the track.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    Text(
        String.format(Locale.US, "Path loss exponent: %.1f", pathLoss),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Slider(value = pathLoss, onValueChange = { pathLoss = it }, valueRange = 1.8f..3.5f, steps = 16)
    Text(
        "2.0 for a clear view of the line, which is the usual case outdoors. Raise it " +
            "toward 3 if there is a fence, a hedge or a cutting in the way. Run the Path " +
            "Loss experiment from the same spot to measure it properly.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    Text("Alert on a pass", style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AlertStyle.entries.forEach { style ->
            FilterChip(
                selected = alertStyle == style,
                onClick = { alertStyle = style },
                label = { Text(style.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    Text(
        alertStyle.hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "How this works",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A device going past is loudest when it draws level with you. Six dB below " +
                    "that peak is a known greater range, and a known range at a known " +
                    "distance from the track is a right-angled triangle - so the length of " +
                    "track between the two crossings falls out, and dividing by the time " +
                    "between them gives speed.\n\n" +
                    "A pass is only recognisable once it is over, so results appear a few " +
                    "seconds after the train has gone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (passes.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Passes", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { passes = emptyList() }) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))

        val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        passes.forEach { pass ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(
                    clock.format(Date(pass.peakAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        notes[pass.address.uppercase()]?.nickname
                            ?: Vendors.byAddress(pass.address)
                            ?: pass.address,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "peak %d dBm · %.1fs across · %d reads%s",
                            pass.peakRssi,
                            (pass.crossingMs ?: 0L) / 1000.0,
                            pass.samples,
                            if (pass.quality == PassQuality.ROUGH) " · rough" else "",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pass.quality == PassQuality.ROUGH) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    format(pass, units),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun format(pass: PassResult, units: Units): String {
    val value = when (units) {
        Units.KMH -> pass.kmh
        Units.MPH -> pass.mph
        Units.MPS -> pass.speedMetresPerSecond
    } ?: return "—"
    // No decimals: the inputs do not justify them.
    return value.roundToInt().toString()
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
