package com.sigeye.experiments.locate

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.rf.ProximityEstimator
import com.sigeye.core.analysis.rf.ProximityReading
import com.sigeye.core.analysis.rf.Trend
import com.sigeye.core.ble.Arrivals
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "locate"

/**
 * Hunt one device down by walking.
 *
 * Deliberately says almost nothing about direction, because it cannot know any. What it
 * offers instead is a very responsive answer to "warmer or colder", which is enough to
 * find something by sweeping a room - the same way a metal detector works.
 */
@Composable
fun LocateScreen(
    address: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("← Back")
        }
        Text("Locate", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text(
            address,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To follow one device's signal. SigEye never connects to it.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it.",
                ),
            ),
            footnote = "Signal strength only. Nothing here reveals a direction.",
        ) {
            Hunt(address)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Hunt(address: String) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val estimator = remember(address) { ProximityEstimator() }
    val vibrator = remember { vibratorOf(context) }

    val notes by book.notes.collectAsStateWithLifecycle()

    var reading by remember { mutableStateOf<ProximityReading?>(null) }
    var history by remember { mutableStateOf<List<Double>>(emptyList()) }
    var lastHeardMs by remember { mutableStateOf(0L) }
    var pathLoss by remember { mutableStateOf(2.0f) }
    var buzz by remember { mutableStateOf(true) }
    var stale by remember { mutableStateOf(false) }
    var arrivals by remember(address) { mutableStateOf<List<Long>>(emptyList()) }

    val health by BleScanHub.health.collectAsStateWithLifecycle()

    DisposableEffect(address) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        // Everything else in the app wants the whole room. This screen wants one device,
        // and asking the controller for one device by address is the difference between
        // hearing it and hearing it occasionally when the air is busy.
        BleScanHub.focus(address)
        onDispose {
            BleScanHub.focus(null)
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(address) {
        BleScanHub.adverts.collect { advert ->
            if (!advert.address.equals(address, ignoreCase = true)) return@collect
            estimator.pathLossExponent = pathLoss.toDouble()
            val next = estimator.observe(advert.rssi, advert.atMs)
            reading = next
            lastHeardMs = advert.atMs
            arrivals = Arrivals.record(arrivals, advert.atMs)
            history = (history + next.smoothedRssi).takeLast(120)
        }
    }

    val gapMs = remember(arrivals) { Arrivals.typicalGapMs(arrivals) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            stale = lastHeardMs > 0 && System.currentTimeMillis() - lastHeardMs > 6_000
        }
    }

    // Pulse rate rises as the signal does, like a metal detector. Eyes on the room,
    // not on the screen, is the whole point of a locator.
    LaunchedEffect(buzz, reading?.smoothedRssi?.roundToInt()) {
        val current = reading
        if (!buzz || current == null || vibrator == null || stale) return@LaunchedEffect
        val strength = ((current.smoothedRssi + 100) / 55.0).coerceIn(0.0, 1.0)
        val gap = (1_400 - strength * 1_250).toLong().coerceAtLeast(90L)
        while (true) {
            pulse(vibrator, (18 + strength * 28).toInt())
            delay(gap)
        }
    }

    val current = reading
    if (current == null) {
        Text(
            "Listening for this device...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val warm = when (current.trend) {
        Trend.CLOSER -> MaterialTheme.colorScheme.primary
        Trend.FURTHER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trendColour by animateColorAsState(warm, tween(600), label = "trend")

    Arrivals.advice(gapMs, health.crowded)?.let { advice ->
        Card(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    Arrivals.describe(gapMs),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    advice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    if (stale) {
        Card(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                "Lost it. Nothing heard for a few seconds - walk back the way you came. " +
                    "If it had a randomized address it may simply have changed identity.",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StrengthDial(reading = current, color = trendColour)
        Spacer(Modifier.height(6.dp))
        Text(
            current.trend.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = trendColour,
        )
        Text(
            notes[address.uppercase()]?.nickname
                ?: Vendors.byAddress(address)
                ?: "unnamed device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Now", "${current.smoothedRssi.roundToInt()}", "dBm")
        Stat("Range", String.format(Locale.US, "~%.1f m", current.meters), current.zone.label)
        Stat("Closest", "${current.bestRssi}", "dBm best")
    }

    Spacer(Modifier.height(14.dp))
    Trace(history = history, color = MaterialTheme.colorScheme.primary)
    Text(
        "Last two minutes. Rising is warmer.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "How to actually find it",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Walk a few paces, then stop and wait two seconds for the reading to " +
                    "settle. Repeat. Chasing the number while moving will send you in " +
                    "circles, because reflections make it jump.\n\n" +
                    "Your own body blocks the signal, so turn slowly on the spot: the " +
                    "direction where it reads strongest is roughly where the device is. " +
                    "That is the Body Absorption experiment doing double duty as a " +
                    "compass.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Buzz as it warms", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Faster pulses when the signal is stronger, so you can watch the room " +
                    "instead of the screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = buzz, onCheckedChange = { buzz = it })
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Environment: " + when {
            pathLoss < 2.2f -> "open space"
            pathLoss < 2.8f -> "ordinary room"
            else -> "walls between you"
        } + String.format(Locale.US, "  (n = %.1f)", pathLoss),
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(value = pathLoss, onValueChange = { pathLoss = it }, valueRange = 1.8f..3.5f, steps = 16)
    Text(
        "Only changes the meters figure, never the trend. If the distance reads wrong " +
            "for something you can see, adjust this until it matches - that number is " +
            "then roughly right for this building.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            estimator.reset()
            history = emptyList()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Reset hunt") }
}

/** Big circular strength gauge. The one thing readable at arm's length. */
@Composable
private fun StrengthDial(reading: ProximityReading, color: Color) {
    val fraction = ((reading.smoothedRssi + 100) / 55.0).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(fraction, tween(900), label = "strength")
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)

    val spoken = "Signal strength ${reading.smoothedRssi.roundToInt()} dBm, " +
        "roughly ${"%.1f".format(java.util.Locale.US, reading.meters)} metres away, " +
        "${reading.zone.name.lowercase()}, ${reading.trend.name.lowercase()}."

    Box(
        Modifier.fillMaxWidth().height(230.dp).semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f * 0.82f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = track, radius = radius, center = center, style = Stroke(width = 18f))
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * animated,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = 18f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                reading.trend.arrow,
                fontSize = 34.sp,
                color = color,
            )
            Text(
                "${reading.smoothedRssi.roundToInt()}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "dBm",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Signal history. Scaled to what was actually seen, so small changes stay visible. */
@Composable
private fun Trace(history: List<Double>, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val spoken = if (history.size < 2) {
        "Signal history, not enough readings yet."
    } else {
        "Signal history, ${history.size} readings from ${history.min().roundToInt()} to " +
            "${history.max().roundToInt()} dBm."
    }
    Canvas(
        Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = spoken },
    ) {
        drawRect(track)
        if (history.size < 2) return@Canvas
        val high = history.max()
        val low = history.min()
        val span = (high - low).coerceAtLeast(4.0)
        val stepX = size.width / (history.size - 1)
        var previous: Offset? = null
        history.forEachIndexed { index, value ->
            val y = (size.height - 6f) -
                (((value - low) / span).toFloat() * (size.height - 12f))
            val point = Offset(index * stepX, y)
            previous?.let { drawLine(color, it, point, strokeWidth = 2.5f) }
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

private fun vibratorOf(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

private fun pulse(vibrator: Vibrator, millis: Int) {
    runCatching {
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                millis.toLong().coerceIn(10L, 80L),
                VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
    }
}
