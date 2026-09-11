package com.sigeye.experiments.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.MotionConfig
import com.sigeye.core.analysis.MotionDetector
import com.sigeye.core.analysis.MotionEvent
import com.sigeye.core.analysis.MotionReading
import com.sigeye.core.analysis.MotionState
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "motion"
private const val TICK_MS = 500L

@Composable
fun MotionScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.MOTION, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "The detector watches how steady existing Bluetooth links are. " +
                        "SigEye never connects to any of them.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it. Your location is never " +
                        "read or stored.",
                ),
            ),
            footnote = "No camera, no microphone. It only notices that the radio in the " +
                "room became unsteady.",
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
    val detector = remember { MotionDetector() }

    val notes by book.notes.collectAsStateWithLifecycle()
    val health by BleScanHub.health.collectAsStateWithLifecycle()

    var reading by remember { mutableStateOf<MotionReading?>(null) }
    var running by remember { mutableStateOf(false) }
    var sensitivity by remember { mutableStateOf(3.5f) }
    var history by remember { mutableStateOf<List<Double>>(emptyList()) }
    var events by remember { mutableStateOf<List<MotionEvent>>(emptyList()) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            detector.observe(advert.address, advert.rssi, advert.atMs)
        }
    }

    LaunchedEffect(sensitivity) {
        detector.config = MotionConfig(sensitivity = sensitivity.toDouble())
    }

    LaunchedEffect(running) {
        while (running) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val next = detector.tick(now)
            reading = next
            events = detector.eventLog()
            if (next.state != MotionState.CALIBRATING) {
                history = (history + next.score).takeLast(160)
            }
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

    val snap = reading
    if (!running || snap == null) {
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
                    "Put the phone down where it will stay, somewhere with a few " +
                        "Bluetooth things around it - a television, a speaker, earbuds on " +
                        "charge. Then leave the room, or at least hold still, for the " +
                        "twenty-five seconds it takes to learn what calm looks like.\n\n" +
                        "Anything moving during calibration gets learned as normal, " +
                        "which is the one way to make this useless.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                detector.startCalibration(System.currentTimeMillis())
                history = emptyList()
                events = emptyList()
                running = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Calibrate and start") }
        return
    }

    if (snap.state == MotionState.CALIBRATING) {
        Calibrating(snap)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { running = false },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel") }
        return
    }

    val stateColour by animateColorAsState(
        when (snap.state) {
            MotionState.MOTION -> MaterialTheme.colorScheme.error
            MotionState.STIRRING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        tween(400),
        label = "state",
    )

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            snap.state.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = stateColour,
        )
        Spacer(Modifier.height(4.dp))
        ScoreGauge(score = snap.score, threshold = snap.threshold, colour = stateColour)
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Links", "${snap.liveReferences}", "of ${snap.referenceCount} live")
        Stat("Disturbed", "${snap.disturbed}", "links agree")
        Stat("Events", "${events.size}", "so far")
    }

    Spacer(Modifier.height(14.dp))
    ScoreTrace(
        history = history,
        threshold = snap.threshold,
        colour = MaterialTheme.colorScheme.primary,
        alert = MaterialTheme.colorScheme.error,
        background = MaterialTheme.colorScheme.surfaceVariant,
    )
    Text(
        "Disturbance over the last minute or so. The dashed line is the trigger.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    if (snap.liveReferences == 0) {
        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                "No usable links. Calibration keeps only devices that advertise often " +
                    "enough and sat still while it watched - if everything nearby was " +
                    "moving or dozing, there is nothing to measure against. Move closer " +
                    "to something stationary and calibrate again.",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Sensitivity: " + when {
            sensitivity < 2.5f -> "twitchy"
            sensitivity < 4.5f -> "balanced"
            else -> "only obvious movement"
        } + String.format(Locale.US, "  (%.1f sigma)", sensitivity),
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = sensitivity,
        onValueChange = { sensitivity = it },
        valueRange = 1.5f..8f,
        steps = 25,
    )
    Text(
        "How far a link has to stray from its own calm behaviour before it counts. " +
            "Lower catches someone across the room and also the cat.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (snap.references.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Reference links",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        snap.references.forEach { reference ->
            val name = notes[reference.address.uppercase()]?.nickname
                ?: Vendors.byAddress(reference.address)
                ?: reference.address
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (reference.live) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "%.0f dBm calm, sigma %.1f, %.1f/s%s",
                            reference.baselineMean,
                            reference.baselineSigma,
                            reference.packetsPerSecond,
                            if (reference.live) "" else " · silent",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    String.format(Locale.US, "%.1f", reference.score),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (reference.score >= snap.threshold) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    if (events.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Events", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        events.take(30).forEach { event ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    clock.format(Date(event.startedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    String.format(
                        Locale.US,
                        "%.1fs · peak %.1f · %d links",
                        event.durationMs(System.currentTimeMillis()) / 1000.0,
                        event.peakScore,
                        event.peakDisturbed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(
        onClick = {
            detector.startCalibration(System.currentTimeMillis())
            history = emptyList()
            events = emptyList()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Recalibrate") }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = {
            running = false
            detector.reset()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Stop") }
}

@Composable
private fun Calibrating(reading: MotionReading) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Learning the room",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Hold still, or step out. Whatever is moving now becomes the definition " +
                    "of normal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { reading.calibrationProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Disturbance against the trigger, as an arc that fills past the line. */
@Composable
private fun ScoreGauge(score: Double, threshold: Double, colour: Color) {
    val fraction = (score / (threshold * 2)).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(fraction, tween(350), label = "score")
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val markColour = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f * 0.80f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 16f),
            )
            drawArc(
                color = colour,
                startAngle = 135f,
                sweepAngle = 270f * animated,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 16f),
            )
            // The trigger sits at half scale by construction.
            val markAngle = Math.toRadians((135f + 270f * 0.5f).toDouble())
            drawLine(
                color = markColour,
                start = Offset(
                    centre.x + (radius - 14f) * kotlin.math.cos(markAngle).toFloat(),
                    centre.y + (radius - 14f) * kotlin.math.sin(markAngle).toFloat(),
                ),
                end = Offset(
                    centre.x + (radius + 14f) * kotlin.math.cos(markAngle).toFloat(),
                    centre.y + (radius + 14f) * kotlin.math.sin(markAngle).toFloat(),
                ),
                strokeWidth = 3f,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                String.format(Locale.US, "%.1f", score),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                String.format(Locale.US, "trigger %.1f", threshold),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScoreTrace(
    history: List<Double>,
    threshold: Double,
    colour: Color,
    alert: Color,
    background: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        drawRect(background)
        if (history.size < 2) return@Canvas
        val ceiling = maxOf(history.max(), threshold * 1.6)
        val stepX = size.width / (history.size - 1)

        val y = size.height - 6f - ((threshold / ceiling).toFloat() * (size.height - 12f))
        drawLine(
            color = alert.copy(alpha = 0.7f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )

        var previous: Offset? = null
        history.forEachIndexed { index, value ->
            val pointY = size.height - 6f -
                ((value / ceiling).toFloat() * (size.height - 12f))
            val point = Offset(index * stepX, pointY)
            previous?.let {
                drawLine(
                    color = if (value >= threshold) alert else colour,
                    start = it,
                    end = point,
                    strokeWidth = 2.5f,
                )
            }
            previous = point
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
