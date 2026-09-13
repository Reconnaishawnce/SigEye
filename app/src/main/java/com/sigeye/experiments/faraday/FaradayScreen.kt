package com.sigeye.experiments.faraday

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.RunFigure
import com.sigeye.core.analysis.rf.AbComparison
import com.sigeye.core.analysis.rf.AbResult
import com.sigeye.core.analysis.rf.FadeSample
import com.sigeye.core.analysis.rf.FadingAnalysis
import com.sigeye.core.analysis.rf.FadingStats
import com.sigeye.core.analysis.rf.Significance
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.PauseBar
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RunHistory
import com.sigeye.ui.SourceOrder
import com.sigeye.ui.rememberSources
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "faraday"
private const val SAMPLE_MS = 1_000L
/** Clear of the room's own wander by enough that the container is the explanation. */
private const val CLEAR_MARGIN = 3.0

/** Above it, but not by enough to believe on one run. */
private const val SOME_MARGIN = 1.5

private const val TARGET_SECONDS = 15

private enum class Stage { PICK, FLOOR, OUTSIDE, INSIDE, RESULT }

/**
 * How long to watch the room do nothing before measuring anything.
 *
 * Thirty seconds is enough for a device advertising once a second to show what its level
 * does when nothing is happening to it, which is the number every later claim is measured
 * against.
 */
private const val FLOOR_SECONDS = 30


