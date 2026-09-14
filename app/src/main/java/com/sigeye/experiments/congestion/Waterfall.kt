package com.sigeye.experiments.congestion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.rf.ChannelLoad
import com.sigeye.core.analysis.rf.Spectrum

/**
 * The band over time, in the display every radio person already knows how to read.
 *
 * A bar chart says what the air looks like now. A waterfall says what it has been doing,
 * and that is the difference between "channel 6 is busy" and "channel 6 is busy every
 * evening and channel 11 went busy ten minutes ago". Frequency runs left to right, time
 * runs down, and brightness is power.
 *
 * It also settles the argument nobody wins with a bar chart. A 2.4 GHz Wi-Fi channel is
 * about 20 MHz wide and the channels are numbered 5 MHz apart, so channel 4 is sitting on
 * top of channels 1 through 8. On a waterfall that overlap is a smear you can see rather
 * than a fact somebody has to take on faith, which is why 1, 6 and 11 are the only three
 * that do not tread on each other.
 */
@Composable
fun SpectrumWaterfall(
    channels: List<ChannelLoad>,
    modifier: Modifier = Modifier,
) {
    // One row per scan rather than per frame. Wi-Fi scan results arrive every few seconds
    // and painting the same row sixty times a second would draw a smooth picture of
    // nothing happening.
    val history = remember { mutableStateListOf<List<ChannelLoad>>() }

    LaunchedEffect(channels) {
        if (channels.isEmpty()) return@LaunchedEffect
        history.add(channels)
        while (history.size > ROWS) history.removeAt(0)
    }

    if (history.isEmpty()) {
        Text(
            "Waiting for the first scan. Wi-Fi has to be switched on for the phone to scan " +
                "at all, though it does not have to be connected to anything.",
            modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val cold = MaterialTheme.colorScheme.surfaceVariant
    val warm = MaterialTheme.colorScheme.primary
    val hot = MaterialTheme.colorScheme.error
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "2.4 GHz, last ${history.size} scans",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text("newest at the top", style = MaterialTheme.typography.labelSmall, color = faint)
        }
        Spacer(Modifier.height(4.dp))

        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val rows = history.asReversed()
            val rowHeight = size.height / rows.size
            val widest = rows.first().size.coerceAtLeast(1)
            val cellWidth = size.width / widest

            rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { index, load ->
                    drawRect(
                        color = heat(load, cold, warm, hot),
                        topLeft = Offset(index * cellWidth, rowIndex * rowHeight),
                        size = Size(cellWidth + 1f, rowHeight + 1f),
                    )
                }
            }

            // Where the three non-overlapping channels sit. Drawn over the heat so the
            // eye can check whether the busy smears line up with them or fall between.
            val marks = listOf(1, 6, 11)
            rows.first().forEachIndexed { index, load ->
                if (load.channel in marks) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f),
                        start = Offset(index * cellWidth + cellWidth / 2, 0f),
                        end = Offset(index * cellWidth + cellWidth / 2, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("ch 1", "ch 6", "ch 11").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = faint)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("quiet", style = MaterialTheme.typography.labelSmall, color = faint)
            Canvas(Modifier.padding(horizontal = 6.dp).width(90.dp).height(8.dp)) {
                val steps = 24
                repeat(steps) { step ->
                    val t = step / (steps - 1f)
                    drawRect(
                        color = blend(cold, warm, hot, t),
                        topLeft = Offset(size.width * t, 0f),
                        size = Size(size.width / steps + 1f, size.height),
                    )
                }
            }
            Text("loud", style = MaterialTheme.typography.labelSmall, color = faint)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Frequency across, time downward, brightness is how much power is landing on " +
                "that channel. A Wi-Fi channel here is about 20 MHz wide and the numbers are " +
                "5 MHz apart, so anything parked on channel 4 is smeared across 1 through 8. " +
                "The three white lines are 1, 6 and 11, the only trio that does not overlap.",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
        )
    }
}

/** Where a channel's load sits between quiet and loud, on the same scale the bars use. */
private fun heat(load: ChannelLoad, cold: Color, warm: Color, hot: Color): Color {
    val dbm = load.loadDbm ?: return cold.copy(alpha = 0.35f)
    val t = ((dbm - FLOOR_DBM) / (Spectrum.BUSY_DBM - FLOOR_DBM)).coerceIn(0.0, 1.0)
    return blend(cold, warm, hot, t.toFloat())
}

/** Two stops rather than one, so the busy end separates instead of saturating. */
private fun blend(cold: Color, warm: Color, hot: Color, t: Float): Color = when {
    t < 0.5f -> lerp(cold, warm, t * 2f)
    else -> lerp(warm, hot, (t - 0.5f) * 2f)
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)

/**
 * Rows kept.
 *
 * Scans arrive every few seconds, so this is a few minutes of history. Enough to see a
 * microwave start or a neighbor's access point move channel, and small enough that the
 * whole thing is redrawn without thinking about it.
 */
private const val ROWS = 40

/** Below this there is effectively nothing on the channel. */
private const val FLOOR_DBM = -95.0
