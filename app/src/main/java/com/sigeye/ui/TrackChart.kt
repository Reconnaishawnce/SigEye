package com.sigeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One line on the chart: a device's signal over time, and where it changed address. */
data class Track2D(
    val label: String,
    /** Time in milliseconds against signal in dBm, in time order. */
    val points: List<Pair<Long, Int>>,
    /** Times at which this device put on a new address. */
    val eventsMs: List<Long> = emptyList(),
)

/**
 * Several devices' signal drawn on one time axis, with address changes marked.
 *
 * The point of putting them together is that a rotation is invisible in isolation and
 * obvious in company: one line carries straight on through a vertical marker while the
 * others do nothing, and that continuity across the change is the claim being made. A
 * reader can see whether the line really is continuous, or whether the level jumped at
 * exactly the moment the app decided two addresses were one device.
 *
 * Deliberately a plain line chart with no smoothing. Smoothing a signal trace across a
 * handover would draw the very continuity the chart exists to let someone check.
 */
@Composable
fun TrackChart(
    tracks: List<Track2D>,
    modifier: Modifier = Modifier,
    heightDp: Int = 200,
) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.secondary,
    )
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val labelColour = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    val drawn = tracks.filter { it.points.isNotEmpty() }
    if (drawn.isEmpty()) {
        Text(
            "Nothing to draw yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val earliest = drawn.minOf { track -> track.points.minOf { it.first } }
    val latest = drawn.maxOf { track -> track.points.maxOf { it.first } }
    val span = (latest - earliest).coerceAtLeast(1L)

    // A fixed dB window rather than one scaled to the data: the comparison is between
    // devices, and rescaling each chart to its own range would make a strong device and a
    // weak one look identical.
    val strongest = -30f
    val weakest = -100f

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val plotHeight = size.height - 16f

                fun x(atMs: Long): Float = (atMs - earliest).toFloat() / span * size.width
                fun y(dbm: Int): Float {
                    val fraction = ((dbm - weakest) / (strongest - weakest)).coerceIn(0f, 1f)
                    return plotHeight - fraction * plotHeight
                }

                listOf(-40, -60, -80).forEach { level ->
                    val at = y(level)
                    drawLine(
                        color = grid,
                        start = Offset(0f, at),
                        end = Offset(size.width, at),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                    )
                    val layout = measurer.measure(
                        text = "$level",
                        style = TextStyle(fontSize = 8.sp, color = labelColour),
                    )
                    drawText(layout, topLeft = Offset(2f, at - layout.size.height))
                }

                drawn.forEachIndexed { index, track ->
                    val colour = palette[index % palette.size]

                    track.eventsMs.forEach { at ->
                        drawLine(
                            color = colour.copy(alpha = 0.5f),
                            start = Offset(x(at), 0f),
                            end = Offset(x(at), plotHeight),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
                        )
                    }

                    val path = Path()
                    var started = false
                    var previousMs = 0L
                    track.points.forEach { (atMs, rssi) ->
                        val point = Offset(x(atMs), y(rssi))
                        // A gap in the packets is a gap in the line. Joining across one
                        // would draw a device that was audible when it was not.
                        val broken = started && atMs - previousMs > GAP_MS
                        if (!started || broken) path.moveTo(point.x, point.y) else {
                            path.lineTo(point.x, point.y)
                        }
                        started = true
                        previousMs = atMs
                    }
                    drawPath(path, color = colour, style = Stroke(width = 2f))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            drawn.forEachIndexed { index, track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = palette[index % palette.size],
                        modifier = Modifier.size(10.dp, 3.dp),
                    ) {}
                    Text(
                        " ${track.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Colour a caller can use to match its own labels to the chart's lines. */
@Composable
fun trackColour(index: Int): Color = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.secondary,
)[index % 4]

/** Longer than this between readings and the line is broken rather than joined. */
private const val GAP_MS = 20_000L
