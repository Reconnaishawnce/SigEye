package com.sigeye.ui.radar

import com.sigeye.core.Clock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One thing on the radar. Strength is the only real coordinate. */
data class RadarTarget(
    val address: String,
    val label: String,
    /** Smoothed signal, so the blip does not twitch with every packet. */
    val smoothedRssi: Double,
    val flagged: Boolean = false,
    val watched: Boolean = false,
    /**
     * How far through going quiet this device is, 0 heard just now and 1 about to be
     * retired.
     *
     * The elimination is the measurement in Follow Me and it was invisible: a blip sat
     * there at full brightness for a minute and then vanished between one frame and the
     * next. Draining a ring around it turns the count falling into something you watch
     * happen rather than a number that changes when you are not looking.
     */
    val fading: Float = 0f,
)

private class Blip(
    val address: String,
    val angleDegrees: Float,
    /** Where it is being drawn now. Eases toward [targetFraction]. */
    var currentFraction: Float,
    var targetFraction: Float,
    var label: String,
    var flagged: Boolean,
    var watched: Boolean,
    var fading: Float,
    val appearedAtMs: Long,
    var leavingSinceMs: Long? = null,
    /** Set when the sweep line last crossed it, for the illumination pulse. */
    var lastSweptMs: Long = 0L,
) {
    /** Recent positions, for the comet trail. Oldest first. */
    val trail = ArrayDeque<Float>()
}

private const val APPEAR_MS = 900L
private const val LEAVE_MS = 900L
private const val SWEEP_PERIOD_MS = 3800L
private const val SWEPT_GLOW_MS = 900L

/** How fast a blip glides to a new radius. Lower is more languid. */
private const val EASE = 0.055f

/**
 * The radar.
 *
 * Two honesty rules it keeps, because a display this satisfying is exactly the kind that
 * gets over-read:
 *
 *  - **Angle is not direction.** One antenna cannot produce a bearing. The angle comes
 *    from a hash of the address so a device keeps its own spot, and the caller is expected
 *    to say so on screen.
 *  - **Radius is smoothed signal, not measured distance.** The estimator does the
 *    filtering; this only draws it.
 *
 * Everything else here is for feel. Blips glide rather than jump, the sweep illuminates
 * what it passes, and arrivals bloom - so a change catches the eye in a field of dots.
 */
