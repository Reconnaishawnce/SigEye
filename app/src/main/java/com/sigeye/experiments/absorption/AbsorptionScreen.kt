package com.sigeye.experiments.absorption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Permissions
import com.sigeye.core.Experiments
import com.sigeye.core.SweepExport
import com.sigeye.core.analysis.rf.DropReason
import com.sigeye.core.analysis.rf.PolarSweep
import com.sigeye.core.analysis.rf.SweepCounters
import com.sigeye.core.analysis.rf.SweepDiagnostics
import com.sigeye.core.analysis.rf.SweepAgreement
import com.sigeye.core.analysis.rf.SweepResult
import com.sigeye.core.analysis.rf.SessionResult
import com.sigeye.core.analysis.rf.SweepSession
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.sensors.CompassQuality
import com.sigeye.core.sensors.HeadingSensor
import com.sigeye.ui.BodyDiagram
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.isBlocking
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Source
import com.sigeye.ui.SourceOrder
import com.sigeye.ui.SourcePicker
import com.sigeye.ui.PolarPlot
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "absorption"
private const val MIN_SAMPLES_PER_SECTOR = 3

/** Where the guided procedure has got to. */
private enum class Stage { PICK_SOURCE, SWEEP, RESULT }

@Composable
fun AbsorptionScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.ABSORPTION, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To measure the signal from one chosen source. SigEye never connects.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it. Your location is never " +
                        "read or stored - the compass heading stays on the phone.",
                ),
            ),
            footnote = "Uses the compass, which needs no permission.",
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
    val compass = remember { HeadingSensor(context) }
    val sweep = remember { PolarSweep(sectorCount = 24, minSamplesPerSector = MIN_SAMPLES_PER_SECTOR) }

    val heading by compass.heading.collectAsStateWithLifecycle()

    val session = remember { SweepSession() }
    var stage by remember { mutableStateOf(Stage.PICK_SOURCE) }
    var sourceAddress by remember { mutableStateOf<String?>(null) }
    // A plain map, deliberately not Compose state. Writing a new immutable map on every
    // advertisement copied the whole thing per packet and recomposed the screen hundreds
    // of times a second, which starved the recording coroutine badly enough that most of
    // the turn never reached the sweep.
    var sourceLabel by remember { mutableStateOf("-") }
    var result by remember { mutableStateOf<SweepResult?>(null) }
    var sessionResult by remember { mutableStateOf(session.result()) }
    var liveRssi by remember { mutableStateOf<Int?>(null) }
    // The worst the compass got at any point during the sweep. Recording no longer stops
    // when it degrades, so the result has to carry the caveat instead.
    var worstCompass by remember { mutableStateOf(CompassQuality.HIGH) }
    val diagnostics = remember { SweepDiagnostics() }
    var counters by remember { mutableStateOf(SweepCounters()) }
    var resolution by remember { mutableStateOf(24) }
    var exported by remember { mutableStateOf<String?>(null) }
    var sourceRate by remember { mutableStateOf(0.0) }
    var turnRate by remember { mutableStateOf(0.0f) }
    val sourcePackets = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        compass.start()
        onDispose {
            compass.stop()
            BleScanHub.release(HUB_TAG)
        }
    }

    // Recording, keyed so it restarts cleanly when the stage or the source changes.
    //
    // The heading is read straight off the sensor's StateFlow rather than through the
    // Compose state this composable also holds: a value captured by a long-lived
    // coroutine is one recomposition away from being stale, and if it goes stale here the
    // whole sweep piles into whichever sector the phone happened to be facing.
    LaunchedEffect(stage, sourceAddress) {
        val target = sourceAddress
        if (stage != Stage.SWEEP || target == null) return@LaunchedEffect
        diagnostics.reset(System.currentTimeMillis())
        BleScanHub.adverts.collect { advert ->
            diagnostics.packet(advert.atMs)
            if (advert.address != target) {
                diagnostics.dropped(DropReason.NOT_THE_SOURCE)
                return@collect
            }
            diagnostics.fromSource(advert.atMs)
            liveRssi = advert.rssi
            sourcePackets.incrementAndGet()
            val current = compass.heading.value
            // Anything but a missing magnetometer is recorded. See CompassQuality's note
            // on why the strict gate belongs on printing bearings and not on capture.
            if (current.quality.isUsableForSweep) {
                if (sweep.add(current.degrees, advert.rssi, advert.atMs)) {
                    diagnostics.recorded(current.degrees)
                    if (current.quality.rank < worstCompass.rank) {
                        worstCompass = current.quality
                    }
                } else {
                    diagnostics.dropped(DropReason.NOT_TURNING)
                }
            } else {
                diagnostics.dropped(DropReason.NO_COMPASS)
            }
        }
    }

    LaunchedEffect(stage) {
        while (stage == Stage.SWEEP) {
            delay(300)
            // Resolution is chosen from what the source has actually delivered, not fixed
            // in advance - a slow advertiser now gets a coarse plot that fills rather
            // than a fine one that stays empty.
            resolution = sweep.bestResolution()
            result = sweep.result(resolution)
            counters = diagnostics.counters(System.currentTimeMillis())
        }
    }

    // Advertising rate of the chosen source. This is the number that decides whether a
    // sweep can work at all: a device sending one packet a second cannot fill 24 sectors.
    LaunchedEffect(sourceAddress) {
        while (true) {
            delay(2_000)
            sourceRate = sourcePackets.getAndSet(0) / 2.0
        }
    }

    // Degrees per second, for the turn-speed coaching.
    //
    // Collected in one long-lived coroutine. Keying a LaunchedEffect on the heading itself
    // cancelled and relaunched it on every sensor sample, which was enough work to make
    // the needle stutter and the whole screen feel broken.
    LaunchedEffect(Unit) {
        var previous: Pair<Long, Float>? = null
        compass.heading.collect { current ->
            diagnostics.heading()
            val now = System.currentTimeMillis()
            previous?.let { (thenMs, thenDegrees) ->
                var delta = current.degrees - thenDegrees
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                val seconds = (now - thenMs) / 1000f
                if (seconds > 0.02f) {
                    val instant = kotlin.math.abs(delta) / seconds
                    turnRate = turnRate * 0.75f + instant * 0.25f
                }
            }
            previous = now to current.degrees
        }
    }


    when (stage) {
        Stage.PICK_SOURCE -> PickSource(
            compassQuality = heading.quality,
            onPick = { candidate ->
                sourceAddress = candidate.address
                sourceLabel = candidate.label(book.nicknameOf(candidate.address))
                sourcePackets.set(0)
                worstCompass = CompassQuality.HIGH
                sweep.reset()
                session.clear()
                sessionResult = session.result()
                result = null
                stage = Stage.SWEEP
            },
        )

        Stage.SWEEP -> Sweeping(
            sourceLabel = sourceLabel,
            counters = counters,
            resolution = resolution,
            heading = heading.degrees,
            quality = heading.quality,
            rssi = liveRssi,
            sourceRate = sourceRate,
            turnRate = turnRate,
            runNumber = session.count() + 1,
            result = result,
            onFinish = {
                val finished = sweep.adaptiveResult()
                session.add(finished)
                sessionResult = session.result()
                result = finished
                stage = Stage.RESULT
            },
            onCancel = { stage = Stage.PICK_SOURCE },
        )

        Stage.RESULT -> Results(
            sourceLabel = sourceLabel,
            session = sessionResult,
            worstCompass = worstCompass,
            counters = counters,
            exported = exported,
            onExport = {
                val file = SweepExport.write(context, sourceLabel, sweep.readings())
                exported = file?.name
                file?.let { SweepExport.share(context, it) }
            },
            onAgain = {
                sweep.reset()
                worstCompass = CompassQuality.HIGH
                result = null
                stage = Stage.SWEEP
            },
            onNewSource = {
                sourceAddress = null
                session.clear()
                sessionResult = session.result()
                stage = Stage.PICK_SOURCE
            },
        )
    }
}


