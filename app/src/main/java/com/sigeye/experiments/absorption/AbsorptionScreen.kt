package com.sigeye.experiments.absorption

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.DeviceNote
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.PolarSweep
import com.sigeye.core.analysis.SweepResult
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.sensors.CompassQuality
import com.sigeye.core.sensors.HeadingSensor
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
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
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("← All experiments")
        }
        Text(
            "Body Absorption",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Turn slowly in a circle and find your own shadow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val notes by book.notes.collectAsStateWithLifecycle()

    var stage by remember { mutableStateOf(Stage.PICK_SOURCE) }
    var sourceAddress by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<Map<String, Candidate>>(emptyMap()) }
    var result by remember { mutableStateOf<SweepResult?>(null) }
    var liveRssi by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        compass.start()
        onDispose {
            compass.stop()
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            val existing = candidates[advert.address]
            candidates = candidates + (
                advert.address to Candidate(
                    address = advert.address,
                    name = advert.name ?: existing?.name,
                    vendor = advert.vendor,
                    rssi = advert.rssi,
                    isRandom = advert.isRandomAddress,
                    sightings = (existing?.sightings ?: 0) + 1,
                    lastSeenMs = advert.atMs,
                )
                )

            if (advert.address == sourceAddress) {
                liveRssi = advert.rssi
                // Only record while the compass is worth believing.
                if (stage == Stage.SWEEP && heading.quality.isUsable) {
                    sweep.add(heading.degrees, advert.rssi)
                }
            }
        }
    }

    LaunchedEffect(stage) {
        while (stage == Stage.SWEEP) {
            delay(300)
            result = sweep.result()
        }
    }

    when (stage) {
        Stage.PICK_SOURCE -> PickSource(
            candidates = candidates.values.toList(),
            nicknameOf = { notes[it.uppercase()]?.nickname },
            compassQuality = heading.quality,
            onPick = { address ->
                sourceAddress = address
                sweep.reset()
                result = null
                stage = Stage.SWEEP
            },
        )

        Stage.SWEEP -> Sweeping(
            sourceLabel = labelFor(sourceAddress, candidates, notes),
            heading = heading.degrees,
            quality = heading.quality,
            rssi = liveRssi,
            result = result,
            onFinish = {
                result = sweep.result()
                stage = Stage.RESULT
            },
            onCancel = { stage = Stage.PICK_SOURCE },
        )

        Stage.RESULT -> Results(
            sourceLabel = labelFor(sourceAddress, candidates, notes),
            result = result,
            onAgain = {
                sweep.reset()
                result = null
                stage = Stage.SWEEP
            },
            onNewSource = {
                sourceAddress = null
                stage = Stage.PICK_SOURCE
            },
        )
    }
}

private data class Candidate(
    val address: String,
    val name: String?,
    val vendor: String?,
    val rssi: Int,
    val isRandom: Boolean,
    val sightings: Int,
    val lastSeenMs: Long,
)

private fun labelFor(
    address: String?,
    candidates: Map<String, Candidate>,
    notes: Map<String, DeviceNote>,
): String {
    if (address == null) return "-"
    notes[address.uppercase()]?.nickname?.takeIf { it.isNotBlank() }?.let { return it }
    val candidate = candidates[address] ?: return address
    return candidate.name?.takeIf { it.isNotBlank() } ?: candidate.vendor ?: address
}

// ------------------------------------------------------------------ stage one

