package com.sigeye.experiments.fading

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.sigeye.core.analysis.FadeSample
import com.sigeye.core.analysis.FadingAnalysis
import com.sigeye.core.analysis.FadingCharacter
import com.sigeye.core.analysis.FadingStats
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PauseBar
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private const val HUB_TAG = "fading"

/** Half a wavelength at 2.44 GHz, the distance between a peak and a null. */
private const val HALF_WAVELENGTH_CM = 6.1

private enum class Stage { PICK, RECORD, RESULT }

private data class Candidate(
    val address: String,
    val name: String?,
    val vendor: String?,
    val rssi: Int,
    val sightings: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
) {
    val rate: Double
        get() = sightings * 1000.0 / (lastSeenMs - firstSeenMs).coerceAtLeast(1L)
}

/** One place the phone was held still, and what the signal did there. */
private data class Spot(val label: String, val stats: FadingStats)

@Composable
fun FadingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.FADING, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To watch one transmitter's signal while nothing moves.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted and nothing leaves the phone.",
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

    var stage by remember { mutableStateOf(Stage.PICK) }
    var target by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf("-") }

    // Both of these are deliberately outside Compose. Rebuilding an immutable collection
    // on every advertisement and writing it back to state recomposes the whole screen
    // hundreds of times a second, which starves the very coroutine doing the collecting.
    val candidateTable = remember { LinkedHashMap<String, Candidate>() }
    val record = remember { mutableListOf<FadeSample>() }

    var frozen by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var paused by remember { mutableStateOf(false) }
    var liveRssi by remember { mutableStateOf<Int?>(null) }
    var trace by remember { mutableStateOf<List<Int>>(emptyList()) }
    var stats by remember { mutableStateOf(FadingAnalysis.analyse(emptyList())) }
    var spots by remember { mutableStateOf<List<Spot>>(emptyList()) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(stage) {
        if (stage != Stage.PICK) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            synchronized(candidateTable) {
                val existing = candidateTable[advert.address]
                candidateTable[advert.address] = Candidate(
                    address = advert.address,
                    name = advert.name ?: existing?.name,
                    vendor = advert.vendor,
                    rssi = advert.rssi,
                    sightings = (existing?.sightings ?: 0) + 1,
                    firstSeenMs = existing?.firstSeenMs ?: advert.atMs,
                    lastSeenMs = advert.atMs,
                )
            }
        }
    }

    LaunchedEffect(paused, stage) {
        while (stage == Stage.PICK && !paused) {
            delay(700)
            val now = System.currentTimeMillis()
            frozen = synchronized(candidateTable) { candidateTable.values.toList() }
                .filter { now - it.lastSeenMs < 12_000 && it.sightings >= 3 }
                .sortedByDescending { it.rate }
                .take(20)
        }
    }

    LaunchedEffect(stage, target) {
        val address = target
        if (stage != Stage.RECORD || address == null) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            if (advert.address != address) return@collect
            liveRssi = advert.rssi
            synchronized(record) { record.add(FadeSample(advert.atMs, advert.rssi)) }
        }
    }

    // Analysis on a timer rather than per packet. The statistics do not change visibly
    // between one advertisement and the next, and recomputing them per packet is the
    // difference between a screen that updates and one that fights itself.
    LaunchedEffect(stage, target) {
        while (stage == Stage.RECORD) {
            delay(400)
            val snapshot = synchronized(record) { record.toList() }
            stats = FadingAnalysis.analyse(snapshot)
            trace = snapshot.takeLast(TRACE_POINTS).map { it.rssi }
        }
    }

    when (stage) {
        Stage.PICK -> PickSource(
            candidates = frozen,
            paused = paused,
            onTogglePause = { paused = !paused },
            nicknameOf = { notes[it.uppercase()]?.nickname },
            onPick = { candidate ->
                target = candidate.address
                targetLabel = notes[candidate.address.uppercase()]?.nickname
                    ?: candidate.name?.takeIf { it.isNotBlank() }
                    ?: candidate.vendor
                    ?: candidate.address
                synchronized(record) { record.clear() }
                spots = emptyList()
                trace = emptyList()
                stats = FadingAnalysis.analyse(emptyList())
                stage = Stage.RECORD
            },
        )

        Stage.RECORD -> Recording(
            label = targetLabel,
            spotNumber = spots.size + 1,
            rssi = liveRssi,
            trace = trace,
            stats = stats,
            spots = spots,
            onMarkSpot = {
                spots = spots + Spot("Spot ${spots.size + 1}", stats)
                synchronized(record) { record.clear() }
                trace = emptyList()
                stats = FadingAnalysis.analyse(emptyList())
            },
            onFinish = {
                val current = stats
                spots = if (current.samples >= FadingAnalysis.MIN_SAMPLES) {
                    spots + Spot("Spot ${spots.size + 1}", current)
                } else {
                    spots
                }
                stage = Stage.RESULT
            },
            onCancel = { stage = Stage.PICK },
        )

        Stage.RESULT -> Results(
            label = targetLabel,
            spots = spots,
            onAgain = {
                synchronized(record) { record.clear() }
                trace = emptyList()
                stats = FadingAnalysis.analyse(emptyList())
                stage = Stage.RECORD
            },
            onNewSource = {
                target = null
                spots = emptyList()
                stage = Stage.PICK
            },
        )
    }
}