// ------------------------------------------------------------------ stage one

@Composable
private fun PickSource(compassQuality: CompassQuality, onPick: (Source) -> Unit) {
    StepCard(
        step = "Step 1 of 3",
        title = "Choose something to listen to",
        body = "Pick a source that talks often. The rate beside each one is packets per " +
            "second - a sweep needs about three readings in each of twenty-four sectors, " +
            "so anything under 3/s cannot fill the circle in the time a turn takes. " +
            "Beacons, earbuds and speakers are chatty; most phones are not.",
    )

    CompassBanner(compassQuality)

    Spacer(Modifier.height(10.dp))
    SourcePicker(
        onPick = onPick,
        heading = "Nearby",
        order = SourceOrder.RATE,
        wantsRate = 3.0,
        warnOnRandom = true,
    )
}

@Composable
private fun Sweeping(
    sourceLabel: String,
    counters: SweepCounters,
    resolution: Int,
    heading: Float,
    quality: CompassQuality,
    rssi: Int?,
    sourceRate: Double,
    turnRate: Float,
    runNumber: Int,
    result: SweepResult?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    StepCard(
        step = "Step 2 of 3 · sweep $runNumber",
        title = "Turn slowly, all the way round",
        body = "Hold the phone flat against your chest, screen facing out, and turn on " +
            "the spot through a full circle. Keep it against your body: it is your torso " +
            "doing the absorbing, and the phone has to be on one side of it.",
    )

    CompassBanner(quality)

    val coverage = result?.coverage ?: 0f
    // Visited, as opposed to measured. A slow source leaves most sectors touched but
    // unsettled through the whole first turn, and showing only the settled figure made a
    // sweep that was working look like one that was not.
    val touched = result?.touchedFraction ?: 0f
    // The region centroid, not the single best sector - it does not jump between
    // near-tied sectors while you turn.
    val sourceBearing = result?.peakBearingDegrees
    val blocking = sourceBearing != null && isBlocking(heading, sourceBearing)

    // The picture first, the plot second. The polar trace is the measurement, but this is
    // the thing that makes the measurement make sense while you are doing it.
    Spacer(Modifier.height(12.dp))
    BodyDiagram(
        headingDegrees = heading,
        sourceBearingDegrees = sourceBearing,
        strength = strengthFraction(rssi, result),
        blocking = blocking,
    )

    Text(
        when {
            sourceBearing == null ->
                "Turn a little further - the strongest direction is not clear yet."
            blocking ->
                "You are between the phone and the source now. This is the shadow, and " +
                    "the signal should be at its weakest."
            else ->
                "Clear path to the source. Keep turning until your back is to it."
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (blocking) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )

    Spacer(Modifier.height(12.dp))
    PolarPlot(
        result = result ?: SweepResult(emptyList(), 0, 24, 0, null, null),
        liveHeading = heading,
        minSamplesPerSector = MIN_SAMPLES_PER_SECTOR,
    )

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Heading", "${heading.roundToInt()}°", compassPoint(heading))
        Stat("Signal", rssi?.let { "$it" } ?: "-", "dBm now")
        Stat(
            "Covered",
            "${(coverage * 100).roundToInt()}%",
            "measured, ${(touched * 100).roundToInt()}% visited",
        )
    }

    Spacer(Modifier.height(10.dp))
    SweepDiagnosticsCard(counters, resolution)

    Spacer(Modifier.height(10.dp))
    // Two bars in one: the faint one is how far round you have been, the solid one how
    // much of that has enough readings to count.
    Box(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { touched },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        )
        LinearProgressIndicator(progress = { coverage }, modifier = Modifier.fillMaxWidth())
    }

    // Turn-speed coaching. Sectors are 15 degrees and need three readings, so the fastest
    // usable turn is roughly a fifth of the source's packet rate in degrees per second.
    val maxUsableTurn = (sourceRate * 15.0 / MIN_SAMPLES_PER_SECTOR).toFloat()
    val coaching = when {
        sourceRate < 1.0 -> "This source is barely talking. Go back and pick a chattier one."
        turnRate < 2f -> "Start turning, slowly."
        maxUsableTurn > 1f && turnRate > maxUsableTurn ->
            "Too fast for this source - slow down or sectors will stay empty."
        coverage >= 0.75f -> "Enough of the circle covered. Finish whenever you like."
        touched > 0.8f && coverage < 0.5f ->
            "You have been all the way round, but the sectors are thin. Turn again - a " +
                "second lap adds to the same plot."
        else -> "Good pace. Keep going."
    }
    Text(
        coaching,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = when {
            sourceRate < 1.0 -> MaterialTheme.colorScheme.error
            maxUsableTurn > 1f && turnRate > maxUsableTurn -> MaterialTheme.colorScheme.error
            coverage >= 0.75f -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Text(
        String.format(
            Locale.US,
            "%s · %.1f packets/s · turning %.0f°/s",
            sourceLabel,
            sourceRate,
            turnRate,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onFinish,
        enabled = coverage > 0.25f,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Finish sweep $runNumber") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Pick a different source")
    }
}

// ---------------------------------------------------------------- stage three

@Composable
private fun Results(
    sourceLabel: String,
    session: SessionResult,
    worstCompass: CompassQuality,
    counters: SweepCounters,
    exported: String?,
    onExport: () -> Unit,
    onAgain: () -> Unit,
    onNewSource: () -> Unit,
) {
    val combined = session.combined
    if (session.runCount == 0) {
        Text("No sweep recorded.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    StepCard(
        step = "Step 3 of 3",
        title = if (session.runCount == 1) {
            "One sweep done - now do another"
        } else {
            "${session.runCount} sweeps combined"
        },
        body = "Radius is signal strength, north is up. A notch means something was " +
            "absorbing in that direction.",
    )

    // Recording no longer stops when the compass wobbles, so this is where the wobble gets
    // declared. The shape - how deep the notch is, how far it sits from the peak - holds
    // up; the compass rose it is drawn on may be rotated or stretched.
    if (!worstCompass.isUsable) {
        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Compass was " + worstCompass.label.removePrefix("Compass ").lowercase(
                        Locale.US,
                    ) + " during part of this turn",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "The sweep was still recorded, and the shape of it - how deep the " +
                        "notch is, and how far round it sits from the peak - is what the " +
                        "measurement rests on. Treat the compass headings themselves as " +
                        "approximate. Calibrating with a figure of eight and sweeping " +
                        "again will tighten them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    PolarPlot(result = combined, minSamplesPerSector = MIN_SAMPLES_PER_SECTOR)

    Spacer(Modifier.height(14.dp))
    val difference = combined.frontToBackDb
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            difference?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "dB between the best and worst direction",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        combined.peak?.let {
            Stat("Clearest", "${it.centerDegrees.roundToInt()}°", "${it.meanRssi.roundToInt()} dBm")
        }
        combined.notch?.let {
            Stat("Shadow", "${it.centerDegrees.roundToInt()}°", "${it.meanRssi.roundToInt()} dBm")
        }
        Stat("Sweeps", session.runCount.toString(), "${combined.totalSamples} reads")
    }

    // The agreement card is the real finding once there is more than one run.
    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (session.agreement) {
                SweepAgreement.CONSISTENT -> MaterialTheme.colorScheme.primaryContainer
                SweepAgreement.SCATTERED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                session.agreement.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(session.agreement.verdict, style = MaterialTheme.typography.bodySmall)
            session.notchSpreadDegrees?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Shadow headings: " +
                        session.notchHeadings.joinToString(", ") { h -> "${h.roundToInt()}°" } +
                        String.format(Locale.US, "  (spread %.0f°)", it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                "Reading this",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(interpret(combined, session), style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Source: $sourceLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    SweepDiagnosticsCard(counters, session.combined.totalSectors)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
        Text("Export this sweep as CSV")
    }
    exported?.let {
        Text(
            "Wrote $it - every reading in arrival order, with elapsed time, heading and " +
                "signal. The raw turn, so it can be looked at rather than guessed about.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Spacer(Modifier.height(10.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
        Text(if (session.runCount == 1) "Sweep again (recommended)" else "Sweep again")
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
        Text("Different source")
    }
}

/**
 * What the sweep is actually seeing, in counters rather than conclusions.
 *
 * This experiment's capture has been diagnosed from first principles twice and fixed
 * wrongly twice. The third attempt makes the running app answer the question instead:
 * packets in, packets from the source, packets recorded, and where they went.
 */
@Composable
private fun SweepDiagnosticsCard(counters: SweepCounters, resolution: Int) {
    DiagnosticsPanel(
        title = "What the sweep is seeing",
        verdict = counters.verdict(),
        diagnostics = listOf(
            Diagnostic("From source", counters.packetsFromSource.toString(), "packets"),
            Diagnostic("Recorded", counters.recorded.toString(), "into sectors"),
            Diagnostic(
                "Rate",
                String.format(Locale.US, "%.1f/s", counters.sourceRate),
                "from source",
            ),
            Diagnostic("All devices", counters.packetsSeen.toString(), "packets seen"),
            Diagnostic("Compass", counters.headingUpdates.toString(), "updates"),
            Diagnostic("Sectors", "$resolution", "${360 / resolution}° each"),
            Diagnostic("Not turning", counters.droppedNotTurning.toString(), "dropped"),
            Diagnostic("No compass", counters.droppedNoCompass.toString(), "dropped"),
            Diagnostic("Turn seen", "${counters.distinctHeadings}/12", "arcs visited"),
        ),
        footnote = "Readings taken while the phone is not turning are dropped: the " +
            "measurement is how signal changes with direction, so standing still adds " +
            "nothing and spends packets that other bearings need.",
    )
}

/** Plain words for the number, including the honest "you found nothing" case. */
private fun interpret(result: SweepResult, session: SessionResult): String {
    if (!result.isUsable()) {
        return "Only ${(result.coverage * 100).roundToInt()}% of the circle was covered, " +
            "which is not enough to call a direction. Turn further, and more slowly."
    }
    val difference = result.frontToBackDb ?: return "Not enough readings."
    val notch = result.notchBearingDegrees?.roundToInt()
        ?: result.notch?.centerDegrees?.roundToInt() ?: 0

    val depth = when {
        difference < 3.0 ->
            "Under 3 dB is within the noise of an ordinary room. Either nothing was " +
                "blocking the path, or the phone was held away from your body. Hold it " +
                "flat against your chest and try again."

        difference < 8.0 ->
            "About ${String.format(Locale.US, "%.0f", difference)} dB, weakest toward " +
                "$notch°. That is a believable body shadow: roughly half the power lost " +
                "when you stand in the way."

        else ->
            "${String.format(Locale.US, "%.0f", difference)} dB is a deep notch, weakest " +
                "toward $notch°. More than a body alone usually manages - there may be a " +
                "wall, a pillar or an appliance in that direction too."
    }

    val geometry = when {
        result.peakToNotchDegrees == null -> ""
        result.looksLikeBodyShadow ->
            " The quietest direction is " +
                "${result.peakToNotchDegrees!!.roundToInt()}° from the loudest, which is " +
                "about opposite - exactly where a torso would sit."
        else ->
            " The quietest direction is only " +
                "${result.peakToNotchDegrees!!.roundToInt()}° from the loudest. A body is " +
                "always on the far side of you from the source, so a notch that close to " +
                "the peak is something in the room rather than you."
    }

    val confirmation = when (session.agreement) {
        SweepAgreement.UNKNOWN ->
            " One sweep cannot separate you from the room, though - run it again."
        SweepAgreement.CONSISTENT ->
            " Repeated sweeps put it in the same place, so this is your body rather than " +
                "a reflection."
        SweepAgreement.MIXED -> " The sweeps only partly agree, so treat the heading loosely."
        SweepAgreement.SCATTERED ->
            " But the sweeps disagreed about where, which means you measured the room, " +
                "not yourself."
    }
    return depth + geometry + confirmation
}

// --------------------------------------------------------------------- pieces

@Composable
private fun StepCard(step: String, title: String, body: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                step.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * An uncalibrated magnetometer returns a confident wrong number rather than failing.
 *
 * Recording carries on regardless - the shape of a sweep survives a wonky compass even
 * when the bearings printed against it do not - so this warns about the bearings rather
 * than announcing a stop.
 */
@Composable
private fun CompassBanner(quality: CompassQuality) {
    if (quality.isUsable) return
    Spacer(Modifier.height(10.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                quality.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            quality.advice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "Nothing is recorded while the compass is like this - a plot drawn " +
                    "against a wrong heading is worse than no plot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
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
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where the current reading sits between the weakest and strongest seen so far. */
private fun strengthFraction(rssi: Int?, result: SweepResult?): Float {
    val value = rssi?.toDouble() ?: return 0f
    val settled = result?.sectors?.filter { it.samples >= MIN_SAMPLES_PER_SECTOR }
    val high = settled?.maxOfOrNull { it.meanRssi } ?: return 0.5f
    val low = settled.minOfOrNull { it.meanRssi } ?: return 0.5f
    val span = (high - low).coerceAtLeast(4.0)
    return ((value - low) / span).coerceIn(0.0, 1.0).toFloat()
}

private fun compassPoint(degrees: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((degrees % 360f) + 360f) % 360f / 45f).roundToInt() % 8
    return points[index]
}
