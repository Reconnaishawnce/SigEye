package com.sigeye.experiments.rotationlab

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateListOf
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
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.SweepExport
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.identity.Cohort
import com.sigeye.core.analysis.identity.Cohorts
import com.sigeye.core.analysis.identity.LinkConfidence
import com.sigeye.core.analysis.identity.RoomRhythm
import com.sigeye.core.analysis.identity.RotationLab
import com.sigeye.core.analysis.identity.RotationRhythm
import com.sigeye.core.analysis.identity.RotationTrack
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.shape
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.Track2D
import com.sigeye.ui.TrackChart
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "rotationlab"
private const val TICK_MS = 2_000L

/** Lines the comparison chart will draw at once. More than four is a tangle. */
private const val MAX_COMPARED = 4

private enum class View(val label: String) {
    COHORTS("Who is here"),
    RHYTHM("How often"),
    COMPARE("Side by side"),
}

@Composable
fun RotationLabScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.ROTATION_LAB, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To read the manufacturer and the timing out of every advertisement.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is connected to and nothing leaves the phone. This reads " +
                "advertisements that are already being broadcast to everyone in range.",
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
    val lab = remember { RotationLab() }

    var view by remember { mutableStateOf(View.COHORTS) }
    var cohorts by remember { mutableStateOf<List<Cohort>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<RotationTrack>>(emptyList()) }
    var room by remember {
        mutableStateOf(RoomRhythm(emptyList(), null, emptyList(), 0, 0))
    }
    var addresses by remember { mutableStateOf(0) }
    var audible by remember { mutableStateOf(0) }
    var elapsed by remember { mutableStateOf(0L) }
    val compared = remember { mutableStateListOf<Int>() }

    KeepScreenOn(true)

    BackHandler(enabled = view != View.COHORTS) { view = View.COHORTS }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            lab.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                shape = advert.shape(),
                isRandom = advert.isRandomAddress,
                payloadVendor = advert.companyId?.let { Vendors.byCompanyId(it) },
                ouiVendor = Vendors.byAddress(advert.address),
            )
        }
    }

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            lab.tick(now)
            cohorts = lab.cohorts(now)
            tracks = lab.tracks { book.nicknameOf(it) }
            room = lab.roomRhythm(tracks)
            addresses = lab.addressCount
            audible = lab.audible(now)
            elapsed = now - startedAt
        }
    }

    Headline(cohorts, tracks, room, elapsed)

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        View.entries.forEach { option ->
            FilterChip(
                selected = view == option,
                onClick = { view = option },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    when (view) {
        View.COHORTS -> CohortView(cohorts)
        View.RHYTHM -> RhythmView(room, tracks)
        View.COMPARE -> CompareView(lab, tracks, compared)
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "Why fifteen minutes is a convention, not a rule",
        summary = "The timeout is settable from one second to an hour.",
    ) {
        Text(
            "The Bluetooth specification makes the resolvable private address timeout a " +
                "value a device sets for itself, anywhere from one second to an hour, " +
                "with a default of nine hundred seconds. Fifteen minutes is what most " +
                "stacks inherit, not what the standard requires - so the only way to know " +
                "what the device in front of you does is to watch it, which is what the " +
                "middle tab is for.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The more useful consequence is what the timer is not. It is not aligned to a " +
                "clock: it runs from the moment the last address was generated. Two " +
                "phones on the same table, both on the default, change at different " +
                "seconds and go on doing so. That offset - the phase - is a property of " +
                "the device that every rotation preserves, which is precisely what a " +
                "rotation is supposed to destroy.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field("What resets it", "Bluetooth off and on, flight mode, a reboot.")
        Field(
            "What it is worth",
            "A phase known to forty-five seconds on a fifteen minute cycle is one slot in " +
                "${RotationRhythm.phaseSlots(RotationRhythm.SPEC_DEFAULT_MS)} - useful " +
                "next to other evidence, nowhere near enough alone.",
        )
    }

    Spacer(Modifier.height(10.dp))
    Section(
        title = "What this cannot tell you",
        summary = "Cohorts are cheap. Individual tracks are claims.",
        emphasis = true,
    ) {
        Text(
            "Counting manufacturers is safe: a company identifier is a fact in the " +
                "payload and says nothing about any one person. Linking two addresses " +
                "into one track is an inference, and this one is made across a room " +
                "nobody invited it into - so only the strongest rating is drawn at all, " +
                "and even then a track is a hypothesis rather than a finding.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "To actually test one, use Defeating Randomization: pick a device you own, " +
                "walk it away, and watch whether the signal it claims is that device fades " +
                "with it. That is ground truth, and it beats every confidence score here.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A vendor from an address prefix is only meaningful while the address is real. " +
                "Once a device randomizes it, the prefix belongs to nobody, and only the " +
                "company identifier inside the payload survives - which is why so much of " +
                "any room lands in the unidentified pile.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            val directory = File(context.getExternalFilesDir(null), "rotations")
            directory.mkdirs()
            val file = File(directory, "lab-${System.currentTimeMillis()}.csv")
            runCatching { file.writeText(lab.csv()) }
            SweepExport.share(context, file)
        },
        enabled = tracks.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the tracks") }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What this is seeing",
        diagnostics = listOf(
            Diagnostic("Addresses", "$addresses", "remembered"),
            Diagnostic("Audible", "$audible", "right now"),
            Diagnostic("Cohorts", "${cohorts.count { it.vendor != Cohorts.UNKNOWN }}", "named"),
            Diagnostic(
                "Identified",
                "${(Cohorts.identifiedFraction(cohorts) * 100).roundToInt()}%",
                "of addresses",
            ),
            Diagnostic("Tracks", "${tracks.size}", "linked at all"),
            Diagnostic("Periods", "${room.periodsMs.size}", "measured"),
        ),
        verdict = if (elapsed in 1 until 3 * 60_000L && tracks.isEmpty()) {
            "No rotation seen yet. A device has to change address while this is watching, " +
                "and on the usual fifteen minute timer that is a long wait - and a period " +
                "needs three addresses, so half an hour before the middle tab says " +
                "anything."
        } else {
            null
        },
        footnote = "A track needs two addresses; a period needs three, because a period " +
            "is the gap between two changes. Addresses are remembered for " +
            "forty-five minutes so a track outlives the addresses it is made of.",
    )
}

// -------------------------------------------------------------------------- headline

@Composable
private fun Headline(
    cohorts: List<Cohort>,
    tracks: List<RotationTrack>,
    room: RoomRhythm,
    elapsedMs: Long,
) {
    val trackable = Cohorts.trackable(cohorts)
    val worst = trackable.firstOrNull()

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (worst != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        val onContainer = if (worst != null) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
        Column(Modifier.padding(14.dp)) {
            Text(
                when {
                    cohorts.isEmpty() -> "Listening"
                    worst != null ->
                        "${worst.size} ${worst.vendor} devices here, none of them rotating"
                    else -> "${cohorts.sumOf { it.size }} addresses, " +
                        "${cohorts.count { it.vendor != Cohorts.UNKNOWN }} makers"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${tracks.size} track")
                    if (tracks.size != 1) append("s")
                    room.medianPeriodMs?.let {
                        append(" · typically ")
                        append(String.format(Locale.US, "%.1f min", it / 60_000.0))
                    }
                    if (room.tracksWithPhase > 0) {
                        append(" · ${room.tracksWithPhase} holding a phase")
                    }
                    if (elapsedMs > 0) {
                        append(" · watching for ${elapsedMs / 60_000} min")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

// --------------------------------------------------------------------- who is here

@Composable
private fun CohortView(cohorts: List<Cohort>) {
    if (cohorts.isEmpty()) {
        Text(
            "Nothing yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        "Rotation is a firmware decision, so it is a vendor trait: a maker's whole fleet " +
            "behaves the same way. Grouping the room by manufacturer is grouping it by " +
            "behavior.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    cohorts.forEach { cohort ->
        val trackable = cohort.vendor != Cohorts.UNKNOWN && cohort.randomized == 0 &&
            cohort.size >= 2
        Card(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    trackable -> MaterialTheme.colorScheme.errorContainer
                    cohort.vendor == Cohorts.UNKNOWN -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.surfaceVariant
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
                        cohort.vendor,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${cohort.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                PrivacyBar(cohort)
                Spacer(Modifier.height(6.dp))
                Text(Cohorts.describe(cohort), style = MaterialTheme.typography.bodySmall)
                if (cohort.medianIntervalMs > 0) {
                    Text(
                        "advertises about every ${cohort.medianIntervalMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cohort.vendor == Cohorts.UNKNOWN) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No company identifier in the payload and no usable address " +
                            "prefix. This pile is a measure of what could not be worked " +
                            "out, not a maker.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Rotating against fixed, as one bar, because the ratio is the finding. */
@Composable
private fun PrivacyBar(cohort: Cohort) {
    val rotating = MaterialTheme.colorScheme.primary
    val fixed = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val split = size.width * cohort.privacyFraction
        drawRect(color = rotating, size = Size(split, size.height))
        drawRect(
            color = fixed,
            topLeft = Offset(split, 0f),
            size = Size(size.width - split, size.height),
        )
    }
}

// ---------------------------------------------------------------------- how often

@Composable
private fun RhythmView(room: RoomRhythm, tracks: List<RotationTrack>) {
    val measured = tracks.filter { it.rhythm.measurable }

    if (!room.measurable) {
        Text(
            "No period measured yet. A period is the gap between two address changes, so " +
                "a device has to be followed through three addresses before there is one " +
                "at all - roughly half an hour on the usual timer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                String.format(
                    Locale.US,
                    "%.1f minutes, measured",
                    (room.medianPeriodMs ?: 0L) / 60_000.0,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                RotationRhythm.familiarName(room.medianPeriodMs)
                    ?.let { "which is $it" }
                    ?: "which is not one of the usual values",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${room.periodsMs.size} gaps across ${room.tracksMeasured} devices, " +
                    "${room.tracksWithPhase} of which kept a steady phase.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Histogram(room)

    Spacer(Modifier.height(12.dp))
    measured.forEach { track -> TrackRhythmCard(track) }
}

@Composable
private fun Histogram(room: RoomRhythm) {
    if (room.buckets.isEmpty()) return
    val bar = MaterialTheme.colorScheme.primary
    val tallest = room.buckets.maxOf { it.count }.coerceAtLeast(1)

    Text(
        "Every gap between two address changes, bucketed.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(90.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width / room.buckets.size
            room.buckets.forEachIndexed { index, bucket ->
                if (bucket.count == 0) return@forEachIndexed
                val height = size.height * bucket.count / tallest
                drawRect(
                    color = bar,
                    topLeft = Offset(index * width + 1f, size.height - height),
                    size = Size(width - 2f, height),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            String.format(Locale.US, "%.1f min", room.buckets.first().fromMs / 60_000.0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            String.format(Locale.US, "%.1f min", room.buckets.last().toMs / 60_000.0),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackRhythmCard(track: RotationTrack) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(track.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${track.vendor} · ${track.addresses.size} addresses",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    track.rhythm.describePeriod(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Field(
                "Keeps time",
                if (track.rhythm.regular) {
                    "yes - the gaps barely vary"
                } else {
                    "no - the gaps vary too much for the phase to mean anything"
                },
            )
            Field(
                "Phase",
                if (track.rhythm.phaseUsable) {
                    String.format(
                        Locale.US,
                        "held to %ds across %d changes - one slot in %d",
                        (track.rhythm.phaseSpreadMs ?: 0L) / 1000,
                        track.rhythm.changes,
                        RotationRhythm.phaseSlots(track.rhythm.medianPeriodMs),
                    )
                } else if (track.rhythm.changes < RotationRhythm.MIN_CHANGES_FOR_PHASE) {
                    "not yet - two changes define a phase, three test it"
                } else {
                    "wandering, so not a handle on this device"
                },
            )
            Field(
                "Within the specification",
                if (RotationRhythm.withinSpec(track.rhythm.medianPeriodMs)) {
                    "yes"
                } else {
                    "no - which means this track is probably two devices"
                },
            )
        }
    }
}

// --------------------------------------------------------------------- side by side

@Composable
private fun CompareView(
    lab: RotationLab,
    tracks: List<RotationTrack>,
    compared: MutableList<Int>,
) {
    if (tracks.isEmpty()) {
        Text(
            "Nothing has been linked across a rotation yet, so there is nothing to draw.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val chosen = tracks.filter { compared.contains(it.chainId) }
    val series = chosen.map { track ->
        Track2D(
            label = track.label,
            points = lab.trackTrail(track),
            eventsMs = track.changeTimesMs,
        )
    }

    Text(
        "Signal against time, with each address change marked. A rotation is invisible " +
            "alone and obvious in company: one line carries on through its marker while " +
            "the others do nothing. If the level jumps at the marker, the link is wrong.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    if (series.isEmpty()) {
        Text(
            "Pick up to $MAX_COMPARED below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        TrackChart(series)
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Tracks to draw",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))

    tracks.take(20).forEach { track ->
        val selected = compared.contains(track.chainId)
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable {
                    if (selected) {
                        compared.remove(track.chainId)
                    } else if (compared.size < MAX_COMPARED) {
                        compared.add(track.chainId)
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(track.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        track.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        track.current,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${track.lastRssi} dBm",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (track.weakestLink == LinkConfidence.STRONG) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    if (compared.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Button(onClick = { compared.clear() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear the chart")
        }
    }
}