private const val TRACE_POINTS = 180

// --------------------------------------------------------------------- stage one

@Composable
private fun PickSource(
    candidates: List<Candidate>,
    paused: Boolean,
    onTogglePause: () -> Unit,
    nicknameOf: (String) -> String?,
    onPick: (Candidate) -> Unit,
) {
    StepCard(
        "Step 1 of 3",
        "Pick something chatty",
        "This measures how much a signal wanders when nothing is moving, so it needs a " +
            "steady stream of packets. The rate matters more than the strength - a loud " +
            "device that speaks once a second will take all afternoon.",
    )
    Spacer(Modifier.height(10.dp))
    if (candidates.isEmpty()) {
        Text(
            "Listening...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PauseBar(paused = paused, onToggle = onTogglePause, summary = "${candidates.size} nearby")
    Spacer(Modifier.height(6.dp))
    candidates.forEach { candidate ->
        val name = nicknameOf(candidate.address)
            ?: candidate.name?.takeIf { it.isNotBlank() }
            ?: candidate.vendor
            ?: candidate.address
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onPick(candidate) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        candidate.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format(Locale.US, "%.1f/s", candidate.rate),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (candidate.rate >= 2.0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "${candidate.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------- stage two

@Composable
private fun Recording(
    label: String,
    spotNumber: Int,
    rssi: Int?,
    trace: List<Int>,
    stats: FadingStats,
    spots: List<Spot>,
    onMarkSpot: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    StepCard(
        "Step 2 of 3",
        "Spot $spotNumber - hold still",
        "Put the phone down, or hold it as steady as you can, and leave it. Everything " +
            "the trace does from here is the room, not you.",
    )

    Spacer(Modifier.height(12.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Text(
        rssi?.let { "$it dBm" } ?: "-",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )

    Spacer(Modifier.height(10.dp))
    FadeTrace(trace)

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Readings", stats.samples.toString(), "collected")
        Stat("Swing", "${stats.rangeDb} dB", "worst to best")
        Stat(
            "Spread",
            String.format(Locale.US, "%.1f dB", stats.sdDb),
            "standard deviation",
        )
    }

    Spacer(Modifier.height(10.dp))
    val progress = (stats.samples.toFloat() / FadingAnalysis.MIN_SAMPLES).coerceIn(0f, 1f)
    if (stats.samples < FadingAnalysis.MIN_SAMPLES) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            "Needs ${FadingAnalysis.MIN_SAMPLES} readings before the shape means anything.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        CharacterLine(stats)
    }

    if (spots.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "${spots.size} spot${if (spots.size == 1) "" else "s"} already recorded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "The point of the experiment",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Once you have a spot recorded, move the phone about " +
                    String.format(Locale.US, "%.0f cm", HALF_WAVELENGTH_CM) +
                    " - a hand's width - and record another. The distance to the " +
                    "transmitter has barely changed, so anything that treats signal " +
                    "strength as a ruler should read the same. It will not.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onMarkSpot,
        enabled = stats.samples >= FadingAnalysis.MIN_SAMPLES,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save this spot and move") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = onFinish,
        enabled = spots.isNotEmpty() || stats.samples >= FadingAnalysis.MIN_SAMPLES,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Finish and compare") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Pick a different source")
    }
}

// ------------------------------------------------------------------- stage three

@Composable
private fun Results(
    label: String,
    spots: List<Spot>,
    onAgain: () -> Unit,
    onNewSource: () -> Unit,
) {
    if (spots.isEmpty()) {
        Text("Nothing recorded.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
            Text("Start again")
        }
        return
    }

    StepCard(
        "Step 3 of 3",
        if (spots.size == 1) "One spot" else "${spots.size} spots compared",
        "Radius of the swing, how much of it is one dominant path, and what that does to " +
            "any attempt to turn signal strength into a distance.",
    )

    Spacer(Modifier.height(10.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    spots.forEach { spot ->
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    spot.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(
                        "Average",
                        String.format(Locale.US, "%.1f", spot.stats.meanDbm),
                        "dBm",
                    )
                    Stat("Swing", "${spot.stats.rangeDb} dB", "worst to best")
                    Stat("K", kLabel(spot.stats), "dominant vs scattered")
                }
                Spacer(Modifier.height(8.dp))
                CharacterLine(spot.stats)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A deep fade took it " +
                        String.format(Locale.US, "%.1f dB", spot.stats.fadeDepthDb) +
                        " below its own average. Read as a distance at a path loss " +
                        "exponent of 2, a thing actually 10 m away would have measured " +
                        "anywhere from " +
                        String.format(
                            Locale.US,
                            "%.1f to %.1f m",
                            spot.stats.distanceRange(10.0).start,
                            spot.stats.distanceRange(10.0).endInclusive,
                        ) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (spots.size >= 2) {
        val strongest = spots.maxBy { it.stats.meanDbm }
        val weakest = spots.minBy { it.stats.meanDbm }
        val difference = abs(strongest.stats.meanDbm - weakest.stats.meanDbm)
        Spacer(Modifier.height(14.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    String.format(Locale.US, "%.1f dB between spots", difference),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (difference < 2.0) {
                        "Barely any difference, which happens when one path dominates " +
                            "strongly - out of doors, or very close to the transmitter. " +
                            "Try again from further away or with a wall in the way."
                    } else {
                        "${strongest.label} read " +
                            String.format(Locale.US, "%.1f dB", difference) +
                            " stronger than ${weakest.label} from a spot a hand's width " +
                            "away. Nothing moved and nothing got closer. Fed through the " +
                            "usual distance formula at an exponent of 2, that alone is a " +
                            String.format(
                                Locale.US,
                                "%.1f-fold",
                                Math.pow(10.0, difference / 20.0),
                            ) +
                            " disagreement about how far away the thing is."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
        Text("Record another spot")
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
        Text("Pick a different source")
    }
}

// ------------------------------------------------------------------------- parts

/**
 * The live trace.
 *
 * Scaled to what has been seen rather than to a fixed dB window: the whole point is the
 * shape of the wander, and a fixed axis flattens a 3 dB ripple into a straight line.
 */
@Composable
private fun FadeTrace(values: List<Int>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val mean = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

    Box(modifier.fillMaxWidth().height(140.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (values.size < 2) return@Canvas
            val highest = values.max()
            val lowest = values.min()
            // A floor on the span stops a dead-steady signal from being drawn as a
            // dramatic zigzag of its own quantisation.
            val span = (highest - lowest).coerceAtLeast(6)
            val centre = (highest + lowest) / 2.0
            val top = centre + span / 2.0
            val step = size.width / (values.size - 1).toFloat()

            fun yFor(rssi: Double): Float =
                (((top - rssi) / span) * size.height).toFloat().coerceIn(0f, size.height)

            repeat(3) { index ->
                val y = size.height * (index + 1) / 4f
                drawLine(
                    color = grid,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val average = values.average()
            drawLine(
                color = mean,
                start = Offset(0f, yFor(average)),
                end = Offset(size.width, yFor(average)),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )

            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index * step
                val y = yFor(value.toDouble())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = line, style = Stroke(width = 2.5f))
        }
        if (values.size < 2) {
            Text(
                "Waiting for packets...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CharacterLine(stats: FadingStats) {
    val character = stats.character
    Text(
        character.label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = when (character) {
            FadingCharacter.SCATTERED -> MaterialTheme.colorScheme.error
            FadingCharacter.MIXED -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
    )
    Text(
        character.meaning,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** K is a ratio over a vast range, so it is shown in dB, with the ends named. */
private fun kLabel(stats: FadingStats): String {
    val k = stats.ricianKDb ?: return "-"
    return when {
        k.isInfinite() && k > 0 -> "flat"
        k.isInfinite() -> "0 dB"
        else -> String.format(Locale.US, "%.0f dB", k)
    }
}

@Composable
private fun Stat(label: String, value: String, hint: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepCard(step: String, title: String, body: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                step.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