@Composable
private fun PickSource(
    candidates: List<Candidate>,
    nicknameOf: (String) -> String?,
    compassQuality: CompassQuality,
    onPick: (String) -> Unit,
) {
    StepCard(
        step = "Step 1 of 3",
        title = "Choose something to listen to",
        body = "Pick a strong, stationary source - a beacon, a TV, a speaker. The " +
            "measurement is the difference between directions, so what it is matters " +
            "less than that it stays put and keeps talking.",
    )

    CompassBanner(compassQuality)

    Spacer(Modifier.height(12.dp))
    val now = System.currentTimeMillis()
    val usable = candidates
        .filter { now - it.lastSeenMs < 15_000 && it.sightings >= 3 }
        .sortedByDescending { it.rssi }
        .take(25)

    if (usable.isEmpty()) {
        Text(
            "Listening for something steady enough to use...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    usable.forEach { candidate ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onPick(candidate.address) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        nicknameOf(candidate.address)
                            ?: candidate.name?.takeIf { it.isNotBlank() }
                            ?: candidate.vendor
                            ?: candidate.address,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        candidate.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (candidate.isRandom) {
                        Text(
                            "Randomised address - fine for one sweep, but it will change " +
                                "within about fifteen minutes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    "${candidate.rssi}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ stage two

@Composable
private fun Sweeping(
    sourceLabel: String,
    heading: Float,
    quality: CompassQuality,
    rssi: Int?,
    result: SweepResult?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    StepCard(
        step = "Step 2 of 3",
        title = "Turn slowly, all the way round",
        body = "Hold the phone against your chest, screen facing out, and turn on the " +
            "spot through a full circle. Take about thirty seconds - too fast and the " +
            "sectors never fill. Keep the phone against your body: it is your torso " +
            "doing the absorbing, and the phone has to be on one side of it.",
    )

    CompassBanner(quality)

    val coverage = result?.coverage ?: 0f
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
        Stat("Covered", "${(coverage * 100).roundToInt()}%", "of the circle")
    }

    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(progress = { coverage }, modifier = Modifier.fillMaxWidth())
    Text(
        text = if (coverage >= 0.75f) {
            "Enough of the circle covered. Finish whenever you like."
        } else {
            "Keep turning - a sector needs a few readings before it counts."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )

    Spacer(Modifier.height(14.dp))
    Text(
        "Listening to $sourceLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onFinish,
        enabled = coverage > 0.25f,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Finish sweep") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Pick a different source")
    }
}

// ---------------------------------------------------------------- stage three

@Composable
private fun Results(
    sourceLabel: String,
    result: SweepResult?,
    onAgain: () -> Unit,
    onNewSource: () -> Unit,
) {
    if (result == null) {
        Text("No sweep recorded.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    StepCard(
        step = "Step 3 of 3",
        title = "What the sweep found",
        body = "Radius is signal strength, north is up. A notch means something was " +
            "absorbing in that direction.",
    )

    Spacer(Modifier.height(12.dp))
    PolarPlot(result = result, minSamplesPerSector = MIN_SAMPLES_PER_SECTOR)

    Spacer(Modifier.height(14.dp))
    val difference = result.frontToBackDb
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
        result.peak?.let {
            Stat("Clearest", "${it.centreDegrees.roundToInt()}°", "${it.meanRssi.roundToInt()} dBm")
        }
        result.notch?.let {
            Stat("Shadow", "${it.centreDegrees.roundToInt()}°", "${it.meanRssi.roundToInt()} dBm")
        }
        Stat("Covered", "${(result.coverage * 100).roundToInt()}%", "${result.totalSamples} reads")
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
            Text(interpret(result), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Multipath makes any single sweep noisy - reflections add and cancel " +
                    "independently of your body. Run it twice facing the same way. If the " +
                    "shadow lands in roughly the same place both times, it is you; if it " +
                    "moves, you measured the room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Source: $sourceLabel",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Sweep again") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
        Text("Different source")
    }
}

/** Plain words for the number, including the honest "you found nothing" case. */
private fun interpret(result: SweepResult): String {
    if (!result.isUsable()) {
        return "Only ${(result.coverage * 100).roundToInt()}% of the circle was covered, " +
            "which is not enough to call a direction. Turn further, and more slowly."
    }
    val difference = result.frontToBackDb ?: return "Not enough readings."
    val notch = result.notch?.centreDegrees?.roundToInt() ?: 0
    return when {
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
 * An uncalibrated magnetometer returns a confident wrong number rather than failing, so
 * this says so loudly and recording stops until it settles.
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

private fun compassPoint(degrees: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((degrees % 360f) + 360f) % 360f / 45f).roundToInt() % 8
    return points[index]
}