@Composable
fun RadarScene(
    targets: List<RadarTarget>,
    /** Signal at the outer edge. Raise it to zoom into the near field. */
    outerDbm: Float,
    innerDbm: Float,
    selectedAddress: String?,
    onSelect: (String?) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How much bigger to draw the blips.
     *
     * This used to stay at one on purpose - zooming widened the near field rather than
     * magnifying anything, on the grounds that bigger dots carry no more information. In
     * use that reads as the zoom having done nothing, so the dots now grow with it. The
     * information is still in the radius; the size is feedback.
     */
    blipScale: Float = 1f,
) {
    val blips = remember { mutableStateMapOf<String, Blip>() }
    var frameMs by remember { mutableLongStateOf(Clock.nowMs()) }
    val measurer = rememberTextMeasurer()

    val grid = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val gridText = MaterialTheme.colorScheme.onSurfaceVariant
    val normal = MaterialTheme.colorScheme.primary
    val flaggedColour = MaterialTheme.colorScheme.error
    val watchedColour = MaterialTheme.colorScheme.tertiary
    val selectedColour = MaterialTheme.colorScheme.onSurface

    // One frame clock for every blip: hundreds of Animatables would be hundreds of
    // running coroutines in a busy street.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = Clock.nowMs() }
        }
    }

    LaunchedEffect(targets, outerDbm, innerDbm) {
        val now = Clock.nowMs()
        val byAddress = targets.associateBy { it.address }

        byAddress.forEach { (address, target) ->
            val fraction = fractionFor(target.smoothedRssi, innerDbm, outerDbm)
            val existing = blips[address]
            if (existing == null) {
                blips[address] = Blip(
                    address = address,
                    angleDegrees = angleFor(address),
                    // New blips fly in from the rim, which reads as arrival.
                    currentFraction = 1.05f,
                    targetFraction = fraction,
                    label = target.label,
                    flagged = target.flagged,
                    watched = target.watched,
                    fading = target.fading,
                    appearedAtMs = now,
                )
            } else {
                existing.targetFraction = fraction
                existing.label = target.label
                existing.flagged = target.flagged
                existing.watched = target.watched
                existing.fading = target.fading
                existing.leavingSinceMs = null
            }
        }

        blips.values.forEach { blip ->
            if (!byAddress.containsKey(blip.address) && blip.leavingSinceMs == null) {
                blip.leavingSinceMs = now
            }
        }
        blips.entries.removeAll { (_, blip) ->
            blip.leavingSinceMs?.let { now - it > LEAVE_MS } == true
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (abs(zoom - 1f) > 0.001f) onZoom(zoom)
                }
            }
            .pointerInput(blips, outerDbm, innerDbm) {
                detectTapGestures { tap ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = minOf(size.width, size.height) / 2f * 0.86f
                    val hit = blips.values
                        .map { it to positionOf(it, center, maxRadius) }
                        .filter { hypot(it.second.x - tap.x, it.second.y - tap.y) < 44f }
                        .minByOrNull { hypot(it.second.x - tap.x, it.second.y - tap.y) }
                    onSelect(hit?.first?.address)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f * 0.86f
            val sweepAngle = sweepAngle(frameMs)

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(normal.copy(alpha = 0.14f), Color.Transparent),
                    center = center,
                    radius = maxRadius,
                ),
                radius = maxRadius,
                center = center,
            )

            repeat(4) { ring ->
                val fraction = (ring + 1) / 4f
                drawCircle(
                    color = grid,
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = if (ring == 3) 2f else 1f),
                )
                val dbm = innerDbm + (outerDbm - innerDbm) * fraction
                drawRingLabel(measurer, "${dbm.toInt()}", center, maxRadius * fraction, gridText)
            }

            drawSweep(center, maxRadius, sweepAngle, normal)

            blips.values.forEach { blip ->
                // Ease toward the target so movement reads as gliding, not teleporting.
                blip.currentFraction += (blip.targetFraction - blip.currentFraction) * EASE
                if (frameMs - blip.lastSweptMs > SWEEP_PERIOD_MS / 2 &&
                    angleDelta(sweepAngle, blip.angleDegrees) < 6f
                ) {
                    blip.lastSweptMs = frameMs
                }
                // Trail samples, thinned so the deque stays short.
                if (blip.trail.isEmpty() ||
                    abs(blip.trail.last() - blip.currentFraction) > 0.004f
                ) {
                    blip.trail.addLast(blip.currentFraction)
                    while (blip.trail.size > 14) blip.trail.removeFirst()
                }

                drawBlip(
                    scale = blipScale.coerceIn(1f, 2.6f),
                    trails = blips.size <= TRAIL_LIMIT || blip.address == selectedAddress,
                    blip = blip,
                    center = center,
                    maxRadius = maxRadius,
                    nowMs = frameMs,
                    selected = blip.address == selectedAddress,
                    color = when {
                        blip.address == selectedAddress -> selectedColour
                        blip.flagged -> flaggedColour
                        blip.watched -> watchedColour
                        else -> normal
                    },
                    measurer = measurer,
                )
            }
        }
    }
}

private fun sweepAngle(nowMs: Long): Float =
    (nowMs % SWEEP_PERIOD_MS).toFloat() / SWEEP_PERIOD_MS * 360f

private fun angleDelta(a: Float, b: Float): Float {
    val raw = abs(a - b) % 360f
    return if (raw > 180f) 360f - raw else raw
}

private fun DrawScope.drawSweep(
    center: Offset,
    maxRadius: Float,
    angle: Float,
    color: Color,
) {
    // A short fading wedge behind the leading edge, which is what makes it read as a sweep
    // rather than a spinning stick.
    for (step in 0..14) {
        val trailing = angle - step * 2.2f
        val alpha = (1f - step / 14f) * 0.30f
        rotate(degrees = trailing, pivot = center) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = center,
                end = Offset(center.x, center.y - maxRadius),
                strokeWidth = 3f,
            )
        }
    }
}

private fun positionOf(blip: Blip, center: Offset, maxRadius: Float): Offset {
    val radians = (blip.angleDegrees - 90f) * PI.toFloat() / 180f
    val radius = maxRadius * blip.currentFraction.coerceIn(0.06f, 1.06f)
    return Offset(center.x + radius * cos(radians), center.y + radius * sin(radians))
}

/**
 * Above this many blips, trails are drawn only for whatever is selected.
 *
 * A trail is for watching one thing move. Forty of them overlapping is fog across the
 * rings, and the rings are the actual measurement.
 */
private const val TRAIL_LIMIT = 12

