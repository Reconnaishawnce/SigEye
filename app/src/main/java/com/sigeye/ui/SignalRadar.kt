package com.sigeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One thing to plot. Direction is unknowable from one antenna, so only strength is real. */
data class RadarTarget(val address: String, val rssi: Int)

private data class Blip(
    val address: String,
    /** Stable pseudo-angle derived from the address, so a device does not wander. */
    val angleDegrees: Float,
    var rssi: Int,
    val appearedAtMs: Long,
    var leavingSinceMs: Long? = null,
)

private const val APPEAR_MS = 1100L
private const val LEAVE_MS = 900L
private const val SWEEP_PERIOD_MS = 4200L

/** Ring boundaries in dBm, strongest first. The innermost ring is nearest. */
private val RING_DBM = intArrayOf(-50, -65, -80, -95)

/**
 * A radar that is honest about what it does not know.
 *
 * One antenna cannot tell you direction, so the angle here is decorative - derived from a
 * hash of the address purely so a device keeps the same spot between frames instead of
 * jittering. Only the radius carries information: distance from the centre is signal
 * strength, strongest at the middle.
 *
 * Arrivals bloom outward and departures fade, because in a room full of dots a change is
 * the only thing worth noticing, and a dot that simply blinks into existence is missed.
 */
@Composable
fun SignalRadar(
    targets: List<RadarTarget>,
    modifier: Modifier = Modifier,
    ringColor: Color = Color(0xFF2E7D57),
    blipColor: Color = Color(0xFF7FE3A3),
) {
    val blips = remember { mutableStateMapOf<String, Blip>() }
    var frameMs by remember { mutableLongStateOf(0L) }
    val measurer = rememberTextMeasurer()

    // One frame clock drives every animation, rather than an Animatable per device -
    // a busy street would otherwise mean hundreds of running animations.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = System.currentTimeMillis() }
        }
    }

    LaunchedEffect(targets) {
        val now = System.currentTimeMillis()
        val present = targets.associateBy { it.address }

        present.forEach { (address, target) ->
            val existing = blips[address]
            if (existing == null) {
                blips[address] = Blip(
                    address = address,
                    angleDegrees = angleFor(address),
                    rssi = target.rssi,
                    appearedAtMs = now,
                )
            } else {
                existing.rssi = target.rssi
                // Came back before the fade finished: cancel the departure.
                existing.leavingSinceMs = null
            }
        }

        blips.values.forEach { blip ->
            if (!present.containsKey(blip.address) && blip.leavingSinceMs == null) {
                blip.leavingSinceMs = now
            }
        }
        blips.entries.removeAll { (_, blip) ->
            blip.leavingSinceMs?.let { now - it > LEAVE_MS } == true
        }
    }

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f * 0.88f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ringColor.copy(alpha = 0.16f), Color.Transparent),
                    center = centre,
                    radius = maxRadius,
                ),
                radius = maxRadius,
                center = centre,
            )

            RING_DBM.forEachIndexed { index, dbm ->
                val radius = maxRadius * (index + 1) / RING_DBM.size
                drawCircle(
                    color = ringColor.copy(alpha = 0.35f),
                    radius = radius,
                    center = centre,
                    style = Stroke(width = 1.5f),
                )
                drawRingLabel(measurer, "$dbm", centre, radius, ringColor)
            }

            drawSweep(centre, maxRadius, frameMs, ringColor)

            blips.values.forEach { blip ->
                drawBlip(blip, centre, maxRadius, frameMs, blipColor)
            }
        }
    }
}

/** Rotating sweep, so a quiet radar still looks alive rather than broken. */
private fun DrawScope.drawSweep(
    centre: Offset,
    maxRadius: Float,
    nowMs: Long,
    color: Color,
) {
    val phase = (nowMs % SWEEP_PERIOD_MS).toFloat() / SWEEP_PERIOD_MS
    rotate(degrees = phase * 360f, pivot = centre) {
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
                start = centre,
                end = Offset(centre.x + maxRadius, centre.y),
            ),
            start = centre,
            end = Offset(centre.x + maxRadius, centre.y),
            strokeWidth = 2.5f,
        )
    }
}

private fun DrawScope.drawBlip(
    blip: Blip,
    centre: Offset,
    maxRadius: Float,
    nowMs: Long,
    color: Color,
) {
    val radius = maxRadius * radiusFraction(blip.rssi)
    val radians = blip.angleDegrees * PI.toFloat() / 180f
    val position = Offset(
        centre.x + radius * cos(radians),
        centre.y + radius * sin(radians),
    )

    val leaving = blip.leavingSinceMs
    if (leaving != null) {
        // Departure: drift outward and fade out.
        val progress = ((nowMs - leaving).toFloat() / LEAVE_MS).coerceIn(0f, 1f)
        val drift = 1f + progress * 0.25f
        val faded = Offset(
            centre.x + radius * drift * cos(radians),
            centre.y + radius * drift * sin(radians),
        )
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.8f),
            radius = 5f * (1f - progress * 0.5f),
            center = faded,
        )
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.35f),
            radius = 5f + progress * 16f,
            center = faded,
            style = Stroke(width = 1.5f),
        )
        return
    }

    val age = nowMs - blip.appearedAtMs
    if (age < APPEAR_MS) {
        // Arrival: a ring blooms outward and the dot settles in.
        val progress = (age.toFloat() / APPEAR_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress)
        drawCircle(
            color = color.copy(alpha = (1f - eased) * 0.7f),
            radius = 5f + eased * 26f,
            center = position,
            style = Stroke(width = 2f),
        )
        drawCircle(
            color = color.copy(alpha = 0.35f + eased * 0.65f),
            radius = 4f + (1f - eased) * 5f,
            center = position,
        )
        return
    }

    // Settled: a gentle breath so the display never looks frozen.
    val breath = 0.5f + 0.5f * sin((nowMs % 2600L) / 2600f * 2f * PI.toFloat())
    drawCircle(color = color.copy(alpha = 0.22f), radius = 8f + breath * 2f, center = position)
    drawCircle(color = color, radius = 4f, center = position)
}

/**
 * Strongest at the centre. Clamped so an unusually strong or weak reading still lands on
 * the display rather than off the edge of it.
 */
private fun radiusFraction(rssi: Int): Float {
    val strongest = RING_DBM.first().toFloat()
    val weakest = RING_DBM.last().toFloat()
    val clamped = rssi.toFloat().coerceIn(weakest, strongest)
    val fraction = (strongest - clamped) / (strongest - weakest)
    // Keep a little space at the very centre so blips do not pile onto the origin.
    return 0.12f + fraction * 0.88f
}

/** Stable angle per address. Decorative - one antenna cannot know a bearing. */
private fun angleFor(address: String): Float {
    var hash = 0
    address.forEach { hash = hash * 31 + it.code }
    return ((hash % 360) + 360) % 360f
}

private fun DrawScope.drawRingLabel(
    measurer: TextMeasurer,
    text: String,
    centre: Offset,
    radius: Float,
    color: Color,
) {
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = 8.sp, color = color.copy(alpha = 0.7f)),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            centre.x - layout.size.width / 2f,
            centre.y - radius - layout.size.height - 1f,
        ),
    )
}
