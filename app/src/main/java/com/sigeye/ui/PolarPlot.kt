package com.sigeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.sigeye.core.analysis.rf.Sector
import com.sigeye.core.analysis.rf.SweepResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Signal strength plotted against compass heading.
 *
 * Radius is strength, so a notch in the outline is a direction something was absorbing
 * from. North is up, which makes the plot readable against the room you took it in.
 *
 * Unsampled sectors are drawn as gaps rather than interpolated across: a plot that closes
 * a hole it never measured invents the very shape the experiment is looking for.
 *
 * A sector needs several readings before it is drawn as measured, which used to leave the
 * whole plot blank through most of a first turn - the experiment looked broken to someone
 * who was visibly turning round. The outer ring fixes that without lying: it fills in as
 * each sector is visited, showing progress around the circle separately from the
 * measurement itself.
 */
@Composable
fun PolarPlot(
    result: SweepResult,
    modifier: Modifier = Modifier,
    liveHeading: Float? = null,
    minSamplesPerSector: Int = 3,
    /**
     * What the four axes are called, clockwise from the top.
     *
     * Compass points by default, because that is what a heading sweep is. A roll sweep
     * plots the same shape against a different quantity entirely, and labelling its axes
     * north and east would be a plain lie about what was measured.
     */
    axisLabels: List<String> = listOf("N", "E", "S", "W"),
) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val line = MaterialTheme.colorScheme.primary
    val peakColour = MaterialTheme.colorScheme.tertiary
    val notchColour = MaterialTheme.colorScheme.error
    val needle = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val measurer = rememberTextMeasurer()

    // What the plot says, for anybody who cannot see it. The peak, the notch, the depth
    // between them: the same three facts the caption underneath carries.
    val spoken = buildString {
        val peak = result.peakBearingDegrees
        val notch = result.notchBearingDegrees
        val depth = result.frontToBackDb
        if (peak == null || notch == null || depth == null) {
            append("Polar plot, not enough of the circle covered to read yet. ")
            append("${(result.coverage * 100).roundToInt()} percent covered.")
        } else {
            append("Polar plot. Strongest at ${peak.roundToInt()} degrees, ")
            append("weakest at ${notch.roundToInt()}, ")
            append("${depth.roundToInt()} decibels between them. ")
            append("${(result.coverage * 100).roundToInt()} percent of the circle covered.")
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = spoken },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f * 0.78f

            val settled = result.sectors.filter { it.samples >= minSamplesPerSector }
            // Scale to the measured span, not an absolute dB range - a 4 dB notch on a
            // strong signal should be as visible as a 20 dB one on a weak signal.
            val strongest = settled.maxOfOrNull { it.meanRssi } ?: -40.0
            val weakest = settled.minOfOrNull { it.meanRssi } ?: -100.0
            val span = (strongest - weakest).coerceAtLeast(6.0)
            val floor = weakest - span * 0.35

            fun radiusFor(mean: Double): Float {
                val fraction = ((mean - floor) / (strongest - floor)).coerceIn(0.0, 1.0)
                return (maxRadius * (0.15 + fraction * 0.85)).toFloat()
            }

            repeat(4) { ring ->
                drawCircle(
                    color = grid,
                    radius = maxRadius * (ring + 1) / 4f,
                    center = center,
                    style = Stroke(width = 1f),
                )
            }
            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                val radians = compassToRadians(angle)
                drawLine(
                    color = grid,
                    start = center,
                    end = Offset(
                        center.x + maxRadius * cos(radians),
                        center.y + maxRadius * sin(radians),
                    ),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
            drawAxisLabels(measurer, center, maxRadius, label, axisLabels)
            drawCoverageRing(
                sectors = result.sectors,
                minSamples = minSamplesPerSector,
                center = center,
                radius = maxRadius,
                partial = line.copy(alpha = 0.25f),
                complete = line.copy(alpha = 0.7f),
            )

            if (settled.isNotEmpty()) {
                drawSweepOutline(
                    sectors = result.sectors,
                    minSamples = minSamplesPerSector,
                    center = center,
                    radiusFor = ::radiusFor,
                    fill = fill,
                    line = line,
                )

                result.peak?.let {
                    drawMarker(center, it, ::radiusFor, peakColour)
                }
                result.notch?.let {
                    drawMarker(center, it, ::radiusFor, notchColour)
                }
            }

            liveHeading?.let { heading ->
                val radians = compassToRadians(heading)
                drawLine(
                    color = needle,
                    start = center,
                    end = Offset(
                        center.x + maxRadius * cos(radians),
                        center.y + maxRadius * sin(radians),
                    ),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

/**
 * The progress ring: one tick per sector, faint once visited and solid once measured.
 *
 * Deliberately outside the plot area and in a different weight, so it reads as "how far
 * round have I got" rather than as data.
 */
private fun DrawScope.drawCoverageRing(
    sectors: List<Sector>,
    minSamples: Int,
    center: Offset,
    radius: Float,
    partial: Color,
    complete: Color,
) {
    if (sectors.isEmpty()) return
    val width = 360f / sectors.size
    val ringRadius = radius * 1.12f
    sectors.forEach { sector ->
        if (sector.samples <= 0) return@forEach
        // Compass degrees run clockwise from north; Canvas angles run clockwise from east.
        val start = sector.centerDegrees - width / 2f - 90f
        drawArc(
            color = if (sector.samples >= minSamples) complete else partial,
            startAngle = start + 1f,
            sweepAngle = width - 2f,
            useCenter = false,
            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
            size = Size(ringRadius * 2f, ringRadius * 2f),
            style = Stroke(width = 5f),
        )
    }
}

/**
 * Draws the measured outline, breaking it wherever a run of sectors was not measured.
 */
private fun DrawScope.drawSweepOutline(
    sectors: List<Sector>,
    minSamples: Int,
    center: Offset,
    radiusFor: (Double) -> Float,
    fill: Color,
    line: Color,
) {
    var run = mutableListOf<Sector>()

    fun flush() {
        if (run.size >= 2) {
            val path = Path()
            run.forEachIndexed { index, sector ->
                val radians = compassToRadians(sector.centerDegrees)
                val radius = radiusFor(sector.meanRssi)
                val point = Offset(
                    center.x + radius * cos(radians),
                    center.y + radius * sin(radians),
                )
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            // Closing back through the center shows the wedge that was measured without
            // implying anything about the sectors that were not.
            val closed = Path().apply {
                addPath(path)
                lineTo(center.x, center.y)
                close()
            }
            drawPath(closed, color = fill)
            drawPath(path, color = line, style = Stroke(width = 2.5f))
        }
        run = mutableListOf()
    }

    // Walk twice round so a run spanning north is not split at the seam.
    val doubled = sectors + sectors
    var started = false
    doubled.forEachIndexed { index, sector ->
        if (sector.samples >= minSamples) {
            if (index < sectors.size || started) run.add(sector)
        } else {
            flush()
            started = true
        }
        if (index == sectors.size - 1 && run.size == sectors.size) {
            // Full circle with no gaps: close the loop and stop.
            flush()
            return
        }
    }
    flush()
}

private fun DrawScope.drawMarker(
    center: Offset,
    sector: Sector,
    radiusFor: (Double) -> Float,
    color: Color,
) {
    val radians = compassToRadians(sector.centerDegrees)
    val radius = radiusFor(sector.meanRssi)
    val point = Offset(center.x + radius * cos(radians), center.y + radius * sin(radians))
    drawCircle(color = color, radius = 6f, center = point)
    drawCircle(color = color.copy(alpha = 0.35f), radius = 12f, center = point)
}

/** Compass degrees to screen radians, with north up and east to the right. */
private fun compassToRadians(degrees: Float): Float =
    ((degrees - 90f) * PI.toFloat() / 180f)

private fun DrawScope.drawAxisLabels(
    measurer: TextMeasurer,
    center: Offset,
    maxRadius: Float,
    color: Color,
    labels: List<String>,
) {
    labels.take(4).forEachIndexed { index, text ->
        val angle = index * 90f
        val radians = compassToRadians(angle)
        val layout = measurer.measure(
            text = text,
            style = TextStyle(fontSize = 11.sp, color = color),
        )
        val radius = maxRadius + 14f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                center.x + radius * cos(radians) - layout.size.width / 2f,
                center.y + radius * sin(radians) - layout.size.height / 2f,
            ),
        )
    }
}
