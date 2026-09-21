package com.sigeye.experiments.doppler

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CsvExport
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.RunFigure
import com.sigeye.core.analysis.rf.FitQuality
import com.sigeye.core.analysis.rf.PathLossFit
import com.sigeye.core.analysis.rf.PathLossResult
import com.sigeye.core.analysis.rf.WalkSample
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.sensors.StepSensor
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RunHistory
import com.sigeye.ui.Section
import com.sigeye.ui.Source
import com.sigeye.ui.SourceOrder
import com.sigeye.ui.SourcePicker
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "doppler"
private const val TICK_MS = 500L

private enum class Stage { PICK, WALK, RESULT }


@Composable
fun DopplerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The suite's mode switcher, drawn under this screen's own header.
     *
     * A slot rather than a bar the container draws above everything, so the switcher lands
     * below the title it belongs to instead of above the back button. Empty by default,
     * which is what keeps this screen openable on its own.
     */
    modes: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.DOPPLER, onBack)
        modes()
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To watch one transmitter fade as you walk away from it.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Physical activity permission is what lets the step counter work. " +
                "Without it, the distance has to be typed in.",
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
    val stepSensor = remember { StepSensor(context) }

    val stepCount by stepSensor.steps.collectAsStateWithLifecycle()

    var stage by remember { mutableStateOf(Stage.PICK) }
    var target by remember { mutableStateOf<String?>(null) }
    var targetLabel by remember { mutableStateOf("-") }
    var stride by remember { mutableStateOf(0.70f) }
    var manualMetres by remember { mutableStateOf(10f) }

    val walk = remember { mutableListOf<WalkSample>() }
    var liveRssi by remember { mutableStateOf<Int?>(null) }
    var metersSoFar by remember { mutableStateOf(0.0) }
    var samples by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<PathLossResult?>(null) }

    KeepScreenOn(stage == Stage.WALK)

    BackHandler(enabled = stage != Stage.PICK) {
        stage = Stage.PICK
        stepSensor.stop()
    }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            stepSensor.stop()
            BleScanHub.release(HUB_TAG)
        }
    }

    // Each reading is paired with how far the walker had got when it arrived, which is
    // the whole trick - everything else in this app guesses that number.
    LaunchedEffect(stage, target) {
        val address = target
        if (stage != Stage.WALK || address == null) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            if (advert.address != address) return@collect
            liveRssi = advert.rssi
            val distance = if (stepSensor.available) {
                stepCount.steps * stride.toDouble()
            } else {
                manualMetres.toDouble()
            }
            synchronized(walk) { walk.add(WalkSample(distance, advert.rssi)) }
        }
    }

    LaunchedEffect(stage) {
        while (stage == Stage.WALK) {
            delay(TICK_MS)
            val snapshot = synchronized(walk) { walk.toList() }
            samples = snapshot.size
            metersSoFar = snapshot.lastOrNull()?.meters ?: 0.0
            result = PathLossFit.fit(snapshot)
        }
    }

    when (stage) {
        Stage.PICK -> Pick(
            hasStepCounter = stepSensor.available,
            stepNote = stepCount.note,
            stride = stride,
            onStride = { stride = it },
            onPick = { candidate ->
                target = candidate.address
                targetLabel = candidate.label(book.nicknameOf(candidate.address))
                synchronized(walk) { walk.clear() }
                samples = 0
                result = null
                stepSensor.start()
                stepSensor.zero()
                stage = Stage.WALK
            },
        )

        Stage.WALK -> Walking(
            label = targetLabel,
            rssi = liveRssi,
            steps = stepCount.steps,
            meters = metersSoFar,
            samples = samples,
            hasStepCounter = stepSensor.available,
            manualMetres = manualMetres,
            onManualMetres = { manualMetres = it },
            result = result,
            onFinish = { stage = Stage.RESULT },
            onCancel = {
                stepSensor.stop()
                stage = Stage.PICK
            },
        )

        Stage.RESULT -> Results(
            label = targetLabel,
            result = result,
            walk = synchronized(walk) { walk.toList() },
            strideMetres = stride,
            onAgain = {
                synchronized(walk) { walk.clear() }
                samples = 0
                result = null
                stepSensor.zero()
                stage = Stage.WALK
            },
            onNewSource = {
                stepSensor.stop()
                stage = Stage.PICK
            },
        )
    }
}

// -------------------------------------------------------------------------- stage one

