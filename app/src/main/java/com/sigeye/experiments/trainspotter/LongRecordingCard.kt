package com.sigeye.experiments.trainspotter

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Clock
import com.sigeye.core.ScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZONE: ZoneId = ZoneId.systemDefault()
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

private enum class Length(val label: String, val ms: Long?) {
    HOUR("1 hour", 3_600_000L),
    TWELVE("12 hours", 12 * 3_600_000L),
    DAY("24 hours", 24 * 3_600_000L),
    TWO_DAYS("48 hours", 48 * 3_600_000L),
    OPEN("No limit", null),
}

private enum class Span(val label: String, val ms: Long?) {
    HOUR("Last hour", 3_600_000L),
    SIX("Last 6 h", 6 * 3_600_000L),
    ALL("All", null),
}

/**
 * A day of trains on one graph.
 *
 * The live chart above shows the last few minutes. This records for as long as you like,
 * one row a second, and draws the whole thing: tall bars where trains came through, a line
 * for how busy the place was, and a summary of first train, last train and rush hour.
 */
@Composable
fun LongRecordingCard() {
    val context = LocalContext.current
    val recording by TrainRecorder.state.collectAsStateWithLifecycle()
    val pulse by PulseState.state.collectAsStateWithLifecycle()

    var length by remember { mutableStateOf(Length.DAY) }
    var span by remember { mutableStateOf(Span.ALL) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var loaded by remember { mutableStateOf<List<Bucket>?>(null) }
    var past by remember(recording.active) { mutableStateOf(TrainRecorder.recordings(context)) }
    val scope = rememberCoroutineScope()

    // A past recording is read off disk away from the main thread. A day is 86,400 rows.
    LaunchedEffect(viewing) {
        val file = viewing ?: run { loaded = null; return@LaunchedEffect }
        loaded = withContext(Dispatchers.IO) {
            TrainDay.buckets(file.useLines { TrainCsv.parseAll(it) })
        }
    }

    val showing: List<Bucket> = loaded ?: recording.buckets
    val summary = remember(showing) { TrainDay.summarize(showing, ZONE) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Long recording", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Records every second for hours, then shows when the trains came and how " +
                    "busy they were. Leave the phone plugged in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (recording.active) {
                Running(recording)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { TrainRecorder.stop() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop recording")
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Length.entries.forEach { option ->
                        FilterChip(
                            selected = option == length,
                            onClick = { length = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!pulse.running) ScanService.start(context, ScanService.Mode.TRAIN_SPOTTER)
                        val config = SettingsStore(context).load()
                        val started = TrainRecorder.start(
                            context = context,
                            durationMs = length.ms,
                            forgetMs = config.windowMillis,
                            rssiFloor = config.rssiFloor,
                            nowMs = Clock.nowMs(),
                        )
                        viewing = null
                        if (!started) {
                            Toast.makeText(context, "Could not open a file to record to.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start recording") }
            }

            if (showing.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                viewing?.let {
                    Text(
                        "Showing ${it.nameWithoutExtension}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Span.entries.forEach { option ->
                        FilterChip(
                            selected = option == span,
                            onClick = { span = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val window = remember(showing, span) { inWindow(showing, span.ms) }
                TrainChart(window, summary.events)
                Spacer(Modifier.height(10.dp))
                Summary(summary)
            }

            if (past.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Saved recordings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                past.take(8).forEach { file ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "%.1f MB".format(Locale.US, file.length() / 1_048_576.0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewing = if (viewing == file) null else file }) {
                            Text(if (viewing == file) "Hide" else "View")
                        }
                        TextButton(onClick = { scope.launch { export(context, file) } }) { Text("Export") }
                    }
                }
                if (past.size > 8) {
                    Text(
                        "${past.size - 8} older recordings are in the train folder.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { past = TrainRecorder.recordings(context) }) { Text("Refresh list") }
            }
        }
    }
}

@Composable
private fun Running(recording: LongRecording) {
    val now = recording.latest?.atMs ?: recording.startedAtMs
    val elapsed = (now - recording.startedAtMs).coerceAtLeast(0L)
    Text(
        "Recording for ${duration(elapsed)}" +
            (recording.endsAtMs?.let { " of ${duration(it - recording.startedAtMs)}" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    recording.latest?.let { second ->
        Text(
            if (second.settling) {
                "Settling in. The first minute is skipped because everything looks new."
            } else {
                "${second.new6} new in the last 6 seconds · ${second.present60} devices around"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "${recording.rows} rows · %.1f MB".format(Locale.US, recording.bytes / 1_048_576.0) +
            if (recording.missedSeconds > 0) " · ${recording.missedSeconds}s missed" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Summary(summary: TrainSummary) {
    val events = summary.events
    Stat("Trains", if (events.isEmpty()) "None yet" else "${events.size}")

    if (summary.days.size == 1) {
        val day = summary.days.first()
        Stat("First train", time(day.firstMs))
        Stat("Last train", time(day.lastMs))
    } else {
        summary.days.forEach { day ->
            Stat(DAY.format(day.date), "${day.trains} trains, ${time(day.firstMs)} to ${time(day.lastMs)}")
        }
    }

    summary.busiestHour?.let { hour ->
        Stat("Busiest hour", "${hourLabel(hour.hour)}: ${hour.trains} trains, about ${hour.devices} devices")
    }
    summary.biggest?.let { train ->
        Stat("Fullest train", "${time(train.startMs)}, about ${train.size} devices")
    }
    Stat("Devices around", "${summary.meanPresent} on average, ${summary.peakPresent} at most")
    if (summary.missedSeconds > 60) {
        Stat("Gaps", "${duration(summary.missedSeconds * 1000)} not recorded")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Bars are arrivals, so each train is a spike. The line is how many devices were around,
 * so a busy afternoon sits higher than a quiet night even between trains. Red dots mark
 * what was counted as a train.
 */
@Composable
private fun TrainChart(buckets: List<Bucket>, events: List<TrainEvent>) {
    val drawn = remember(buckets) { TrainDay.downsample(buckets, 600) }
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val dotColor = MaterialTheme.colorScheme.error
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    if (drawn.isEmpty()) return
    val startMs = drawn.first().startMs
    val endMs = buckets.last().startMs + TrainDay.BUCKET_MS
    val visible = events.filter { it.peakMs in startMs..endMs }

    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val spanMs = (endMs - startMs).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = (t - startMs) / spanMs * size.width
        val top = 10.dp.toPx()
        val usable = size.height - top
        val maxNew = drawn.maxOf { it.new }.coerceAtLeast(1)
        val maxPresent = drawn.maxOf { it.present }.coerceAtLeast(1)
        val step = size.width / drawn.size

        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())

        drawn.forEach { bucket ->
            val height = bucket.new.toFloat() / maxNew * usable
            if (height > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(x(bucket.startMs), size.height - height),
                    size = Size(step.coerceAtLeast(1f), height),
                )
            }
        }

        val line = Path()
        drawn.forEachIndexed { index, bucket ->
            val px = x(bucket.startMs) + step / 2
            val py = size.height - bucket.present.toFloat() / maxPresent * usable
            if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        drawPath(line, lineColor, style = Stroke(width = 1.5.dp.toPx()))

        visible.forEach { train ->
            drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(x(train.peakMs), top / 2))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(startMs), style = MaterialTheme.typography.labelSmall)
        Text("bars: arrivals · line: devices around", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time(endMs), style = MaterialTheme.typography.labelSmall)
    }
}

private fun inWindow(buckets: List<Bucket>, spanMs: Long?): List<Bucket> {
    if (spanMs == null || buckets.isEmpty()) return buckets
    val from = buckets.last().startMs - spanMs
    return buckets.filter { it.startMs >= from }
}

private fun time(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(ZONE))

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/**
 * Sends the recording and a one-row-per-train summary of it together.
 *
 * The trains file is worked out at export time from the recording, so it always matches
 * the settings the app has now rather than whatever it had when the recording was made.
 */
private suspend fun export(context: Context, recording: File) {
    runCatching {
        // A day is 86,400 rows, which is too much to read on the thread drawing the screen.
        val trains = withContext(Dispatchers.IO) {
            val buckets = TrainDay.buckets(recording.useLines { TrainCsv.parseAll(it) })
            File(recording.parentFile, recording.nameWithoutExtension + "-trains.csv").also {
                it.writeText(TrainDay.eventsCsv(TrainDay.events(buckets), ZONE))
            }
        }

        val uris = ArrayList(
            listOf(recording, trains).map {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
            },
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export train recording"))
    }.onFailure {
        Toast.makeText(context, "Could not export: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}
