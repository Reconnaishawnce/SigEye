package com.sigeye.experiments.population

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.IgnoreList
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.DwellClass
import com.sigeye.core.analysis.PopulationConfig
import com.sigeye.core.analysis.PopulationSnapshot
import com.sigeye.core.analysis.PopulationTracker
import com.sigeye.core.analysis.TrackedDevice
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.DetailField
import com.sigeye.ui.DeviceActions
import com.sigeye.ui.NewListDialog
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PauseBar
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RadarTarget
import com.sigeye.ui.SignalRadar
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** The two readings of the same measurement. */
enum class PopulationMode { DWELL, CROWD }

private const val HUB_TAG = "population"
private const val REFRESH_MS = 700L

@Composable
fun PopulationScreen(
    mode: PopulationMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dwell = mode == PopulationMode.DWELL
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(
            if (dwell) Experiments.DWELL else Experiments.CROWD,
            onBack,
        )
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To count Bluetooth advertisements. SigEye never connects to anything.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it. Your location is never " +
                        "read or stored.",
                ),
            ),
            footnote = "Counts devices, not people, and never records who they belong to.",
        ) {
            Live(dwell)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live(dwell: Boolean) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val tracker = remember { PopulationTracker() }

    val health by BleScanHub.health.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()

    var snapshot by remember { mutableStateOf<PopulationSnapshot?>(null) }
    var rssiFloor by remember { mutableStateOf(-85f) }
    var perPerson by remember { mutableStateOf(2.0f) }
    var showSettings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showNewList by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val ignoreList = remember { IgnoreList.get(context) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            tracker.observe(
                address = advert.address,
                rssi = advert.rssi,
                nowMs = advert.atMs,
                isRandomAddress = advert.isRandomAddress,
                name = advert.name,
                companyId = advert.companyId,
            )
        }
    }

    LaunchedEffect(rssiFloor, perPerson) {
        tracker.config = PopulationConfig(
            rssiFloor = rssiFloor.roundToInt(),
            devicesPerPerson = perPerson.toDouble(),
        )
    }

    // Pausing freezes the rendered snapshot, never the recording, so a row can be tapped
    // without the list reordering underneath the finger.
    LaunchedEffect(paused) {
        while (!paused) {
            delay(REFRESH_MS)
            val now = System.currentTimeMillis()
            tracker.prune(now)
            snapshot = tracker.snapshot(now)
        }
    }

    val snap = snapshot
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

    if (snap == null) {
        Text(
            "Listening...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // The headline number.
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (dwell) {
                snap.devices.size.toString()
            } else {
                snap.estimatedPeople.roundToInt().toString()
            },
            fontSize = 88.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (dwell) "devices seen in the last hour" else "people, roughly",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!dwell) {
            Text(
                "${snap.presentNow} devices present · ${format1(perPerson.toDouble())} per person",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!dwell) {
        Spacer(Modifier.height(12.dp))
        val present = snap.devices
            .filter { it.isPresent(System.currentTimeMillis(), tracker.config) }
            .map { RadarTarget(it.address, it.lastRssi) }
        SignalRadar(targets = present)
        Text(
            "Rings are signal strength, strongest at the centre. Direction is not shown " +
                "because one antenna cannot know it - the angle is only there to keep " +
                "each device in its own spot.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("Passing", snap.passing.toString(), "under 2 min")
        Stat("Lingering", snap.lingering.toString(), "2 to 20 min")
        Stat("Resident", snap.resident.toString(), "over 20 min")
    }

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Read this sceptically",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${(snap.randomAddressShare * 100).roundToInt()}% of these addresses are " +
                    "randomised. Those phones mint a fresh address every fifteen minutes " +
                    "or so, which inflates the passing count and any long-window total. " +
                    if (dwell) {
                        "Resident counts are the trustworthy ones: a device that keeps " +
                            "one address for twenty minutes is fixed hardware."
                    } else {
                        "The headline uses only devices heard in the last minute, which " +
                            "keeps that inflation small but does not remove it."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Observed for " + formatDuration(snap.observedForMs) +
                    " · " + String.format(Locale.US, "%.0f", health.advertsPerSecond) + "/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { showSettings = !showSettings },
            modifier = Modifier.weight(1f),
        ) { Text(if (showSettings) "Hide settings" else "Settings") }
        OutlinedButton(
            onClick = { tracker.reset() },
            modifier = Modifier.weight(1f),
        ) { Text("Restart count") }
    }

    if (showSettings) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Only count above ${rssiFloor.roundToInt()} dBm",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = rssiFloor,
            onValueChange = { rssiFloor = it },
            valueRange = -100f..-40f,
            steps = 59,
        )
        Text(
            "This is what defines \"here\". Around -70 is a room; -85 is a building and " +
                "the street outside it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!dwell) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Devices per person: ${format1(perPerson.toDouble())}",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = perPerson,
                onValueChange = { perPerson = it },
                valueRange = 0.5f..5f,
                steps = 17,
            )
            Text(
                "Calibrate it: stand with a group you have counted and adjust until the " +
                    "estimate matches. Two is a reasonable start - a phone plus earbuds - " +
                    "but a café full of laptops and a quiet street differ a lot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (dwell) {
        Spacer(Modifier.height(18.dp))
        Text("Longest staying", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        PauseBar(
            paused = paused,
            onToggle = { paused = !paused },
            summary = "${snap.devices.size} tracked",
        )
        Spacer(Modifier.height(4.dp))

        val longest = snap.devices.sortedByDescending { it.dwellMs }.take(40)
        if (longest.isEmpty()) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(longest, key = { it.address }) { device ->
                DwellRow(
                    device = device,
                    nickname = notes[device.address.uppercase()]?.nickname,
                    muted = ignoreList.isIgnored(device.address),
                    config = tracker.config,
                    onClick = { selected = device.address },
                )
            }
        }
    }

    selected?.let { address ->
        snap.devices.firstOrNull { it.address == address }?.let { device ->
            DwellDetail(
                device = device,
                title = notes[address.uppercase()]?.nickname ?: device.fallbackName,
                config = tracker.config,
                onRequestNewList = { showNewList = true },
                onDismiss = { selected = null },
            )
        }
    }

    if (showNewList) {
        NewListDialog(
            onCreate = { book.createList(it) },
            onDismiss = { showNewList = false },
        )
    }
}

