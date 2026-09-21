package com.sigeye.experiments.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.RunStore
import com.sigeye.core.SavedRun
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything measured on this bench, in one place.
 *
 * The seven measurements each saved their runs and each kept them to themselves, which is
 * the right storage and the wrong shape for the question people actually have. Nobody
 * walks into a building wondering what its multipath fading was last March. They wonder
 * what they already know about the place, and that answer was spread across seven screens
 * with no way to see it at once.
 *
 * Read-only on purpose. Deleting a run belongs next to the measurement that produced it,
 * where the context for deciding it was a bad run still exists, and a list that can quietly
 * destroy things from six experiments at once is a list nobody should be scrolling idly.
 */
@Composable
fun BenchNotebook(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    modes: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { RunStore.get(context) }
    val version by store.version.collectAsStateWithLifecycle()

    val ids = remember { Bench.MEASUREMENTS.mapNotNull { it.experimentId } }
    val runs = remember(version, ids) { store.runs(ids) }
    val byExperiment = remember(runs) { runs.groupBy { it.experiment } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("← All experiments") }
        Text(
            "Notebook",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Every run saved on this bench. What a measurement says on its own is worth " +
                "less than whether it has changed since the last time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        modes()
        Spacer(Modifier.height(16.dp))

        if (runs.isEmpty()) {
            Text(
                "Nothing saved yet. Each measurement has a Saved runs panel at the bottom " +
                    "of it; naming a run costs a tap and is the difference between a number " +
                    "you looked at once and a number you can argue with later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            return@Column
        }

        Text(
            "${runs.size} run${if (runs.size == 1) "" else "s"} across " +
                "${byExperiment.size} measurement${if (byExperiment.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))

        // In bench order rather than by how recently each was used, so the notebook reads
        // the same way as the switcher above it.
        Bench.MEASUREMENTS.forEach { measurement ->
            val id = measurement.experimentId ?: return@forEach
            val theirs = byExperiment[id].orEmpty()
            if (theirs.isEmpty()) return@forEach

            Text(
                Experiments.byId(id)?.title ?: measurement.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            theirs.forEach { run ->
                RunRow(run)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RunRow(run: SavedRun) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    run.name.ifBlank { "unnamed" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    WHEN.format(Date(run.takenAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                run.figures.joinToString("  ·  ") { "${it.label} ${it.pretty()}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val WHEN = SimpleDateFormat("d MMM HH:mm", Locale.US)
