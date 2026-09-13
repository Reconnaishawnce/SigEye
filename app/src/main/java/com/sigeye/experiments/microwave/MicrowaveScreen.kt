package com.sigeye.experiments.microwave

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.rf.AbComparison
import com.sigeye.core.analysis.rf.AbResult
import com.sigeye.core.analysis.rf.Significance
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HUB_TAG = "microwave"

/** One second per sample - fine against a phase measured in tens of seconds. */
private const val SAMPLE_MS = 1_000L
private const val TARGET_SECONDS = 30

@Composable
fun MicrowaveScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.MICROWAVE, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "The measurement is how many Bluetooth advertisements survive. " +
                        "SigEye never connects to any of them.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it.",
                ),
            ),
            footnote = "Nothing about the oven is read directly - only the damage it does " +
                "to the band.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val comparison = remember { AbComparison() }
    val packets = remember { AtomicInteger(0) }

    val health by BleScanHub.health.collectAsStateWithLifecycle()

    var phase by remember { mutableStateOf(AbComparison.Phase.IDLE) }
    var result by remember { mutableStateOf(comparison.result()) }
    var liveRate by remember { mutableStateOf(0.0) }
    var baselineSeries by remember { mutableStateOf<List<Double>>(emptyList()) }
    var testSeries by remember { mutableStateOf<List<Double>>(emptyList()) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { packets.incrementAndGet() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(SAMPLE_MS)
            val rate = packets.getAndSet(0) * 1000.0 / SAMPLE_MS
            liveRate = rate
            comparison.record(rate)
            result = comparison.result()
            baselineSeries = comparison.baselineSeries()
            testSeries = comparison.testSeries()
        }
    }

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

    // Live rate, the thing the whole experiment watches.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            String.format(Locale.US, "%.0f", liveRate),
            fontSize = 76.sp,
            fontWeight = FontWeight.Bold,
            color = when (phase) {
                AbComparison.Phase.TEST -> MaterialTheme.colorScheme.error
                AbComparison.Phase.BASELINE -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            "advertisements per second",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    when (phase) {
        AbComparison.Phase.IDLE -> {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Before you start",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Stand near the oven with the phone, and make sure there is " +
                            "something Bluetooth in the room to listen to - earbuds, a " +
                            "speaker, a watch. Put a mug of water in the oven so it has a " +
                            "load; running one empty is bad for it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    comparison.startBaseline()
                    phase = AbComparison.Phase.BASELINE
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Record baseline (oven off)") }
        }

        AbComparison.Phase.BASELINE -> PhasePanel(
            title = "Recording baseline",
            instruction = "Oven off. Stand still and leave the phone where it is - moving " +
                "it changes the reading on its own.",
            samples = result.baseline.samples,
            onNext = {
                comparison.startTest()
                phase = AbComparison.Phase.TEST
            },
            nextLabel = "Oven is running - record test",
            onCancel = {
                comparison.reset()
                phase = AbComparison.Phase.IDLE
            },
        )

        AbComparison.Phase.TEST -> PhasePanel(
            title = "Recording with the oven on",
            instruction = "Start the oven now if you have not. Keep the phone in exactly " +
                "the same place as the baseline.",
            samples = result.test.samples,
            onNext = {
                comparison.stop()
                phase = AbComparison.Phase.IDLE
            },
            nextLabel = "Stop and compare",
            onCancel = {
                comparison.reset()
                phase = AbComparison.Phase.IDLE
            },
        )
    }

    if (baselineSeries.isNotEmpty() || testSeries.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        RateTrace(
            baseline = baselineSeries,
            test = testSeries,
            baselineColour = MaterialTheme.colorScheme.primary,
            testColour = MaterialTheme.colorScheme.error,
            background = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            "Blue is the oven off, red is on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (result.baseline.samples >= 3 && result.test.samples >= 3) {
        Spacer(Modifier.height(16.dp))
        Verdict(result)
    }

    if (phase == AbComparison.Phase.IDLE && result.baseline.samples > 0) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                comparison.reset()
                baselineSeries = emptyList()
                testSeries = emptyList()
                result = comparison.result()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start over") }
    }
}

