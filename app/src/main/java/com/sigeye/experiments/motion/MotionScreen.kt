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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.MotionConfig
import com.sigeye.core.analysis.MotionDetector
import com.sigeye.core.analysis.MotionEvent
import com.sigeye.core.analysis.MotionReading
import com.sigeye.core.analysis.MotionState
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PauseBar
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

    val feedback = remember { Feedback(context) }

    var reading by remember { mutableStateOf<MotionReading?>(null) }
    var running by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<Double>>(emptyList()) }
    var events by remember { mutableStateOf<List<MotionEvent>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }

    // Settings
    var sensitivity by remember { mutableStateOf(3.0f) }
    var sigmaFloor by remember { mutableStateOf(0.8f) }
    var levelWeight by remember { mutableStateOf(1.0f) }
    var jitterWeight by remember { mutableStateOf(3.0f) }
    var calibrationSeconds by remember { mutableStateOf(20f) }
    var agreement by remember { mutableStateOf(2f) }
    var holdTicks by remember { mutableStateOf(2f) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BOTH) }
    var manual by remember { mutableStateOf<Set<String>>(emptySet()) }
    var useManual by remember { mutableStateOf(false) }

    // Candidate picker, for choosing links by hand.
    var candidates by remember { mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap()) }
    var pickerPaused by remember { mutableStateOf(false) }
    var frozenCandidates by remember { mutableStateOf<List<Triple<String, Int, Long>>>(emptyList()) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            detector.observe(advert.address, advert.rssi, advert.atMs)
            candidates = candidates + (advert.address to (advert.rssi to advert.atMs))
        }
    }

    LaunchedEffect(pickerPaused, candidates, running) {
        if (!pickerPaused && !running) {
            val now = System.currentTimeMillis()
            frozenCandidates = candidates.entries
                .filter { now - it.value.second < 12_000 }
                .sortedByDescending { it.value.first }
                .take(20)
                .map { Triple(it.key, it.value.first, it.value.second) }
        }
    }

    LaunchedEffect(
        sensitivity, sigmaFloor, levelWeight, jitterWeight,
        calibrationSeconds, agreement, holdTicks, useManual, manual,
    ) {
        detector.config = MotionConfig(
            calibrationSeconds = calibrationSeconds.roundToInt(),
            sensitivity = sensitivity.toDouble(),
            sigmaFloor = sigmaFloor.toDouble(),
            levelWeight = levelWeight.toDouble(),
            jitterWeight = jitterWeight.toDouble(),
            minAgreement = agreement.roundToInt(),
            ticksToFire = holdTicks.roundToInt(),
            manualReferences = if (useManual && manual.isNotEmpty()) manual else null,
        )
    }

    DisposableEffect(Unit) { onDispose { feedback.release() } }

    LaunchedEffect(running) {
        while (running) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val previous = reading?.state
            val next = detector.tick(now)
            reading = next
            events = detector.eventLog()
            // Announce the transition, not the state - otherwise it buzzes twice a second
            // for as long as someone stands in the room.
            if (previous != MotionState.MOTION && next.state == MotionState.MOTION) {
                feedback.alert(alertStyle, urgent = true)
            }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !useManual,
                onClick = { useManual = false },
                label = { Text("Pick links automatically", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = useManual,
                onClick = { useManual = true },
                label = { Text("Choose them myself", style = MaterialTheme.typography.labelSmall) },
            )
        }

        Text(
            if (useManual) {
                "Choose links that cross where someone would actually walk - a beacon on " +
                    "the far side of a doorway makes a tripwire. Chosen links skip the " +
                    "usual filters, so a marginal one is kept if you insist."
            } else {
                "Keeps whichever nearby devices are chatty enough and were sitting still. " +
                    "Fine for a room; it cannot know which links cross the route you care " +
                    "about."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (useManual) {
            Spacer(Modifier.height(10.dp))
            PauseBar(
                paused = pickerPaused,
                onToggle = { pickerPaused = !pickerPaused },
                summary = "${manual.size} chosen of ${frozenCandidates.size} nearby",
            )
            Spacer(Modifier.height(4.dp))
            frozenCandidates.forEach { (address, rssi, _) ->
                val chosen = manual.contains(address)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            manual = if (chosen) manual - address else manual + address
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = chosen,
                        onCheckedChange = {
                            manual = if (chosen) manual - address else manual + address
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            notes[address.uppercase()]?.nickname
                                ?: Vendors.byAddress(address)
                                ?: address,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("$rssi", style = MaterialTheme.typography.labelMedium)
                }
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
            enabled = !useManual || manual.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Calibrate and start") }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { showSettings = !showSettings },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showSettings) "Hide settings" else "Settings") }

        if (showSettings) {
            Spacer(Modifier.height(10.dp))
            Settings(
                sensitivity = sensitivity, onSensitivity = { sensitivity = it },
                sigmaFloor = sigmaFloor, onSigmaFloor = { sigmaFloor = it },
                levelWeight = levelWeight, onLevelWeight = { levelWeight = it },
                jitterWeight = jitterWeight, onJitterWeight = { jitterWeight = it },
                calibrationSeconds = calibrationSeconds,
                onCalibrationSeconds = { calibrationSeconds = it },
                agreement = agreement, onAgreement = { agreement = it },
                holdTicks = holdTicks, onHoldTicks = { holdTicks = it },
                alertStyle = alertStyle, onAlertStyle = { alertStyle = it },
            )
        }
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

    // Which symptom is actually responding. Without this the combined score is a single
    // number with no way to tell whether level or jitter is doing the work, which makes
    // the weights below impossible to tune.
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat(
            "Level",
            String.format(Locale.US, "%.1f", snap.levelScore),
            "signal shifted",
        )
        Stat(
            "Jitter",
            String.format(Locale.US, "%.1f", snap.jitterScore),
            "signal unsteady",
        )
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

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { showSettings = !showSettings },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (showSettings) "Hide settings" else "Settings") }

    if (showSettings) {
        Spacer(Modifier.height(10.dp))
        Settings(
            sensitivity = sensitivity, onSensitivity = { sensitivity = it },
            sigmaFloor = sigmaFloor, onSigmaFloor = { sigmaFloor = it },
            levelWeight = levelWeight, onLevelWeight = { levelWeight = it },
            jitterWeight = jitterWeight, onJitterWeight = { jitterWeight = it },
            calibrationSeconds = calibrationSeconds,
            onCalibrationSeconds = { calibrationSeconds = it },
            agreement = agreement, onAgreement = { agreement = it },
            holdTicks = holdTicks, onHoldTicks = { holdTicks = it },
            alertStyle = alertStyle, onAlertStyle = { alertStyle = it },
        )
        Text(
            "Sensitivity, weights and alerts take effect immediately. Calibration length " +
                "and chosen links apply the next time you calibrate.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

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
                            "calm %.0f now %.0f · sigma %.1f · level %.1f jitter %.1f%s",
                            reference.baselineMean,
                            reference.currentMean,
                            reference.baselineSigma,
                            reference.levelScore,
                            reference.jitterScore,
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

/**
 * Every knob, each with the recommendation that makes it usable.
 *
 * A setting without advice is worse than no setting: it hands over a number whose right
 * value the user has no way to reason about, and the honest default was at least chosen
 * with the physics in mind.
 */
@Composable
private fun Settings(
    sensitivity: Float, onSensitivity: (Float) -> Unit,
    sigmaFloor: Float, onSigmaFloor: (Float) -> Unit,
    levelWeight: Float, onLevelWeight: (Float) -> Unit,
    jitterWeight: Float, onJitterWeight: (Float) -> Unit,
    calibrationSeconds: Float, onCalibrationSeconds: (Float) -> Unit,
    agreement: Float, onAgreement: (Float) -> Unit,
    holdTicks: Float, onHoldTicks: (Float) -> Unit,
    alertStyle: AlertStyle, onAlertStyle: (AlertStyle) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Setting(
            label = "Trigger level",
            value = String.format(Locale.US, "%.1f sigma", sensitivity),
            advice = "How far a link must stray from its own calm behaviour. Start at 3. " +
                "Drop toward 2 if someone can cross the room without tripping it; raise " +
                "toward 5 if a fan or a curtain keeps firing it.",
        ) {
            Slider(
                value = sensitivity,
                onValueChange = onSensitivity,
                valueRange = 1.5f..8f,
                steps = 25,
            )
        }

        Setting(
            label = "Noise floor",
            value = String.format(Locale.US, "%.1f dB", sigmaFloor),
            advice = "The smallest wobble treated as normal. This is the control to reach " +
                "for when nothing triggers unless you stand next to the phone: a very " +
                "steady link gets its deviations divided by this, so a high floor makes a " +
                "good link deaf. Try 0.5 with beacons on a shelf; raise to 2 if the room " +
                "is busy and it will not settle.",
        ) {
            Slider(
                value = sigmaFloor,
                onValueChange = onSigmaFloor,
                valueRange = 0.3f..4f,
                steps = 36,
            )
        }

        Setting(
            label = "Weight on signal shift",
            value = String.format(Locale.US, "%.1f", levelWeight),
            advice = "Catches someone standing in the path and blocking it. Works close " +
                "in, does little across a room. Leave at 1.",
        ) {
            Slider(
                value = levelWeight,
                onValueChange = onLevelWeight,
                valueRange = 0f..4f,
                steps = 15,
            )
        }

        Setting(
            label = "Weight on unsteadiness",
            value = String.format(Locale.US, "%.1f", jitterWeight),
            advice = "Catches someone moving anywhere in the room, because a body shifts " +
                "the reflections long before it blocks anything. This is the sensitive " +
                "one - raise it to 4 or 5 for detection at a distance.",
        ) {
            Slider(
                value = jitterWeight,
                onValueChange = onJitterWeight,
                valueRange = 0f..6f,
                steps = 23,
            )
        }

        Setting(
            label = "Calibration",
            value = "${calibrationSeconds.roundToInt()} s",
            advice = "Time spent learning what calm looks like. Twenty is plenty in a " +
                "quiet room. Longer only helps if the room itself is restless - and " +
                "whatever moves during it is learned as normal, so leave the room.",
        ) {
            Slider(
                value = calibrationSeconds,
                onValueChange = onCalibrationSeconds,
                valueRange = 10f..60f,
                steps = 24,
            )
        }

        Setting(
            label = "Links that must agree",
            value = agreement.roundToInt().toString(),
            advice = "Guards against one device glitching. Two is right for automatic " +
                "selection. Set it to 1 when you have deliberately chosen a single " +
                "tripwire link - otherwise it can never fire.",
        ) {
            Slider(
                value = agreement,
                onValueChange = onAgreement,
                valueRange = 1f..4f,
                steps = 2,
            )
        }

        Setting(
            label = "Hold before firing",
            value = String.format(Locale.US, "%.1f s", holdTicks * 0.5f),
            advice = "Sustained disturbance needed before it calls motion. Half a second " +
                "catches someone walking briskly past; two seconds ignores a door " +
                "swinging shut.",
        ) {
            Slider(
                value = holdTicks,
                onValueChange = onHoldTicks,
                valueRange = 1f..8f,
                steps = 6,
            )
        }

        Text("Alert", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AlertStyle.entries.forEach { style ->
                FilterChip(
                    selected = alertStyle == style,
                    onClick = { onAlertStyle(style) },
                    label = {
                        Text(style.label, style = MaterialTheme.typography.labelSmall)
                    },
                )
            }
        }
        Text(
            alertStyle.hint + " It fires once when motion starts, not continuously while " +
                "someone is there.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Setting(
    label: String,
    value: String,
    advice: String,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        control()
        Text(
            advice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
