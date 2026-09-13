package com.sigeye.experiments.polarisation

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.CsvExport
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.rf.PolarSweep
import com.sigeye.core.analysis.rf.Polarisation
import com.sigeye.core.analysis.rf.PolarisationResult
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.sensors.RollSensor
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.PolarPlot
import com.sigeye.ui.Source
import com.sigeye.ui.SourceOrder
import com.sigeye.ui.SourcePicker
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "polarisation"
private const val TICK_MS = 300L

/** Twelve bins over half a turn: fifteen degrees each, which a wrist can hold. */
private const val BINS = 12

/**
 * The plot is a whole circle showing half a turn of roll, so each axis is half of what a
 * compass plot would put there.
 */
private val ROLL_AXES = listOf("0°", "45°", "90°", "135°")

private enum class Stage { PICK, ROLL, RESULT }


@Composable
fun PolarisationScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.POLARISATION, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To watch one transmitter while the phone is rolled over.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "No compass here - roll comes from gravity, which nothing in a " +
                "building distorts.",
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
    val rollSensor = remember { RollSensor(context) }
    val sweep = remember { PolarSweep(sectorCount = BINS, minSamplesPerSector = 3) }

    val roll by rollSensor.roll.collectAsStateWithLifecycle()

    var stage by remember { mutableStateOf(Stage.PICK) }
    var target by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf("-") }
    var liveRssi by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<PolarisationResult?>(null) }
    var recorded by remember { mutableStateOf(0) }
    var droppedFlat by remember { mutableStateOf(0) }

    KeepScreenOn(stage == Stage.ROLL)

    BackHandler(enabled = stage != Stage.PICK) {
        stage = Stage.PICK
        rollSensor.stop()
    }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            rollSensor.stop()
            BleScanHub.release(HUB_TAG)
        }
    }

    // Roll is read straight off the sensor's flow rather than through Compose state, for
    // the same reason the compass sweep does it: a value captured by a long-lived
    // coroutine is one recomposition away from being stale.
    LaunchedEffect(stage, target) {
        val address = target
        if (stage != Stage.ROLL || address == null) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            if (advert.address != address) return@collect
            liveRssi = advert.rssi
            val current = rollSensor.roll.value
            if (current.degenerate) {
                droppedFlat++
                return@collect
            }
            if (sweep.add(Polarisation.plotAngle(current.degrees), advert.rssi, advert.atMs)) {
                recorded++
            }
        }
    }

    LaunchedEffect(stage) {
        while (stage == Stage.ROLL) {
            delay(TICK_MS)
            result = Polarisation.analyse(sweep, BINS)
        }
    }

    when (stage) {
        Stage.PICK -> Pick(
            rollAvailable = rollSensor.available,
            onPick = { candidate ->
                target = candidate.address
                targetLabel = candidate.label(book.nicknameOf(candidate.address))
                sweep.reset()
                recorded = 0
                droppedFlat = 0
                result = null
                rollSensor.start()
                stage = Stage.ROLL
            },
        )

        Stage.ROLL -> Rolling(
            label = targetLabel,
            rollDegrees = roll.degrees,
            flat = roll.degenerate,
            rssi = liveRssi,
            recorded = recorded,
            droppedFlat = droppedFlat,
            result = result,
            onFinish = {
                result = Polarisation.analyse(sweep, BINS)
                rollSensor.stop()
                stage = Stage.RESULT
            },
            onCancel = {
                rollSensor.stop()
                stage = Stage.PICK
            },
        )

        Stage.RESULT -> Results(
            label = targetLabel,
            result = result,
            recorded = recorded,
            sweep = sweep,
            onAgain = {
                rollSensor.start()
                sweep.reset()
                recorded = 0
                droppedFlat = 0
                result = null
                stage = Stage.ROLL
            },
            onNewSource = {
                rollSensor.stop()
                stage = Stage.PICK
            },
        )
    }
}

// -------------------------------------------------------------------------- stage one

@Composable
private fun Pick(
    rollAvailable: Boolean,
    onPick: (Source) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Turn the phone over and watch the signal vanish",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "An antenna radiates a field pointing one way. Turn a receiving antenna " +
                    "across that field and it picks up far less of it - in theory nothing " +
                    "at all. Rolling the phone about its long axis traces that out, and " +
                    "ten to twenty dB from nothing but a twist of the wrist is the most " +
                    "surprising result in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No compass is involved. Roll comes from gravity, so unlike Body " +
                    "Absorption there is nothing here for a filing cabinet to upset - " +
                    "the only rule is to keep the phone level rather than stood on end.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    if (!rollAvailable) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                "This phone reports no accelerometer or rotation vector, so roll cannot be " +
                    "measured at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
        return
    }

    Spacer(Modifier.height(12.dp))
    SourcePicker(
        onPick = onPick,
        heading = "Pick something to roll against",
        hint = "A few metres away with a clear path works best. Too close and reflections " +
            "fill the null in; the rate on the right matters more than the strength.",
        order = SourceOrder.RATE,
        wantsRate = 2.0,
    )
}