@Composable
private fun PhasePanel(
    title: String,
    instruction: String,
    samples: Int,
    onNext: () -> Unit,
    nextLabel: String,
    onCancel: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                instruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (samples.toFloat() / TARGET_SECONDS).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$samples of $TARGET_SECONDS seconds",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onNext,
        enabled = samples >= AbResult.MIN_SAMPLES,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(nextLabel) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

@Composable
private fun Verdict(result: AbResult) {
    val drop = result.percentChange
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (result.significance) {
                Significance.CLEAR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                result.significance.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Oven off", String.format(Locale.US, "%.0f", result.baseline.mean), "per sec")
                Stat("Oven on", String.format(Locale.US, "%.0f", result.test.mean), "per sec")
                drop?.let {
                    Stat(
                        if (it < 0) "Lost" else "Gained",
                        String.format(Locale.US, "%.0f%%", abs(it)),
                        "of packets",
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(interpret(result), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Plain words, including the case where the oven turned out to be well sealed. */
private fun interpret(result: AbResult): String {
    val change = result.percentChange
    return when {
        result.significance == Significance.INSUFFICIENT ->
            "Not enough of either phase to compare. Record at least five seconds of each, " +
                "and thirty is better."

        change == null -> "Nothing was being heard even with the oven off, so there is " +
            "nothing to lose. Move somewhere with a Bluetooth device in earshot."

        result.significance == Significance.NONE ->
            "No detectable difference. Either the oven is well sealed - modern ones often " +
                "are - or the device you were listening to is far enough away that its " +
                "packets were already scarce. That is a real result, and the answer to " +
                "\"is my microwave killing the Wi-Fi\" may simply be no."

        change < -50 ->
            "The oven swallowed about ${abs(change).roundToInt()}% of the advertisements " +
                "reaching this phone. That is a magnetron leaking hard into 2.45 GHz, " +
                "right where Bluetooth and 2.4 GHz Wi-Fi live. Anything on 5 GHz would " +
                "have been untouched."

        change < 0 ->
            "About ${abs(change).roundToInt()}% fewer advertisements got through with the " +
                "oven running. Enough to notice on a video call, not enough to break a " +
                "connection outright."

        else ->
            "The rate went up, which is not something an oven does. Something else " +
                "changed between the two phases - most likely the phone or the source " +
                "moved. Run it again without touching either."
    }
}

/** Both phases end to end, so the moment the oven started is visible. */
@Composable
private fun RateTrace(
    baseline: List<Double>,
    test: List<Double>,
    baselineColour: Color,
    testColour: Color,
    background: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        drawRect(background)
        val all = baseline + test
        if (all.size < 2) return@Canvas
        val high = all.max().coerceAtLeast(1.0)
        val stepX = size.width / (all.size - 1)

        fun draw(series: List<Double>, offset: Int, colour: Color) {
            var previous: Offset? = null
            series.forEachIndexed { index, value ->
                val x = (index + offset) * stepX
                val y = size.height - 6f - ((value / high).toFloat() * (size.height - 12f))
                val point = Offset(x, y)
                previous?.let { drawLine(colour, it, point, strokeWidth = 2.5f) }
                previous = point
            }
        }

        draw(baseline, 0, baselineColour)
        if (test.isNotEmpty()) {
            // Join the two so the transition is a line, not a gap.
            if (baseline.isNotEmpty()) {
                val lastBaseline = Offset(
                    (baseline.size - 1) * stepX,
                    size.height - 6f -
                        ((baseline.last() / high).toFloat() * (size.height - 12f)),
                )
                val firstTest = Offset(
                    baseline.size * stepX,
                    size.height - 6f - ((test.first() / high).toFloat() * (size.height - 12f)),
                )
                drawLine(testColour, lastBaseline, firstTest, strokeWidth = 2.5f)
            }
            draw(test, baseline.size, testColour)
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