@Composable
private fun Pick(
    hasStepCounter: Boolean,
    stepNote: String?,
    stride: Float,
    onStride: (Float) -> Unit,
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
                "Measure the number everything else guesses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Signal strength becomes a distance through one formula with one unknown " +
                    "in it - the path loss exponent. Every proximity feature ever shipped " +
                    "guesses that number, usually as two. Walk away from something while " +
                    "counting your steps and it can be measured instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    if (!hasStepCounter) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                stepNote ?: "No step counter available, so distance will have to be set " +
                    "by hand as you go. That works, but pacing it out and moving a slider " +
                    "is harder than it sounds while also walking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    } else {
        Text(
            String.format(Locale.US, "Stride length: %.2f m", stride),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = stride,
            onValueChange = onStride,
            valueRange = 0.4f..1.0f,
            steps = 11,
        )
        Text(
            "Steps are counted by the phone; turning them into meters needs this. About " +
                "0.415 times your height is the usual estimate, so 0.70 m for someone " +
                "1.70 m tall - but pacing out a known distance and dividing is better, " +
                "because an error here goes straight into the exponent.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }

    SourcePicker(
        onPick = onPick,
        heading = "Pick something to walk away from",
        hint = "It has to stay put, so choose something fixed - a beacon, a speaker, a " +
            "television - and leave it where it is.",
        order = SourceOrder.SIGNAL,
        warnOnRandom = true,
        wantsRate = 1.0,
    )
}

// -------------------------------------------------------------------------- stage two

@Composable
private fun Walking(
    label: String,
    rssi: Int?,
    steps: Int,
    meters: Double,
    samples: Int,
    hasStepCounter: Boolean,
    manualMetres: Float,
    onManualMetres: (Float) -> Unit,
    result: PathLossResult?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Distance", String.format(Locale.US, "%.1f m", meters), "walked")
        Stat("Signal", rssi?.toString() ?: "-", "dBm now")
        Stat("Readings", "$samples", "paired")
    }

    if (hasStepCounter) {
        Text(
            "$steps steps",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    } else {
        Spacer(Modifier.height(10.dp))
        Text(
            String.format(Locale.US, "Distance now: %.0f m", manualMetres),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = manualMetres,
            onValueChange = onManualMetres,
            valueRange = 1f..50f,
            steps = 48,
        )
    }

    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            "Walk steadily away in a straight line, with the phone in your hand and the " +
                "transmitter left where it is. Fifteen meters or so is plenty. Do not turn " +
                "back until you have finished - the fit reads a return trip as the signal " +
                "refusing to fall.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(14.dp),
        )
    }

    result?.let {
        Spacer(Modifier.height(12.dp))
        LiveFit(it)
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onFinish,
        enabled = result?.quality != FitQuality.REJECTED,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop and read the fit") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Start over")
    }
}