// -------------------------------------------------------------------------- stage two

@Composable
private fun Rolling(
    label: String,
    rollDegrees: Float,
    flat: Boolean,
    rssi: Int?,
    recorded: Int,
    droppedFlat: Int,
    result: PolarisationResult?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium)

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Roll", "${rollDegrees.roundToInt()}°", "about the long axis")
        Stat("Signal", rssi?.toString() ?: "-", "dBm now")
        Stat("Recorded", "$recorded", "readings")
    }

    Spacer(Modifier.height(10.dp))
    val coverage = result?.coverage ?: 0f
    LinearProgressIndicator(progress = { coverage }, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.height(10.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (flat) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            if (flat) {
                "Tip the phone level. Stood on end, gravity runs straight down the long " +
                    "axis and there is nothing left across it to measure roll against - " +
                    "readings are being dropped rather than recorded against a number " +
                    "that spins on its own."
            } else {
                "Hold it with the long axis level, pointing left and right in front of " +
                    "you, and roll it slowly about that axis - like turning a rolling " +
                    "pin. Screen up, screen sideways, screen down, all the way over and " +
                    "back. Keep it in the same spot while you do: moving it measures " +
                    "distance rather than polarisation."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }

    result?.let {
        Spacer(Modifier.height(12.dp))
        PolarPlot(
            result = it.sweep,
            liveHeading = Polarisation.plotAngle(rollDegrees),
            minSamplesPerSector = 3,
            axisLabels = ROLL_AXES,
        )
        Text(
            "Radius is signal. The response repeats every half turn, so both halves are " +
                "folded together and that half turn is drawn around the whole circle - " +
                "which is why the axes read 0, 45, 90, 135.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            it.describe(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onFinish,
        enabled = coverage >= 0.5f,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Finish the roll") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Pick something else")
    }
    if (droppedFlat > 0) {
        Text(
            "$droppedFlat readings dropped while the phone was flat.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------------ stage three

@Composable
private fun Results(
    label: String,
    result: PolarisationResult?,
    recorded: Int,
    sweep: PolarSweep,
    onAgain: () -> Unit,
    onNewSource: () -> Unit,
) {
    val context = LocalContext.current
    val polarisation = result
    if (polarisation == null) {
        Text("Nothing recorded.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
            Text("Start again")
        }
        return
    }

    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        polarisation.depthDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "-",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "between the best and worst roll angle",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    polarisation.verdict()?.let {
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    PolarPlot(
        result = polarisation.sweep,
        minSamplesPerSector = 3,
        axisLabels = ROLL_AXES,
    )

    if (polarisation.looksLikePolarisation) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "That is polarisation",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    String.format(
                        Locale.US,
                        "Two maxima and two minima a right angle apart is the response of " +
                            "a linear antenna, and nothing else in a room produces it. " +
                            "About %.0f%% of what you are receiving arrived by a single " +
                            "clean path - the rest survived being crossed, which means it " +
                            "bounced on the way.",
                        Polarisation.directPathFraction(polarisation.depthDb ?: 0.0) * 100,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "The sweep",
        diagnostics = listOf(
            Diagnostic(
                "Depth",
                polarisation.depthDb?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
                "dB",
            ),
            Diagnostic(
                "Aligned",
                polarisation.bestRollDegrees?.let { "${it.roundToInt()}°" } ?: "-",
                "strongest roll",
            ),
            Diagnostic(
                "Crossed",
                polarisation.worstRollDegrees?.let { "${it.roundToInt()}°" } ?: "-",
                "weakest roll",
            ),
            Diagnostic(
                "Apart",
                polarisation.separationDegrees?.let { "${it.roundToInt()}°" } ?: "-",
                "should be 90",
            ),
            Diagnostic("Covered", "${(polarisation.coverage * 100).roundToInt()}%", "of the roll"),
            Diagnostic("Readings", "$recorded", "recorded"),
        ),
        footnote = "The null depth is a second, independent route to the same quantity " +
            "the Multipath Fading experiment calls K - the share of the signal arriving " +
            "by one dominant path. Measuring it two different ways and getting a similar " +
            "answer is worth more than either measurement alone.",
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            CsvExport.shareText(
                context = context,
                folder = "polarisation",
                prefix = "roll",
                content = CsvExport.header("polarisation roll sweep", "source=$label") +
                    Polarisation.csv(sweep, polarisation),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the sweep") }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Roll it again") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
        Text("Pick something else")
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
