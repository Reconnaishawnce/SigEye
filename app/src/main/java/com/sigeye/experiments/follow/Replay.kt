package com.sigeye.experiments.follow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.Journal
import com.sigeye.core.analysis.identity.Mark
import com.sigeye.core.analysis.identity.Moment
import com.sigeye.ui.Section

/**
 * Walking back through a follow.
 *
 * A follow is the one experiment here where you cannot watch the thing that matters: you
 * are looking at a person, or at a road, and the moment the count halved went past
 * unobserved. Being able to scrub back to it is how somebody learns whether to trust this,
 * and how they catch it being wrong - which is the more useful of the two.
 *
 * It is also where a mark pays off. A tap saying "they went behind a pillar" is a line on
 * this chart, and a dip in the count at the same instant is either that pillar or a
 * coincidence, and now you can see which.
 */
@Composable
fun Replay(journal: Journal, modifier: Modifier = Modifier) {
    val counts = remember(journal.size) { journal.counts() }
    val events = remember(journal.size) { journal.events() }
    if (counts.size < 2) {
        Text(
            "Nothing recorded yet. The chart fills in once a follow has been running for a " +
                "few seconds.",
            modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var position by remember(journal.size) { mutableStateOf(1f) }
    val from = counts.first().atMs
    val to = counts.last().atMs
    val atMs = from + ((to - from) * position).toLong()
    val here = journal.countAt(atMs)
    val near = journal.around(atMs)

    val line = MaterialTheme.colorScheme.primary
    val markColor = MaterialTheme.colorScheme.tertiary
    val eventColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursor = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val span = (to - from).coerceAtLeast(1L)
            val ceiling = counts.maxOf { it.pool }.coerceAtLeast(1)

            fun x(ms: Long) = size.width * ((ms - from).toFloat() / span)
            fun y(value: Int) = size.height * (1f - value.toFloat() / ceiling)

            val path = Path()
            counts.forEachIndexed { index, count ->
                val point = Offset(x(count.atMs), y(count.stillIn))
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, color = line, style = Stroke(width = 3f))

            // Marks are the operator's own eyes, so they are drawn differently from
            // everything the app worked out for itself.
            events.forEach { moment ->
                val at = x(moment.atMs)
                val colour = if (moment is Moment.Marked) markColor else eventColor
                drawLine(
                    color = colour.copy(alpha = if (moment is Moment.Marked) 0.9f else 0.35f),
                    start = Offset(at, 0f),
                    end = Offset(at, size.height),
                    strokeWidth = if (moment is Moment.Marked) 2.5f else 1.5f,
                )
            }

            drawLine(
                color = cursor,
                start = Offset(x(atMs), 0f),
                end = Offset(x(atMs), size.height),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }

        Slider(value = position, onValueChange = { position = it })

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                journal.clock(atMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            here?.let {
                Text(
                    "${it.stillIn} of ${it.pool} still with them",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (near.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing happened around here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        near.forEach { moment ->
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (moment is Moment.Marked) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(
                    "${journal.clock(moment.atMs)}  ${journal.describe(moment)}",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "The line is how many were still with you. Faint lines are things the app did; " +
                "the bold ones are what you marked with your own eyes, which is the only " +
                "ground truth on this chart.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Marking what you can see and the radio cannot.
 *
 * Tucked into a collapsed section on purpose. Most people will never press one of these,
 * and a row of buttons across the screen during a follow would be five things to not
 * understand at the moment there is least attention to spare. For the one person doing
 * forensics with it, being able to say "behind a pillar, now" and find the dip afterwards
 * is the difference between a chart and an experiment.
 */
@Composable
fun MarkRow(onMark: (Mark) -> Unit, marks: Int, modifier: Modifier = Modifier) {
    Section(
        title = "Mark what you see",
        summary = if (marks == 0) {
            "Optional. Ties what your eyes saw to what the radio heard."
        } else {
            "$marks marked so far."
        },
        modifier = modifier,
    ) {
        Text(
            "Tap one the moment it happens. It lands on the replay chart as a line, so a " +
                "dip in the count at the same instant is either that thing or a " +
                "coincidence - and you can see which afterwards rather than guessing now.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mark.entries.take(3).forEach { mark ->
                AssistChip(onClick = { onMark(mark) }, label = { Text(mark.label) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mark.entries.drop(3).forEach { mark ->
                AssistChip(onClick = { onMark(mark) }, label = { Text(mark.label) })
            }
        }
    }
}