@Composable
private fun LiveFit(result: PathLossResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (result.quality == FitQuality.REJECTED) {
                Text(
                    result.reason ?: "Not enough to fit yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    String.format(Locale.US, "n = %.2f so far", result.exponent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    String.format(
                        Locale.US,
                        "%d readings over %.1f m",
                        result.samples,
                        result.spanMetres,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ stage three

@Composable
private fun Results(
    label: String,
    result: PathLossResult?,
    walk: List<WalkSample>,
    strideMetres: Float,
    onAgain: () -> Unit,
    onNewSource: () -> Unit,
) {
    val context = LocalContext.current
    val fit = result
    if (fit == null || fit.quality == FitQuality.REJECTED) {
        Text(
            fit?.reason ?: "Nothing usable was recorded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Walk again") }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
            Text("Pick something else")
        }
        return
    }

    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        String.format(Locale.US, "n = %.2f", fit.exponent),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Text(fit.character(), style = MaterialTheme.typography.bodySmall)
    fit.reason?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Spacer(Modifier.height(12.dp))
    FitChart(walk, fit)

    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "What this changes",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            val (assumed, measured) = PathLossFit.disagreementMetres(
                rssi = -80,
                referenceRssi = fit.referenceRssi,
                assumed = 2.0,
                measured = fit.exponent,
            )
            Text(
                if (PathLossFit.exponentsDiffer(2.0, fit.exponent)) {
                    String.format(
                        Locale.US,
                        "A reading of -80 dBm here is %.1f m. The usual assumption of " +
                            "n = 2 would have called it %.1f m. Every distance this app " +
                            "shows you elsewhere is built on that assumption.",
                        measured,
                        assumed,
                    )
                } else {
                    "This environment is close enough to free space that the usual " +
                        "assumption of n = 2 was about right - which is worth knowing too."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    RunHistory(
        experiment = Experiments.DOPPLER,
        figures = listOf(
            RunFigure("Path loss exponent", fit.exponent, decimals = 2),
            RunFigure("Reference at 1 m", fit.referenceRssi, "dBm", 1),
            RunFigure("R squared", fit.rSquared, decimals = 2, higherIsBetter = true),
            RunFigure("Typical miss", fit.residualDb, "dB", 1, higherIsBetter = false),
            RunFigure("Distance walked", fit.spanMetres, "m", 1),
            RunFigure("Readings", fit.samples.toDouble(), decimals = 0),
        ),
        note = label,
    )

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "The fit",
        diagnostics = listOf(
            Diagnostic("Exponent", String.format(Locale.US, "%.2f", fit.exponent), "n"),
            Diagnostic(
                "Reference",
                String.format(Locale.US, "%.0f", fit.referenceRssi),
                "dBm at 1 m",
            ),
            Diagnostic("Readings", "${fit.samples}", "used"),
            Diagnostic("Distance", String.format(Locale.US, "%.1f m", fit.spanMetres), "walked"),
            Diagnostic("Scatter", String.format(Locale.US, "%.1f dB", fit.residualDb), "about the line"),
            Diagnostic("R squared", String.format(Locale.US, "%.2f", fit.rSquared), "explained"),
        ),
        footnote = "The model is rssi = reference - 10 n log10(distance), so plotting " +
            "signal against the logarithm of distance gives a straight line whose slope " +
            "is -10n. Scatter about that line is multipath, which is exactly what the " +
            "Multipath Fading experiment exists to show.",
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            CsvExport.shareText(
                context = context,
                folder = "pathloss",
                prefix = "walk",
                content = CsvExport.header("path loss walk", "source=$label") +
                    PathLossFit.csv(walk, fit, strideMetres.toDouble()),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the walk") }

    Spacer(Modifier.height(16.dp))
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
        Text("Walk it again")
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onNewSource, modifier = Modifier.fillMaxWidth()) {
        Text("Pick something else")
    }
}

/**
 * Readings against the logarithm of distance, with the fitted line through them.
 *
 * Log distance on the horizontal, because that is the axis on which the model is a
 * straight line - and a reader can see at a glance whether the readings actually form one
 * or whether the fit is drawing a line through a cloud.
 */
@Composable
private fun FitChart(walk: List<WalkSample>, fit: PathLossResult) {
    val dots = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    val spoken = "Signal against distance on a log axis, ${fit.samples} readings over " +
        "${"%.1f".format(java.util.Locale.US, fit.spanMetres)} metres. " +
        "Fitted path loss exponent ${"%.2f".format(java.util.Locale.US, fit.exponent)}, " +
        "R squared ${"%.2f".format(java.util.Locale.US, fit.rSquared)}, " +
        "typical miss ${"%.1f".format(java.util.Locale.US, fit.residualDb)} dB."

    Box(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxSize()) {
            val usable = walk.filter { it.meters >= PathLossFit.NEAR_FIELD_METRES }
            if (usable.size < 2) return@Canvas

            val xs = usable.map { log10(it.meters) }
            val minX = xs.min()
            val maxX = xs.max()
            val spanX = (maxX - minX).coerceAtLeast(0.01)
            val ys = usable.map { it.rssi }
            val minY = (ys.min() - 3).toDouble()
            val maxY = (ys.max() + 3).toDouble()
            val spanY = (maxY - minY).coerceAtLeast(1.0)

            repeat(3) { index ->
                val y = size.height * (index + 1) / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            fun pointFor(logMetres: Double, rssi: Double) = Offset(
                (((logMetres - minX) / spanX) * size.width).toFloat(),
                (((maxY - rssi) / spanY) * size.height).toFloat(),
            )

            usable.indices.forEach { index ->
                drawCircle(
                    color = dots.copy(alpha = 0.6f),
                    radius = 3f,
                    center = pointFor(xs[index], ys[index].toDouble()),
                )
            }

            // The fitted line, drawn across the whole range it was fitted over.
            val slope = -10.0 * fit.exponent
            drawLine(
                color = line,
                start = pointFor(minX, fit.referenceRssi + slope * minX),
                end = pointFor(maxX, fit.referenceRssi + slope * maxX),
                strokeWidth = 2.5f,
            )
        }
    }
    Text(
        "Each dot is a packet, placed by how far you had walked. The line is the fit. " +
            "Distance runs logarithmically, which is the axis on which this model is " +
            "straight - so a cloud rather than a line means the fit is not describing much.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