@Composable
fun FaradayScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.FARADAY, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To measure one device's signal before and after shielding it.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it.",
                ),
            ),
            footnote = "The container goes around the transmitter, not the phone - you " +
                "need to keep tapping buttons.",
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
    val comparison = remember { AbComparison() }

    val notes by book.notes.collectAsStateWithLifecycle()

    var stage by remember { mutableStateOf(Stage.PICK) }
    val floorReadings = remember { mutableStateListOf<Int>() }
    var floor by remember { mutableStateOf(FadingAnalysis.analyze(emptyList())) }
    var target by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf("-") }
    var paused by remember { mutableStateOf(false) }
    val frozen = rememberSources(
        order = SourceOrder.SIGNAL,
        limit = 20,
        paused = paused || stage != Stage.PICK,
    )
    var result by remember { mutableStateOf(comparison.result()) }
    var liveRssi by remember { mutableStateOf<Int?>(null) }
    // Counted off the Compose thread and published on the phase timer, for the same
    // reason: a state write per packet is a recomposition per packet.
    val lastHeardMs = remember { AtomicLong(0L) }
    val outsideCount = remember { AtomicInteger(0) }
    val insideCount = remember { AtomicInteger(0) }
    // Packets are the other half of the story: a good shield gives no readings at all.
    var outsidePackets by remember { mutableStateOf(0) }
    var insidePackets by remember { mutableStateOf(0) }
    var silent by remember { mutableStateOf(false) }
    var phaseSeconds by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    // Recording, keyed so it restarts cleanly when the phase or the target changes.
    LaunchedEffect(stage, target) {
        val address = target
        val measuring = stage == Stage.FLOOR || stage == Stage.OUTSIDE || stage == Stage.INSIDE
        if (!measuring || address == null) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            if (advert.address != address) return@collect
            liveRssi = advert.rssi
            lastHeardMs.set(advert.atMs)
            if (stage == Stage.FLOOR) {
                floorReadings.add(advert.rssi)
                return@collect
            }
            comparison.record(advert.rssi.toDouble())
            when (comparison.phase) {
                AbComparison.Phase.BASELINE -> outsideCount.incrementAndGet()
                AbComparison.Phase.TEST -> insideCount.incrementAndGet()
                AbComparison.Phase.IDLE -> Unit
            }
        }
    }

    LaunchedEffect(stage) {
        phaseSeconds = 0
        while (stage == Stage.FLOOR) {
            delay(SAMPLE_MS)
            phaseSeconds++
            floor = FadingAnalysis.analyze(
                floorReadings.mapIndexed { index, rssi ->
                    FadeSample(atMs = index * 1_000L, rssi = rssi)
                },
            )
            val heard = lastHeardMs.get()
            silent = heard > 0 && System.currentTimeMillis() - heard > 4_000
            if (phaseSeconds >= FLOOR_SECONDS && floorReadings.size >= FadingAnalysis.MIN_SAMPLES) {
                comparison.startBaseline()
                stage = Stage.OUTSIDE
            }
        }
        phaseSeconds = 0
        while (stage == Stage.OUTSIDE || stage == Stage.INSIDE) {
            delay(SAMPLE_MS)
            phaseSeconds++
            result = comparison.result()
            outsidePackets = outsideCount.get()
            insidePackets = insideCount.get()
            val heard = lastHeardMs.get()
            silent = heard > 0 && System.currentTimeMillis() - heard > 4_000
        }
    }

    when (stage) {
        Stage.PICK -> {
            StepCard(
                "Step 1 of 3",
                "Choose something to shield",
                "Pick a small transmitter you can physically put inside a container - " +
                    "earbuds in their case, a tag, a beacon, a spare phone. It goes in " +
                    "the box, not this phone, because you need to keep pressing buttons.",
            )
            Spacer(Modifier.height(10.dp))
            if (frozen.isEmpty()) {
                Text(
                    "Listening for something steady enough to use...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return
            }
            PauseBar(
                paused = paused,
                onToggle = { paused = !paused },
                summary = "${frozen.size} nearby",
            )
            Spacer(Modifier.height(6.dp))
            frozen.forEach { candidate ->
                val label = notes[candidate.address.uppercase()]?.nickname
                    ?: candidate.name?.takeIf { it.isNotBlank() }
                    ?: candidate.vendor
                    ?: candidate.address
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable {
                            target = candidate.address
                            // Settled once, here, rather than looked up in the live table
                            // on every recomposition of the phase panels.
                            targetLabel = label
                            comparison.reset()
                            outsideCount.set(0)
                            insideCount.set(0)
                            outsidePackets = 0
                            insidePackets = 0
                            lastHeardMs.set(0L)
                            silent = false
                            liveRssi = null
                            floorReadings.clear()
                            floor = FadingAnalysis.analyze(emptyList())
                            stage = Stage.FLOOR
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                candidate.address,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${candidate.rssi}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                String.format(Locale.US, "%.1f/s", candidate.rate),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Before anything is measured, watch the room do nothing. A four decibel result
        // means one thing in a corridor that sits within one, and nothing at all in a
        // kitchen that wanders six on its own - and until now there was no way to tell
        // those two apart, so every shallow reading looked like a finding.
        Stage.FLOOR -> FloorPanel(
            label = targetLabel,
            seconds = phaseSeconds,
            total = FLOOR_SECONDS,
            readings = floorReadings.size,
            floor = floor,
            liveRssi = liveRssi,
            silent = silent,
            onCancel = { stage = Stage.PICK },
        )

        Stage.OUTSIDE -> PhasePanel(
            step = "Step 3 of 4",
            title = "Measuring it in the open",
            instruction = "Leave the device out in the open, a pace or two from the " +
                "phone, and do not move either. This is the reference.",
            label = targetLabel,
            seconds = phaseSeconds,
            samples = result.baseline.samples,
            liveRssi = liveRssi,
            packets = outsidePackets,
            onNext = {
                // The timer publishes the count once a second, so take the final tally
                // directly rather than leaving the last packets of the phase unreported.
                outsidePackets = outsideCount.get()
                comparison.startTest()
                stage = Stage.INSIDE
            },
            nextLabel = "It is in the container now",
            onCancel = { stage = Stage.PICK },
        )

        Stage.INSIDE -> PhasePanel(
            step = "Step 4 of 4",
            title = "Measuring it shielded",
            instruction = "Put the device inside the container and close it properly. " +
                "Keep the container where the device was, and keep the phone still.",
            label = targetLabel,
            seconds = phaseSeconds,
            samples = result.test.samples,
            liveRssi = liveRssi,
            packets = insidePackets,
            silent = silent,
            onNext = {
                comparison.stop()
                result = comparison.result()
                insidePackets = insideCount.get()
                stage = Stage.RESULT
            },
            nextLabel = "Stop and compare",
            onCancel = { stage = Stage.PICK },
        )

        Stage.RESULT -> Results(
            floor = floor,
            result = result,
            outsidePackets = outsidePackets,
            insidePackets = insidePackets,
            onAgain = {
                comparison.reset()
                outsideCount.set(0)
                insideCount.set(0)
                outsidePackets = 0
                insidePackets = 0
                lastHeardMs.set(0L)
                silent = false
                liveRssi = null
                comparison.startBaseline()
                floorReadings.clear()
                floor = FadingAnalysis.analyze(emptyList())
                stage = Stage.FLOOR
            },
            onNewTarget = {
                target = null
                targetLabel = "-"
                comparison.reset()
                stage = Stage.PICK
            },
        )
    }
}

/**
 * Thirty seconds of watching the room do nothing, which is what a result is measured against.
 *
 * Every reading here has been a before and against an after with nothing between them to
 * say how much of the difference was the container and how much was the room. Four decibels
 * is a real result in a corridor that sits within one, and nothing at all in a kitchen that
 * wanders six on its own - and both of those looked identical on this screen.
 */
@Composable
private fun FloorPanel(
    label: String,
    seconds: Int,
    total: Int,
    readings: Int,
    floor: FadingStats,
    liveRssi: Int?,
    silent: Boolean,
    onCancel: () -> Unit,
) {
    StepCard(
        "Step 2 of 4",
        "Watching the room do nothing",
        "Leave everything exactly where it is and do not move. This measures how much the " +
            "signal wanders on its own, which is the only thing that makes the next two " +
            "numbers mean anything - a shallow result in a restless room is not a result.",
    )

    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            elapsedMs = seconds.coerceAtMost(total) * 1_000L,
            totalMs = total * 1_000L,
            label = "settling",
            caption = "$readings readings",
        )
    }

    Spacer(Modifier.height(12.dp))
    Field("Watching", label)
    Field("Right now", liveRssi?.let { "$it dBm" } ?: "nothing yet")
    if (readings >= FadingAnalysis.MIN_SAMPLES) {
        Field("Wander so far", String.format(Locale.US, "%.1f dB", floor.sdDb))
        Field("Range", "${floor.minDbm} to ${floor.maxDbm} dBm")
    }

    if (silent) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Nothing heard for a few seconds. If it stays quiet the device may have gone to " +
                "sleep - wake it and start again, because a floor measured from four packets " +
                "is not a floor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Start over")
    }
}

