package com.sigeye.experiments.trainspotter

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.ui.Section
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A fortnight of a street, in one picture.
 *
 * Train Spotter records a bin every eight seconds for as long as it is left running, and
 * every one of those went into a CSV and nowhere else. Days of somebody's windowsill sat
 * on the phone as text. This reads them back: one row per day, time of day across,
 * brightness is the biggest burst in each five minutes.
 *
 * What it is for is the shape rather than the detail. When the first train goes, whether
 * the evening peak is the same every night, whether Sunday is a different place. A live
 * chart of the last ten minutes cannot show any of that, and it is the thing days of
 * recording were for.
 */
@Composable
fun DayStripCard(config: PulseConfig) {
    val context = LocalContext.current
    var strip by remember { mutableStateOf<DayStrip.Strip?>(null) }

    // Off the main thread. This reads every CSV on the phone, which after a fortnight of
    // unattended running is a few megabytes.
    LaunchedEffect(config.binMillis) {
        strip = withContext(Dispatchers.IO) { load(context) }
    }

    val built = strip ?: return
    if (built.empty) return

    Section(
        title = "The shape of this place",
        summary = "${built.days.size} days recorded, busiest five minutes " +
            "${built.ceiling} new devices.",
        initiallyExpanded = built.days.size >= 2,
    ) {
        Text(
            "One row per day, midnight to midnight. Each block is five minutes, and its " +
                "brightness is the biggest burst inside it rather than the average - a " +
                "train is thirty seconds of a hundred devices in a quiet hour, and an " +
                "average would draw the hour instead of the train.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))

        val cold = MaterialTheme.colorScheme.surfaceVariant
        val warm = MaterialTheme.colorScheme.primary
        val hot = MaterialTheme.colorScheme.error
        val faint = MaterialTheme.colorScheme.onSurfaceVariant

        built.days.forEach { day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.label,
                    Modifier.width(52.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = faint,
                )
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val cell = size.width / DayStrip.COLUMNS
                    day.buckets.forEachIndexed { column, value ->
                        // Nothing recorded is drawn as nothing, not as quiet. A gap in the
                        // listening and a quiet street are different facts.
                        val colour = if (value == null) {
                            Color.Transparent
                        } else {
                            blend(cold, warm, hot, value.toFloat() / built.ceiling)
                        }
                        drawRect(
                            color = colour,
                            topLeft = Offset(column * cell, 0f),
                            size = Size(cell + 0.6f, size.height),
                        )
                    }
                    // Bins somebody marked as a train, over the top. These are the only
                    // ground truth in the whole experiment.
                    day.marked.forEach { column ->
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(column * cell, 0f),
                            size = Size((cell + 0.6f).coerceAtLeast(1.5f), size.height * 0.28f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("", Modifier.width(52.dp))
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = faint)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("quiet", style = MaterialTheme.typography.labelSmall, color = faint)
            Canvas(Modifier.padding(horizontal = 6.dp).width(84.dp).height(8.dp)) {
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
            Text(
                "${built.ceiling} in five minutes",
                style = MaterialTheme.typography.labelSmall,
                color = faint,
            )
        }

        if (built.marks > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The white ticks are the ${built.marks} bins you marked as a real train. " +
                    "They are the only ground truth here, and they are what the threshold " +
                    "below is fitted to.",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Blank means nothing was recorded, which is a different thing from quiet.",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
        )
    }
}

/** Reads every recorded day back off the phone. */
private fun load(context: Context): DayStrip.Strip? {
    val dir: File = context.getExternalFilesDir(null) ?: return null
    val files = dir.listFiles { f -> f.name.startsWith("sigeye-") && f.name.endsWith(".csv") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) return null

    val bins = files.flatMap { file ->
        runCatching { file.readLines().mapNotNull(ThresholdFit::parse) }.getOrDefault(emptyList())
    }
    return if (bins.isEmpty()) null else DayStrip.build(bins)
}

/** Two stops rather than one, so the busy end separates instead of saturating. */
private fun blend(cold: Color, warm: Color, hot: Color, t: Float): Color = when {
    t < 0.5f -> lerp(cold, warm, (t * 2f).coerceIn(0f, 1f))
    else -> lerp(warm, hot, ((t - 0.5f) * 2f).coerceIn(0f, 1f))
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)
