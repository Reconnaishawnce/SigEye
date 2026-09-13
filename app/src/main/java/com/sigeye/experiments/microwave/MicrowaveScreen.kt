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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.RunFigure
import com.sigeye.core.analysis.rf.AbComparison
import com.sigeye.core.analysis.rf.AbResult
import com.sigeye.core.analysis.rf.ControlBand
import com.sigeye.core.analysis.rf.ControlRadios
import com.sigeye.core.analysis.rf.ControlVerdict
import com.sigeye.core.analysis.rf.Radio
import com.sigeye.core.analysis.rf.Significance
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.wifi.AccessPoint
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.ui.CountUpDecimal
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RunHistory
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "microwave"

/** One second per sample - fine against a phase measured in tens of seconds. */
private const val SAMPLE_MS = 1_000L
private const val TARGET_SECONDS = 30

/**
 * How often to ask for a Wi-Fi scan, for the control band.
 *
 * Android will not scan faster than about eight seconds however nicely it is asked, so the
 * control gets a handful of readings per phase where the advertisement count gets dozens.
 * That is why the control is judged on whether a band moved by more than three decibels
 * rather than on a t statistic it does not have the samples for.
 */
private const val WIFI_SCAN_MS = 8_000L

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
    val wifi by WifiScanHub.state.collectAsStateWithLifecycle()

    // The control. Two radios, pinned once at the start of the baseline and followed
    // through both phases whatever happens to them.
    val lowBand = remember { AbComparison() }
    val highBand = remember { AbComparison() }
    var control by remember { mutableStateOf(ControlRadios(null, null, sameBox = false)) }
    var lowResult by remember { mutableStateOf(lowBand.result()) }
    var highResult by remember { mutableStateOf(highBand.result()) }

    var phase by remember { mutableStateOf(AbComparison.Phase.IDLE) }
    var result by remember { mutableStateOf(comparison.result()) }
    var liveRate by remember { mutableStateOf(0.0) }
    var baselineSeries by remember { mutableStateOf<List<Double>>(emptyList()) }
    var testSeries by remember { mutableStateOf<List<Double>>(emptyList()) }

    /** Only the access points from the most recent scan: the hub keeps older ones too. */
    fun latestScan(): List<AccessPoint> =
        wifi.results.filter { it.seenAtMs >= wifi.lastScanAtMs }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        WifiScanHub.init(context)
        WifiScanHub.acquire(context, HUB_TAG)
        onDispose {
            BleScanHub.release(HUB_TAG)
            WifiScanHub.release(context, HUB_TAG)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            delay(WIFI_SCAN_MS)
        }
    }

    // One reading per scan, never one per second: recording the same scan thirty times
    // would flatten its variance to nothing and make any difference look certain.
    LaunchedEffect(wifi.scans) {
        if (wifi.scans == 0) return@LaunchedEffect
        val scan = latestScan().associate { it.bssid to it.rssi }
        ControlBand.levelOf(control.low?.bssid, scan)?.let { lowBand.record(it) }
        ControlBand.levelOf(control.high?.bssid, scan)?.let { highBand.record(it) }
        lowResult = lowBand.result()
        highResult = highBand.result()
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
        CountUpDecimal(
            value = liveRate,
            decimals = 0,
            fontSize = 76.sp,
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
                    control = ControlBand.choose(
                        latestScan().map {
                            Radio(it.bssid, it.ssid, it.frequencyMhz, it.rssi.toDouble())
                        },
                    )
                    comparison.startBaseline()
                    lowBand.startBaseline()
                    highBand.startBaseline()
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
                lowBand.startTest()
                highBand.startTest()
                phase = AbComparison.Phase.TEST
            },
            nextLabel = "Oven is running - record test",
            onCancel = {
                comparison.reset()
                lowBand.reset()
                highBand.reset()
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
                lowBand.stop()
                highBand.stop()
                phase = AbComparison.Phase.IDLE
            },
            nextLabel = "Stop and compare",
            onCancel = {
                comparison.reset()
                lowBand.reset()
                highBand.reset()
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

    if (phase != AbComparison.Phase.IDLE || control.usable) {
        Spacer(Modifier.height(12.dp))
        ControlCard(control, lowResult, highResult, phase)
    }

    if (result.baseline.samples >= 3 && result.test.samples >= 3) {
        val verdict = ControlBand.judge(lowResult, highResult)

        Spacer(Modifier.height(16.dp))
        Verdict(result, verdict)

        Spacer(Modifier.height(16.dp))
        RunHistory(
            experiment = Experiments.MICROWAVE,
            figures = listOfNotNull(
                RunFigure("Baseline", result.baseline.mean, "dBm", 1),
                RunFigure("With the oven on", result.test.mean, "dBm", 1),
                RunFigure("Change", result.delta, "dB", 1, higherIsBetter = true),
                result.tStatistic?.let { RunFigure("t statistic", it, decimals = 2) },
                RunFigure("Baseline packets", result.baseline.samples.toDouble(), decimals = 0),
                RunFigure("Test packets", result.test.samples.toDouble(), decimals = 0),
                lowResult.takeIf { ControlBand.enough(it) }?.let {
                    RunFigure("2.4 GHz control", it.delta, "dB", 1, higherIsBetter = true)
                },
                highResult.takeIf { ControlBand.enough(it) }?.let {
                    RunFigure("5 GHz control", it.delta, "dB", 1, higherIsBetter = true)
                },
            ),
            note = control.takeIf { it.usable }?.label(),
        )
    }

    if (phase == AbComparison.Phase.IDLE && result.baseline.samples > 0) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                comparison.reset()
                lowBand.reset()
                highBand.reset()
                control = ControlRadios(null, null, sameBox = false)
                baselineSeries = emptyList()
                testSeries = emptyList()
                result = comparison.result()
                lowResult = lowBand.result()
                highResult = highBand.result()
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
private fun Verdict(result: AbResult, control: ControlVerdict) {
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
            Text(interpret(result, control), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * What the two Wi-Fi radios did while the advertisement count was being taken.
 *
 * Shown from the moment a baseline starts rather than only at the end, because the commonest
 * way this experiment fails is having no control at all - one band with nothing audible in
 * it - and finding that out after running the oven for two minutes is annoying.
 */
@Composable
private fun ControlCard(
    control: ControlRadios,
    low: AbResult,
    high: AbResult,
    phase: AbComparison.Phase,
) {
    val verdict = ControlBand.judge(low, high)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (verdict) {
                ControlVerdict.ONLY_LOW_FELL -> MaterialTheme.colorScheme.errorContainer
                ControlVerdict.BOTH_FELL -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "The control band · ${verdict.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(control.describe(), style = MaterialTheme.typography.bodySmall)

            if (control.usable) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat(
                        "2.4 GHz",
                        String.format(Locale.US, "%.0f", nowOrBaseline(low, phase)),
                        "dBm · ${low.baseline.samples + low.test.samples} scans",
                    )
                    Stat(
                        "5 GHz",
                        String.format(Locale.US, "%.0f", nowOrBaseline(high, phase)),
                        "dBm · ${high.baseline.samples + high.test.samples} scans",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                ControlBand.explain(verdict, low, high),
                style = MaterialTheme.typography.bodySmall,
            )

            if (control.usable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "A scan that does not contain a pinned radio is recorded at " +
                        "${ControlBand.FLOOR_DBM.toInt()} dBm - below what this phone can " +
                        "hear - rather than skipped, so a radio that keeps vanishing counts " +
                        "against its own phase instead of quietly improving it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Whichever phase is running, so the card reads as live rather than as a summary. */
private fun nowOrBaseline(result: AbResult, phase: AbComparison.Phase): Double = when (phase) {
    AbComparison.Phase.TEST -> result.test.mean
    else -> result.baseline.mean
}

/** Plain words, including the case where the oven turned out to be well sealed. */
private fun interpret(result: AbResult, control: ControlVerdict): String {
    val change = result.percentChange

    // The control outranks the count. A drop with both bands down is not a finding about
    // an oven whatever the advertisement rate did, and saying so first is the point of
    // having a control at all.
    if (control == ControlVerdict.BOTH_FELL) {
        return "The advertisement count fell, but so did 5 GHz, and an oven cannot touch " +
            "5 GHz. Something changed between the two phases that was not the oven - " +
            "almost always the phone or the source moving. This one does not count."
    }

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
                "right where Bluetooth and 2.4 GHz Wi-Fi live." +
                when (control) {
                    ControlVerdict.ONLY_LOW_FELL ->
                        " The 5 GHz radio in the same room did not move, which is the " +
                            "cleanest version of this result you can get."
                    ControlVerdict.NEITHER_FELL ->
                        " Neither Wi-Fi band moved, though, so this hit the Bluetooth " +
                            "source rather than the whole band."
                    else ->
                        " Whether 5 GHz escaped is not measured here yet - run each phase " +
                            "for a minute and the control band will say."
                }

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
    val spoken = buildString {
        append("Packet rate over time. ")
        if (baseline.isEmpty()) {
            append("No baseline yet.")
        } else {
            append("Baseline averaged ${"%.1f".format(java.util.Locale.US, baseline.average())} per second")
            if (test.isEmpty()) {
                append(", test not started.")
            } else {
                append(", test ${"%.1f".format(java.util.Locale.US, test.average())} per second.")
            }
        }
    }

    Canvas(
        Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = spoken },
    ) {
        drawRect(background)
        val all = baseline + test
        if (all.size < 2) return@Canvas
        val high = all.max().coerceAtLeast(1.0)
        val stepX = size.width / (all.size - 1)

        fun draw(series: List<Double>, offset: Int, color: Color) {
            var previous: Offset? = null
            series.forEachIndexed { index, value ->
                val x = (index + offset) * stepX
                val y = size.height - 6f - ((value / high).toFloat() * (size.height - 12f))
                val point = Offset(x, y)
                previous?.let { drawLine(color, it, point, strokeWidth = 2.5f) }
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
