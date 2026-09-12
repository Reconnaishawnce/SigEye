package com.sigeye.experiments.place

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.PlaceProfile
import com.sigeye.core.analysis.PlaceReport
import com.sigeye.core.analysis.Resident
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "place"
private const val TICK_MS = 5_000L

@Composable
fun PlaceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.PLACE, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To count what is around over hours rather than seconds.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Leave the phone plugged in. Nothing is transmitted.",
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

    var sliceMinutes by remember { mutableStateOf(10f) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(PlaceReport(emptyList(), emptyList(), 0, 0)) }
    val profile = remember { PlaceProfile() }
    var startedAtMs by remember { mutableStateOf(0L) }

    // The radio is held by this screen, so a sleeping display ends the measurement.
    KeepScreenOn(running)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            profile.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = notes[advert.address.uppercase(Locale.US)]?.nickname
                    ?: advert.name?.takeIf { it.isNotBlank() }
                    ?: advert.vendor,
            )
        }
    }

    LaunchedEffect(running) {
        while (running) {
            delay(TICK_MS)
            report = profile.report(System.currentTimeMillis())
        }
    }

    if (!running) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "What a place does, rather than what is in it",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Leave the phone somewhere for a few hours and the useful questions " +
                        "stop being what is here and become when is it busy, when does it " +
                        "empty out, and did anything that normally sits still start " +
                        "behaving differently. A shop, a car park, a corridor and a spare " +
                        "room all have completely different shapes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Slice length: ${sliceMinutes.roundToInt()} minutes",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = sliceMinutes,
            onValueChange = { sliceMinutes = it },
            valueRange = 1f..30f,
            steps = 28,
        )
        Text(
            "Everything is binned into slices this long. Ten minutes over four hours " +
                "gives twenty-four of them, which is enough to see a shape without every " +
                "slice being noise. Shorten it for a busy place watched briefly; lengthen " +
                "it for somewhere quiet watched overnight.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                startedAtMs = System.currentTimeMillis()
                profile.bucketMs = (sliceMinutes.roundToInt() * 60_000).toLong()
                profile.start(startedAtMs)
                report = profile.report(startedAtMs)
                running = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start recording") }
        Text(
            "This screen has to stay open, so plug the phone in and leave it. Closing the " +
                "app stops the radio.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
        return
    }

    val elapsed = System.currentTimeMillis() - startedAtMs
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Running", formatSpan(elapsed), "so far")
        Stat("Devices", "${report.totalDevices}", "ever heard")
        Stat("Fixtures", "${report.fixtures.size}", "here throughout")
    }

    Spacer(Modifier.height(14.dp))
    ActivityChart(report)

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "The shape of this place",
        verdict = report.verdict(),
        diagnostics = listOf(
            Diagnostic("Slices", "${report.buckets.size}", "recorded"),
            Diagnostic(
                "Typical",
                String.format(Locale.US, "%.0f", report.medianDevices),
                "devices at once",
            ),
            Diagnostic("Busiest", report.busiest?.let { "${it.devices}" } ?: "-", "devices"),
            Diagnostic("Quietest", report.quietest?.let { "${it.devices}" } ?: "-", "devices"),
            Diagnostic("Residents", "${report.residents.size}", "stayed a while"),
            Diagnostic("Moved", "${report.movers.size}", "fixtures that shifted"),
        ),
        footnote = "A fixture is something heard in most slices - part of the building " +
            "rather than traffic. Only those can meaningfully change, which is why a " +
            "fixture whose signal steps abruptly is the most interesting thing here.",
    )

    report.busiest?.let { busiest ->
        report.quietest?.let { quietest ->
            if (busiest.index != quietest.index) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Busiest and quietest",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Busiest at " + clockFor(busiest.startMs) + " with " +
                                busiest.devices + " devices. Quietest at " +
                                clockFor(quietest.startMs) + " with " + quietest.devices +
                                ".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        report.mostChurn?.takeIf { it.churn > 0 }?.let { churn ->
                            Text(
                                "Most coming and going at " + clockFor(churn.startMs) +
                                    ": " + churn.arrivals + " arrived, " +
                                    churn.departures + " left.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (report.movers.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Something that sits still changed",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                report.movers.take(6).forEach { mover ->
                    Text(
                        mover.label + " stepped " +
                            String.format(Locale.US, "%.0f dB", mover.shiftDb) +
                            (if (mover.shiftDb > 0) " stronger" else " weaker") +
                            " at " + clockFor(
                                report.buckets.getOrNull(mover.shiftedAtBucket ?: 0)
                                    ?.startMs ?: 0L,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "A step this size on something otherwise steady usually means it was " +
                        "moved, or that something large moved between it and the phone - " +
                        "a door, a vehicle, a person who stayed. Ordinary multipath " +
                        "wander is filtered out before anything reaches this list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    if (report.residents.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Residents",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        report.residents.take(25).forEach { ResidentRow(it) }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { running = false },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop recording") }
}

/**
 * Devices per slice, with arrivals and departures underneath.
 *
 * Deliberately two things at once: the height is how many were there, and the marks below
 * are how much changed. A car park is flat and high overnight; a corridor is spiky and
 * low. They look nothing alike, which is the point.
 */
@Composable
private fun ActivityChart(report: PlaceReport) {
    val bars = MaterialTheme.colorScheme.primary
    val churnColour = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Box(Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val buckets = report.buckets
            if (buckets.isEmpty()) return@Canvas

            val peak = (buckets.maxOfOrNull { it.devices } ?: 1).coerceAtLeast(1)
            val peakChurn = (buckets.maxOfOrNull { it.churn } ?: 1).coerceAtLeast(1)
            val barArea = size.height * 0.7f
            val churnArea = size.height * 0.25f
            val width = size.width / buckets.size
            val gap = (width * 0.15f).coerceAtMost(3f)

            repeat(3) { line ->
                val y = barArea * (line + 1) / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            buckets.forEachIndexed { index, bucket ->
                val height = barArea * bucket.devices / peak
                drawRect(
                    color = if (bucket.packets == 0) grid else bars,
                    topLeft = Offset(index * width + gap / 2f, barArea - height),
                    size = Size((width - gap).coerceAtLeast(1f), height),
                )
                if (bucket.churn > 0) {
                    val churnHeight = churnArea * bucket.churn / peakChurn
                    drawRect(
                        color = churnColour,
                        topLeft = Offset(index * width + gap / 2f, size.height - churnHeight),
                        size = Size((width - gap).coerceAtLeast(1f), churnHeight),
                    )
                }
            }
        }
    }
    Text(
        "Bars are how many devices were present in each slice. The marks along the bottom " +
            "are how much arrived or left. Empty slices are drawn faint - those are gaps " +
            "in the recording rather than quiet periods.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResidentRow(resident: Resident) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(
                resident.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (resident.fixture) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                resident.address,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${(resident.presenceFraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = if (resident.fixture) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                String.format(Locale.US, "%.0f dBm", resident.meanRssi),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun clockFor(atMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(atMs))

private fun formatSpan(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