private fun DrawScope.drawBlip(
    blip: Blip,
    center: Offset,
    maxRadius: Float,
    nowMs: Long,
    selected: Boolean,
    color: Color,
    measurer: TextMeasurer,
    scale: Float,
    /** False in a crowd, where fourteen dots each becomes a smear rather than a trail. */
    trails: Boolean,
) {
    val position = positionOf(blip, center, maxRadius)
    val radians = (blip.angleDegrees - 90f) * PI.toFloat() / 180f

    val leaving = blip.leavingSinceMs
    if (leaving != null) {
        val progress = ((nowMs - leaving).toFloat() / LEAVE_MS).coerceIn(0f, 1f)
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.8f),
            radius = 5f * scale,
            center = position,
        )
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.4f),
            radius = (6f + progress * 22f) * scale,
            center = position,
            style = Stroke(width = 1.5f),
        )
        return
    }

    // Comet trail along the radius it travelled. The whole value of it is seeing one
    // thing move; on a concourse with forty blips it is just fog over the rings, and the
    // rings are the measurement.
    if (trails) blip.trail.forEachIndexed { index, fraction ->
        val alpha = (index + 1).toFloat() / blip.trail.size * 0.20f
        val radius = maxRadius * fraction.coerceIn(0.06f, 1.06f)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = 2.5f,
            center = Offset(center.x + radius * cos(radians), center.y + radius * sin(radians)),
        )
    }

    val age = nowMs - blip.appearedAtMs
    if (age < APPEAR_MS) {
        val progress = (age.toFloat() / APPEAR_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress)
        drawCircle(
            color = color.copy(alpha = (1f - eased) * 0.7f),
            radius = (5f + eased * 30f) * scale,
            center = position,
            style = Stroke(width = 2f),
        )
    }

    // The drop-off, drawn as it runs down. A device has to be silent for a full minute
    // before it counts as gone, and for that minute the old radar showed a blip at full
    // brightness with nothing to say it was on its way out.
    if (blip.fading > 0.02f) {
        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = -90f,
            sweepAngle = 360f * (1f - blip.fading.coerceIn(0f, 1f)),
            useCenter = false,
            topLeft = Offset(
                position.x - 11f * scale,
                position.y - 11f * scale,
            ),
            size = Size(22f * scale, 22f * scale),
            style = Stroke(width = 1.6f * scale),
        )
    }

    // Illumination: brief glow as the sweep passes, the way a real display refreshes.
    val sinceSwept = nowMs - blip.lastSweptMs
    val glow = if (sinceSwept in 0..SWEPT_GLOW_MS) {
        1f - sinceSwept.toFloat() / SWEPT_GLOW_MS
    } else {
        0f
    }

    val alive = 1f - blip.fading.coerceIn(0f, 1f) * 0.7f
    drawCircle(
        color = color.copy(alpha = (0.16f + glow * 0.42f) * alive),
        radius = (10f + glow * 8f) * scale,
        center = position,
    )
    drawCircle(
        color = color.copy(alpha = alive),
        radius = (if (selected) 6.5f else 4.5f) * scale,
        center = position,
    )

    if (selected) {
        drawCircle(
            color = color,
            radius = 15f * scale,
            center = position,
            style = Stroke(width = 2f),
        )
        val layout = measurer.measure(
            text = blip.label.take(22),
            style = TextStyle(fontSize = 10.sp, color = color),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                (position.x - layout.size.width / 2f)
                    .coerceIn(0f, size.width - layout.size.width),
                (position.y + 20f).coerceAtMost(size.height - layout.size.height),
            ),
        )
    }
}

/** 0 at the center, 1 at the rim. Strongest in the middle. */
private fun fractionFor(rssi: Double, innerDbm: Float, outerDbm: Float): Float {
    val span = (innerDbm - outerDbm).takeIf { abs(it) > 0.5f } ?: 1f
    val fraction = ((innerDbm - rssi) / span).toFloat()
    return fraction.coerceIn(0.06f, 1.04f)
}

/** Stable angle per address. Decorative - one antenna cannot know a bearing. */
private fun angleFor(address: String): Float {
    var hash = 0
    address.forEach { hash = hash * 31 + it.code }
    return (((hash % 360) + 360) % 360).toFloat()
}

private fun DrawScope.drawRingLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    radius: Float,
    color: Color,
) {
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = 8.sp, color = color.copy(alpha = 0.75f)),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - radius - layout.size.height - 1f,
        ),
    )
}
