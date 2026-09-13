package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.RunFigure
import com.sigeye.core.RunStore
import com.sigeye.core.SavedRun
import com.sigeye.core.compareRuns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save this run, and put it next to an earlier one.
 *
 * Nearly every experiment here is a live readout that forgets everything the moment you
 * leave the screen. That is the right behaviour for pointing a phone at something, and the
 * wrong one for the question people actually ask, which is whether this is worse than it
 * was last week. Saving a run costs a tap and a name.
 *
 * Collapsed by default and never in the way. Nobody standing in a corridor taking a
 * measurement wants a history panel above the number they came for, so this belongs at the
 * bottom of a screen and stays shut until it is wanted.
 *
 * Comparison is two taps rather than a mode: pick a run, pick another, and the table
 * appears. Picking a third replaces the older of the two, which is what people do anyway
 * when they mean "now compare it to this one".
 */
@Composable
fun RunHistory(
    experiment: String,
    /** The run on screen right now. Empty means there is nothing worth saving yet. */
    figures: List<RunFigure>,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val context = LocalContext.current
    val store = remember { RunStore.get(context) }
    val version by store.version.collectAsStateWithLifecycle()
    val runs = remember(version, experiment) { store.runs(experiment) }

    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<Long>()) }

    val summary = when {
        runs.isEmpty() && figures.isEmpty() -> "Nothing saved yet"
        runs.isEmpty() -> "Save this one to compare it later"
        else -> "${runs.size} saved" + if (figures.isEmpty()) "" else ", and this one to add"
    }

    Section("Saved runs", summary, modifier) {
        if (figures.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name this run") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        store.save(
                            SavedRun(
                                experiment = experiment,
                                name = name.ifBlank { defaultName() },
                                takenAtMs = System.currentTimeMillis(),
                                figures = figures,
                                note = note,
                            ),
                        )
                        name = ""
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (runs.isEmpty()) {
            Text(
                "A saved run keeps the numbers, not the raw packets. Take one here, take " +
                    "another somewhere else, and the two sit side by side.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }

        runs.forEach { run ->
            val selected = picked.contains(run.takenAtMs)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        picked = when {
                            selected -> picked - run.takenAtMs
                            picked.size < 2 -> picked + run.takenAtMs
                            else -> listOf(picked.last(), run.takenAtMs)
                        }
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        run.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        stamp(run.takenAtMs) + " · " + run.figures.size + " figures" +
                            (run.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (selected) "comparing" else "compare",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        val pair = picked.mapNotNull { at -> runs.firstOrNull { it.takenAtMs == at } }
        if (pair.size == 2) {
            val ordered = pair.sortedBy { it.takenAtMs }
            Spacer(Modifier.height(12.dp))
            Comparison(ordered[0], ordered[1])
        } else if (picked.size == 1) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a second run to compare against.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { store.clear(experiment); picked = emptyList() }) {
            Text("Forget every saved run")
        }
    }
}

/**
 * The two runs, line by line.
 *
 * The change column is the point of the whole panel, so it is the only thing coloured, and
 * only when the experiment said which direction is the good one. A figure that barely moved
 * is left plain rather than dressed up as an improvement.
 */
@Composable
private fun Comparison(before: SavedRun, after: SavedRun) {
    val deltas = compareRuns(before, after)

    Text(
        "${before.name} → ${after.name}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))

    deltas.forEach { delta ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                delta.label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                delta.before?.pretty() ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(84.dp),
            )
            Text(
                delta.after?.pretty() ?: "—",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(84.dp),
            )
            Text(
                if (delta.steady) "steady" else delta.prettyChange(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (delta.better) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(76.dp),
            )
        }
    }
}

private fun stamp(atMs: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(atMs))

private fun defaultName(): String =
    SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())
