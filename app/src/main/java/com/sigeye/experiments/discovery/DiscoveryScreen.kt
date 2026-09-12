package com.sigeye.experiments.discovery

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.SettingsStore
import com.sigeye.core.SnapshotStore
import com.sigeye.core.analysis.Arrival
import com.sigeye.core.analysis.DiscoveryEngine
import com.sigeye.core.analysis.DiscoveryStage
import com.sigeye.core.analysis.Snapshot
import com.sigeye.core.analysis.SnapshotDiff
import com.sigeye.core.analysis.Snapshots
import com.sigeye.core.analysis.Trend
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.AlertPicker
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.radar.RadarPanel
import com.sigeye.ui.radar.RadarTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "discovery"
private const val TICK_MS = 700L

private enum class Tab(val label: String) { WATCH("Watch"), SNAPSHOTS("Snapshots") }

@Composable
fun DiscoveryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.DISCOVERY, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To learn what is normally around you and notice what is not.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted. Snapshots stay on this phone.",
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
    val store = remember { SnapshotStore.get(context) }
    val feedback = remember { Feedback(context) }
    val engine = remember { DiscoveryEngine() }

    val notes by book.notes.collectAsStateWithLifecycle()
    val saved by store.snapshots.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.WATCH) }
    var stage by remember { mutableStateOf(DiscoveryStage.IDLE) }
    // Thirty seconds suits a suburban street and is far too short on a concourse, where
    // slow beacons are still arriving for the first time two minutes in and would be
    // announced as arrivals afterwards. Seeded from the surroundings profile; still a
    // slider, because the profile is a guess and the user may know better.
    var baselineSeconds by remember {
        mutableStateOf(SettingsStore.get(context).tuning.baselineSeconds.toFloat())
    }
    var progress by remember { mutableStateOf(0f) }
    var arrivals by remember { mutableStateOf<List<Arrival>>(emptyList()) }
    var baselineSize by remember { mutableStateOf(0) }
    var totalSeen by remember { mutableStateOf(0) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BUZZ) }
    var announced by remember { mutableStateOf<Set<String>>(emptySet()) }
    var naming by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hideRotations by remember { mutableStateOf(true) }
    var hideRandom by remember { mutableStateOf(false) }
    var showRadar by remember { mutableStateOf(true) }
    var targets by remember { mutableStateOf<List<RadarTarget>>(emptyList()) }
    var seeding by remember { mutableStateOf(false) }
    var selectedBlip by remember { mutableStateOf<String?>(null) }

    // The radio is held by this screen, so a sleeping display ends the measurement.
    KeepScreenOn(stage != DiscoveryStage.IDLE)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            feedback.release()
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            engine.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                name = advert.name,
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
            )
        }
    }

    LaunchedEffect(stage) {
        while (stage != DiscoveryStage.IDLE) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            engine.tick(now)
            stage = engine.stage
            progress = engine.baselineProgress(now)
            baselineSize = engine.baselineSize
            totalSeen = engine.everything().size
            val current = engine.arrivals(
                hideSuspectedRotations = hideRotations,
                hideRandomAddresses = hideRandom,
            )
            arrivals = current

            // Known devices dimmed, arrivals bright. On a street the known set is most of
            // the display and none of the point.
            val newAddresses = current.map { it.sighting.address }.toSet()
            targets = engine.inRange(now).map { sighting ->
                RadarTarget(
                    address = sighting.address,
                    label = notes[sighting.address]?.nickname ?: sighting.label(),
                    smoothedRssi = sighting.rssi.toDouble(),
                    flagged = sighting.address in newAddresses,
                )
            }

            // One alert per newcomer, not one per packet. A suspected rotation stays
            // silent - it is almost certainly a phone that was already here.
            val fresh = current.filter { it.sighting.address !in announced }
            if (fresh.isNotEmpty()) {
                announced = announced + fresh.map { it.sighting.address }
                feedback.alert(alertStyle, urgent = fresh.any { it.approaching })
            }
        }
    }

    // Back from the snapshots tab returns to watching rather than leaving Discovery.
    BackHandler(enabled = tab != Tab.WATCH) { tab = Tab.WATCH }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab.entries.forEach { entry ->
            FilterChip(
                selected = tab == entry,
                onClick = { tab = entry },
                label = { Text(entry.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    when (tab) {
        Tab.WATCH -> WatchTab(
            stage = stage,
            targets = targets,
            showRadar = showRadar,
            onToggleRadar = { showRadar = !showRadar },
            hideRotations = hideRotations,
            onHideRotations = { hideRotations = it },
            hideRandom = hideRandom,
            onHideRandom = { hideRandom = it },
            onSeed = { seeding = true },
            selectedBlip = selectedBlip,
            onSelectBlip = { selectedBlip = it },
            arrivalsForDetail = arrivals,
            progress = progress,
            baselineSeconds = baselineSeconds,
            onBaselineSeconds = { baselineSeconds = it },
            baselineSize = baselineSize,
            totalSeen = totalSeen,
            arrivals = arrivals,
            alertStyle = alertStyle,
            onAlertStyle = { alertStyle = it },
            feedback = feedback,
            nicknameOf = { notes[it.uppercase(Locale.US)]?.nickname },
            onStart = {
                engine.baselineMs = (baselineSeconds.roundToInt() * 1000).toLong()
                engine.start(System.currentTimeMillis())
                announced = emptySet()
                arrivals = emptyList()
                stage = DiscoveryStage.BASELINE
            },
            onAbsorb = {
                engine.absorbIntoBaseline()
                announced = emptySet()
                arrivals = engine.arrivals()
                baselineSize = engine.baselineSize
            },
            onIgnore = { address ->
                engine.ignore(address)
                arrivals = engine.arrivals()
            },
            onSnapshot = { naming = true },
        )

        Tab.SNAPSHOTS -> SnapshotsTab(
            saved = saved,
            selected = comparing,
            onToggle = { taken ->
                comparing = when {
                    taken in comparing -> comparing - taken
                    comparing.size >= 2 -> setOf(taken)
                    else -> comparing + taken
                }
            },
            onDelete = { store.delete(it) },
        )
    }

    if (seeding) {
        AlertDialog(
            onDismissRequest = { seeding = false },
            title = { Text("Start from a saved place") },
            text = {
                Column {
                    Text(
                        "Everything in the chosen snapshot counts as already known, so " +
                            "anything not in it shows up straight away - no baseline to " +
                            "wait through.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Seed your own home and walk into a hotel room, and everything " +
                            "there is new by definition. Seed last week's scan of your " +
                            "own house and only what has appeared since will surface.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (saved.isEmpty()) {
                        Text(
                            "No snapshots saved yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    saved.take(8).forEach { snapshot ->
                        Text(
                            snapshot.label + "  (" + snapshot.size + " devices)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    engine.seedBaseline(
                                        snapshot.devices.map { it.address },
                                        System.currentTimeMillis(),
                                    )
                                    announced = emptySet()
                                    arrivals = emptyList()
                                    stage = DiscoveryStage.WATCHING
                                    seeding = false
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { seeding = false }) { Text("Cancel") }
            },
        )
    }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this place") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saves everything heard so far, baseline included. Take another " +
                            "here next week and the two can be compared.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = label.ifBlank { "Unnamed" }
                    store.save(engine.snapshot(name, System.currentTimeMillis()))
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------------- watching

@Composable
private fun WatchTab(
    stage: DiscoveryStage,
    targets: List<RadarTarget>,
    showRadar: Boolean,
    onToggleRadar: () -> Unit,
    hideRotations: Boolean,
    onHideRotations: (Boolean) -> Unit,
    hideRandom: Boolean,
    onHideRandom: (Boolean) -> Unit,
    onSeed: () -> Unit,
    selectedBlip: String?,
    onSelectBlip: (String?) -> Unit,
    arrivalsForDetail: List<Arrival>,
    progress: Float,
    baselineSeconds: Float,
    onBaselineSeconds: (Float) -> Unit,
    baselineSize: Int,
    totalSeen: Int,
    arrivals: List<Arrival>,
    alertStyle: AlertStyle,
    onAlertStyle: (AlertStyle) -> Unit,
    feedback: Feedback,
    nicknameOf: (String) -> String?,
    onStart: () -> Unit,
    onAbsorb: () -> Unit,
    onIgnore: (String) -> Unit,
    onSnapshot: () -> Unit,
) {
    when (stage) {
        DiscoveryStage.IDLE -> {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Learn the room, then watch for what is not part of it",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A scan of anywhere returns dozens of devices and no way to tell " +
                            "which matter. So this spends a minute filing everything it " +
                            "can hear as furniture, and after that shows only what turns " +
                            "up afterwards - the car that pulls in, the phone walking " +
                            "towards you, the thing in your house that was not here last " +
                            "week.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Baseline: ${baselineSeconds.roundToInt()} seconds",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = baselineSeconds,
                onValueChange = onBaselineSeconds,
                valueRange = 10f..120f,
                steps = 10,
            )
            Text(
                "Long enough for everything around you to advertise at least once. Thirty " +
                    "seconds covers most things; a beacon that speaks once a minute needs " +
                    "longer, and anything missed will be reported as an arrival later.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            AlertPicker(
                style = alertStyle,
                onStyle = onAlertStyle,
                feedback = feedback,
                title = "Alert on arrivals",
                note = "Once per new device. Suspected address rotations stay silent.",
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start baseline")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onSeed, modifier = Modifier.fillMaxWidth()) {
                Text("Or start from a saved place")
            }
        }

        DiscoveryStage.BASELINE -> {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Learning what is normally here",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    // The baseline is the part of this experiment where nothing appears
                    // to happen, which is exactly where a thin bar reads as a hang.
                    CountdownRing(
                        elapsedMs = (progress * baselineSeconds * 1000f).toLong(),
                        totalMs = (baselineSeconds * 1000f).toLong(),
                        label = "$baselineSize devices filed so far",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stay put - anything that wanders through now becomes furniture " +
                            "and will not be reported later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        DiscoveryStage.WATCHING -> {
            if (showRadar) {
                RadarPanel(
                    targets = targets,
                    selected = selectedBlip,
                    onSelect = onSelectBlip,
                    footnote = "Radius is signal strength, strongest in the middle. The " +
                        "angle is decorative - one antenna cannot tell you a bearing. " +
                        "Devices already here when the baseline closed are dimmed; " +
                        "arrivals are marked.",
                ) { target ->
                    val arrival = arrivalsForDetail.firstOrNull {
                        it.sighting.address == target.address
                    }
                    Text(
                        if (arrival == null) {
                            "Part of the baseline - it was here before watching started."
                        } else {
                            "Arrived after the baseline closed. " + arrival.trend.label +
                                "."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    arrival?.possibleRotationOf?.let {
                        Text(
                            "Possibly $it under a new random address.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Known", "$baselineSize", "filed as normal")
                Stat("New", "${arrivals.size}", "since baseline")
                Stat("Closing", "${arrivals.count { it.approaching }}", "getting nearer")
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = showRadar,
                    onClick = onToggleRadar,
                    label = { Text("Radar", style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = hideRotations,
                    onClick = { onHideRotations(!hideRotations) },
                    label = {
                        Text("Hide rotations", style = MaterialTheme.typography.labelSmall)
                    },
                )
                FilterChip(
                    selected = hideRandom,
                    onClick = { onHideRandom(!hideRandom) },
                    label = {
                        Text("Fixed only", style = MaterialTheme.typography.labelSmall)
                    },
                )
            }
            Text(
                when {
                    hideRandom -> "Only devices with fixed addresses. That means fitted " +
                        "equipment - cameras, beacons, cars, anything installed - and " +
                        "excludes phones almost entirely, since they randomise. The right " +
                        "setting for sweeping a room, the wrong one for watching a street."
                    hideRotations -> "Arrivals that look like a device already here " +
                        "changing its random address are hidden. Conservative: it can hide " +
                        "a real arrival that happens to resemble one."
                    else -> "Everything new is shown, rotations included. Expect the list " +
                        "to be mostly phones changing address every fifteen minutes."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            if (arrivals.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing new. Everything in range was here when the baseline " +
                            "closed.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                arrivals.forEach { arrival ->
                    ArrivalCard(
                        arrival = arrival,
                        nickname = nicknameOf(arrival.sighting.address),
                        onIgnore = { onIgnore(arrival.sighting.address) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            DiagnosticsPanel(
                title = "What discovery is seeing",
                diagnostics = listOf(
                    Diagnostic("Baseline", "$baselineSize", "known devices"),
                    Diagnostic("Total", "$totalSeen", "heard this run"),
                    Diagnostic("New", "${arrivals.size}", "since baseline"),
                    Diagnostic(
                        "Rotations",
                        "${arrivals.count { it.possibleRotationOf != null }}",
                        "suspected, not alerted",
                    ),
                    Diagnostic(
                        "Approaching",
                        "${arrivals.count { it.approaching }}",
                        "signal rising",
                    ),
                    Diagnostic(
                        "Leaving",
                        "${arrivals.count { it.trend == Trend.FURTHER }}",
                        "signal falling",
                    ),
                ),
                footnote = "A phone changes its random address about every fifteen " +
                    "minutes, and that looks exactly like a stranger arriving. Where an " +
                    "arrival plausibly matches something that just went quiet at the same " +
                    "signal strength, it is listed but not alerted on.",
            )

            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onAbsorb, modifier = Modifier.fillMaxWidth()) {
                Text("These are all fine - add them to the baseline")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onSnapshot, modifier = Modifier.fillMaxWidth()) {
                Text("Save a snapshot of this place")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onSeed, modifier = Modifier.fillMaxWidth()) {
                Text("Use a saved place as the baseline")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start over")
            }
        }
    }
}

@Composable
private fun ArrivalCard(arrival: Arrival, nickname: String?, onIgnore: () -> Unit) {
    val sighting = arrival.sighting
    val rotation = arrival.possibleRotationOf
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                rotation != null -> MaterialTheme.colorScheme.surfaceVariant
                arrival.approaching -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
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
                        nickname ?: sighting.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        sighting.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        arrival.trend.arrow + " ${sighting.rssi}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        String.format(Locale.US, "~%.1f m", arrival.metres),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (arrival.approaching) {
                Text(
                    String.format(
                        Locale.US,
                        "Getting stronger by %.1f dB a second - something is coming closer.",
                        arrival.slopeDbPerSecond,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            rotation?.let {
                Text(
                    "Probably not new: this looks like $it changing its random address. " +
                        "Same signal strength, and the old one fell silent just before " +
                        "this appeared.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onIgnore) {
                    Text("Not interesting", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ snapshots

@Composable
private fun SnapshotsTab(
    saved: List<Snapshot>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onDelete: (Snapshot) -> Unit,
) {
    if (saved.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("No snapshots yet", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Take one from the Watch tab. The point is the second one: scan a " +
                        "room you trust today, scan it again next week, and ask what " +
                        "changed. Scan three different places and anything that appears " +
                        "in all three came with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Text(
        "Pick two to compare",
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
    saved.forEach { snapshot ->
        val chosen = snapshot.takenAtMs in selected
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onToggle(snapshot.takenAtMs) },
            colors = CardDefaults.cardColors(
                containerColor = if (chosen) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(snapshot.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        SimpleDateFormat("d MMM, HH:mm", Locale.US)
                            .format(Date(snapshot.takenAtMs)) +
                            " · ${snapshot.size} devices",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onDelete(snapshot) }) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    val pair = saved.filter { it.takenAtMs in selected }.sortedBy { it.takenAtMs }
    if (pair.size == 2) {
        Spacer(Modifier.height(12.dp))
        DiffCard(Snapshots.diff(pair[0], pair[1]))
    }

    if (saved.size >= 2) {
        val travelling = Snapshots.commonTo(saved.take(5))
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (travelling.isEmpty()) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "In every recent snapshot",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                if (travelling.isEmpty()) {
                    Text(
                        "Nothing with a fixed address appears in all of your recent " +
                            "snapshots. Take them in genuinely different places for this " +
                            "to mean anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "These were present everywhere you took a snapshot. Your own " +
                            "earbuds and watch will be in here - the question is whether " +
                            "anything else is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    travelling.forEach {
                        Text(
                            "${it.label()}  ${it.address}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffCard(diff: SnapshotDiff) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${diff.before.label} → ${diff.after.label}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                diff.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Arrived", diff.arrived.map { it.sighting.label() to it.sighting.address })
            Section("Gone", diff.departed.map { it.sighting.label() to it.sighting.address })
            Section(
                "Moved closer",
                diff.movedCloser.map {
                    it.sighting.label() to "${it.deltaDb} dB stronger"
                },
            )
            Section(
                "Moved away",
                diff.movedAway.map {
                    it.sighting.label() to "${it.deltaDb} dB weaker"
                },
            )
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(
        "$title (${rows.size})",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    rows.take(20).forEach { (left, right) ->
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(left, style = MaterialTheme.typography.bodySmall)
            Text(
                right,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (rows.size > 20) {
        Text(
            "and ${rows.size - 20} more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
