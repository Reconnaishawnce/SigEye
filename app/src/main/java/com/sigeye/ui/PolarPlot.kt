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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.sigeye.core.analysis.Sector
import com.sigeye.core.analysis.SweepResult
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Signal strength plotted against compass heading.
 *
 * Radius is strength, so a notch in the outline is a direction something was absorbing
 * from. North is up, which makes the plot readable against the room you took it in.
 *
 * Unsampled sectors are drawn as gaps rather than interpolated across: a plot that closes
 * a hole it never measured invents the very shape the experiment is looking for.
 */
@Composable
fun PolarPlot(
    result: SweepResult,
    modifier: Modifier = Modifier,
    liveHeading: Float? = null,
    minSamplesPerSector: Int = 3,
) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val line = MaterialTheme.colorScheme.primary
    val peakColour = MaterialTheme.colorScheme.tertiary
    val notchColour = MaterialTheme.colorScheme.error
    val needle = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val measurer = rememberTextMeasurer()

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
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
                    center = centre,
                    style = Stroke(width = 1f),
                )
            }
            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                val radians = compassToRadians(angle)
                drawLine(
                    color = grid,
                    start = centre,
                    end = Offset(
                        centre.x + maxRadius * cos(radians),
                        centre.y + maxRadius * sin(radians),
                    ),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
            drawCompassLabels(measurer, centre, maxRadius, label)

            if (settled.isNotEmpty()) {
                drawSweepOutline(
                    sectors = result.sectors,
                    minSamples = minSamplesPerSector,
                    centre = centre,
                    radiusFor = ::radiusFor,
                    fill = fill,
                    line = line,
                )

                result.peak?.let {
                    drawMarker(centre, it, ::radiusFor, peakColour)
                }
                result.notch?.let {
                    drawMarker(centre, it, ::radiusFor, notchColour)
                }
            }

            liveHeading?.let { heading ->
                val radians = compassToRadians(heading)
                drawLine(
                    color = needle,
                    start = centre,
                    end = Offset(
                        centre.x + maxRadius * cos(radians),
                        centre.y + maxRadius * sin(radians),
                    ),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

/**
 * Draws the measured outline, breaking it wherever a run of sectors was not measured.
 */
private fun DrawScope.drawSweepOutline(
    sectors: List<Sector>,
    minSamples: Int,
    centre: Offset,
    radiusFor: (Double) -> Float,
    fill: Color,
    line: Color,
) {
    var run = mutableListOf<Sector>()

    fun flush() {
        if (run.size >= 2) {
            val path = Path()
            run.forEachIndexed { index, sector ->
                val radians = compassToRadians(sector.centreDegrees)
                val radius = radiusFor(sector.meanRssi)
                val point = Offset(
                    centre.x + radius * cos(radians),
                    centre.y + radius * sin(radians),
                )
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            // Closing back through the centre shows the wedge that was measured without
            // implying anything about the sectors that were not.
            val closed = Path().apply {
                addPath(path)
                lineTo(centre.x, centre.y)
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
    centre: Offset,
    sector: Sector,
    radiusFor: (Double) -> Float,
    colour: Color,
) {
    val radians = compassToRadians(sector.centreDegrees)
    val radius = radiusFor(sector.meanRssi)
    val point = Offset(centre.x + radius * cos(radians), centre.y + radius * sin(radians))
    drawCircle(color = colour, radius = 6f, center = point)
    drawCircle(color = colour.copy(alpha = 0.35f), radius = 12f, center = point)
}

/** Compass degrees to screen radians, with north up and east to the right. */
private fun compassToRadians(degrees: Float): Float =
    ((degrees - 90f) * PI.toFloat() / 180f)

private fun DrawScope.drawCompassLabels(
    measurer: TextMeasurer,
    centre: Offset,
    maxRadius: Float,
    colour: Color,
) {
    listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (text, angle) ->
        val radians = compassToRadians(angle)
        val layout = measurer.measure(
            text = text,
            style = TextStyle(fontSize = 11.sp, color = colour),
        )
        val radius = maxRadius + 14f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                centre.x + radius * cos(radians) - layout.size.width / 2f,
                centre.y + radius * sin(radians) - layout.size.height / 2f,
            ),
        )
    }
}