@Composable
private fun DwellRow(
    device: TrackedDevice,
    nickname: String?,
    muted: Boolean,
    config: PopulationConfig,
    onClick: () -> Unit,
) {
    val dwellClass = device.dwellClass(config)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                nickname ?: device.fallbackName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                device.address + if (device.isRandomAddress) "  (random)" else "",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The line that makes a row worth reading: who made it, how loud, how often.
            val tags = buildList {
                device.vendor?.let { add(it) }
                add("${device.lastRssi} dBm")
                add("${device.sightings}x")
                if (muted) add("muted")
            }
            Text(
                tags.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = if (muted) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(device.dwellMs), style = MaterialTheme.typography.bodySmall)
            Text(
                dwellClass.label,
                style = MaterialTheme.typography.labelSmall,
                color = when (dwellClass) {
                    DwellClass.RESIDENT -> MaterialTheme.colorScheme.tertiary
                    DwellClass.LINGERING -> MaterialTheme.colorScheme.primary
                    DwellClass.PASSING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun DwellDetail(
    device: TrackedDevice,
    title: String,
    config: PopulationConfig,
    onRequestNewList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceActions(
                    address = device.address,
                    displayName = device.fallbackName,
                    isRandomAddress = device.isRandomAddress,
                    onRequestNewList = onRequestNewList,
                )

                Spacer(Modifier.height(14.dp))
                DetailField(
                    "Address",
                    device.address + if (device.isRandomAddress) "  (random)" else "",
                )
                device.vendor?.let { DetailField("Vendor", it) }
                device.name?.let { DetailField("Advertised name", it) }
                device.companyId?.let {
                    DetailField(
                        "Company ID",
                        Vendors.companyIdHex(it) +
                            (Vendors.byCompanyId(it)?.let { n -> "  $n" } ?: ""),
                    )
                }
                DetailField(
                    "Dwell",
                    formatDuration(device.dwellMs) + "  (" +
                        device.dwellClass(config).label.lowercase(Locale.US) + ")",
                )
                DetailField("Sightings", device.sightings.toString())
                DetailField("Signal", "${device.lastRssi} dBm now, best ${device.bestRssi}")
                DetailField("First heard", formatDuration(
                    System.currentTimeMillis() - device.firstSeenMs) + " ago")
                DetailField("Last heard", formatDuration(
                    System.currentTimeMillis() - device.lastSeenMs) + " ago")

                Vendors.surveillanceNote(
                    device.address,
                    device.companyId,
                    device.name,
                )?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    device.dwellClass(config).blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