@Composable
private fun PhasePanel(
    step: String,
    title: String,
    instruction: String,
    label: String,
    seconds: Int,
    samples: Int,
    liveRssi: Int?,
    packets: Int,
    silent: Boolean = false,
    onNext: () -> Unit,
    nextLabel: String,
    onCancel: () -> Unit,
) {
    StepCard(step, title, instruction)

    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (silent) "—" else liveRssi?.toString() ?: "—",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = if (silent) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            if (silent) "nothing getting through" else "dBm",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (silent) {
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(
                "Nothing at all is reaching the phone. That is a complete block, and the " +
                    "most interesting answer this experiment has - keep recording for a " +
                    "few more seconds and then stop.",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { (seconds.toFloat() / TARGET_SECONDS).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${seconds}s · $packets packets · $samples readings",
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onNext,
        // A silent phase is a legitimate result, so seconds rather than samples gate it.
        enabled = seconds >= 5,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(nextLabel) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun Results(
    floor: FadingStats,
    result: AbResult,
    outsidePackets: Int,
    insidePackets: Int,
    onAgain: () -> Unit,
    onNewTarget: () -> Unit,
) {
    val complete = insidePackets == 0 && outsidePackets > 0
    val attenuation = if (complete) null else result.baseline.mean - result.test.mean
    val packetLoss = if (outsidePackets == 0) {
        null
    } else {
        (1.0 - insidePackets.toDouble() / outsidePackets) * 100.0
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                complete -> "∞"
                attenuation != null -> String.format(Locale.US, "%.0f", attenuation)
                else -> "—"
            },
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (complete) "nothing got through" else "dB of shielding",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // How many times the room's own wander the result is. This is the whole point of the
    // floor: four decibels is a finding in a corridor that sits within one and nothing at
    // all in a kitchen that moves six, and the two used to look identical here.
    val wander = floor.sdDb.takeIf { floor.samples >= FadingAnalysis.MIN_SAMPLES && it > 0.1 }
    val margin = attenuation?.let { depth -> wander?.let { depth / it } }

    if (wander != null) {
        Spacer(Modifier.height(14.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    complete -> MaterialTheme.colorScheme.primaryContainer
                    margin == null -> MaterialTheme.colorScheme.surfaceVariant
                    margin >= CLEAR_MARGIN -> MaterialTheme.colorScheme.primaryContainer
                    margin >= SOME_MARGIN -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    when {
                        complete -> "Nothing got through at all"
                        margin == null -> "No depth to measure"
                        margin >= CLEAR_MARGIN -> "Well clear of the room's own wander"
                        margin >= SOME_MARGIN -> "Above the room's wander, but not by much"
                        else -> "Inside the room's own wander"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("Before anything was in the container this signal wandered ")
                        append(String.format(Locale.US, "%.1f dB", wander))
                        append(" on its own, with nothing moving. ")
                        when {
                            complete -> append(
                                "Nothing came through the container at all, which no amount " +
                                    "of wander explains.",
                            )

                            margin == null -> append("There is no depth to compare it to.")

                            margin >= CLEAR_MARGIN -> append(
                                "The container took " +
                                    String.format(Locale.US, "%.1f", margin) +
                                    " times that, which is a real shield rather than the " +
                                    "room having a moment.",
                            )

                            margin >= SOME_MARGIN -> append(
                                "The container took " +
                                    String.format(Locale.US, "%.1f", margin) +
                                    " times that. Something is happening, but not much more " +
                                    "than this spot does by itself - worth repeating before " +
                                    "believing.",
                            )

                            else -> append(
                                "The container took " +
                                    String.format(Locale.US, "%.1f", margin) +
                                    " times that, which is to say it did nothing this room " +
                                    "was not already doing. This is not a measurement of a " +
                                    "shield.",
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Open", String.format(Locale.US, "%.0f", result.baseline.mean), "dBm mean")
        Stat(
            "Shielded",
            if (complete) "—" else String.format(Locale.US, "%.0f", result.test.mean),
            "dBm mean",
        )
        packetLoss?.let {
            Stat("Packets lost", String.format(Locale.US, "%.0f%%", it), "of the original")
        }
    }

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Reading this",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                interpret(result, complete, attenuation, packetLoss),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Try the same container with the lid off, or with a corner of the foil " +
                    "lifted. A shield is only as good as its worst seam - a gap much " +
                    "smaller than the wavelength still leaks, and 2.4 GHz is 12 cm.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    RunHistory(
        experiment = Experiments.FARADAY,
        figures = listOfNotNull(
            attenuation?.let {
                RunFigure("Blocked", it, "dB", 1, higherIsBetter = true)
            },
            wander?.let {
                RunFigure("Room wander", it, "dB", 1, higherIsBetter = false)
            },
            margin?.let {
                RunFigure("Times the wander", it, decimals = 1, higherIsBetter = true)
            },
            packetLoss?.let {
                RunFigure("Packets lost", it * 100, "%", 0, higherIsBetter = true)
            },
            RunFigure("Outside", result.baseline.mean, "dBm", 1),
            RunFigure("Inside", result.test.mean, "dBm", 1),
            RunFigure("Outside packets", outsidePackets.toDouble(), decimals = 0),
            RunFigure("Inside packets", insidePackets.toDouble(), decimals = 0),
        ),
    )

    Spacer(Modifier.height(12.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Try another container") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewTarget, modifier = Modifier.fillMaxWidth()) {
        Text("Different device")
    }
}

private fun interpret(
    result: AbResult,
    complete: Boolean,
    attenuation: Double?,
    packetLoss: Double?,
): String = when {
    complete ->
        "A complete block. Nothing escaped at all, so the shielding is at least 30 dB and " +
            "possibly far more - once nothing gets through, there is no way to measure how " +
            "much further it would have gone. A sealed tin or a microwave door does this."

    result.significance == Significance.INSUFFICIENT ->
        "Not enough of either phase. Record at least five seconds on each side."

    attenuation == null -> "No usable comparison."

    attenuation < 3 ->
        "Under 3 dB is nothing. Whatever that container is made of, it is transparent at " +
            "2.4 GHz - cardboard, plastic and cloth all are. Try metal."

    attenuation < 10 ->
        "About ${attenuation.roundToInt()} dB, which is a real but modest loss - roughly " +
            "what a hand or a thin wall costs. Enough to notice, nowhere near enough to hide."

    attenuation < 25 ->
        "${attenuation.roundToInt()} dB is serious shielding" +
            (packetLoss?.let { ", and ${it.roundToInt()}% of packets never arrived" } ?: "") +
            ". Foil or a metal tin with an imperfect seal typically lands here: the metal " +
            "works, the seam leaks."

    else ->
        "${attenuation.roundToInt()} dB is very good shielding. Only a few packets survived. " +
            "This is what a properly closed metal enclosure does."
}

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
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
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
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
