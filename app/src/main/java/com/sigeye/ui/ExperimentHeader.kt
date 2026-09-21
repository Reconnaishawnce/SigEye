package com.sigeye.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.Experiment
import com.sigeye.core.Experiments
import java.util.Locale

/**
 * The header every experiment screen shares: back, title, one-line blurb, and a "how this
 * works" button.
 *
 * The explainer is read from the registry rather than written per screen, so the
 * description on the home card and the instructions inside the experiment cannot drift
 * apart - and a new experiment gets its walkthrough by filling in a data class.
 */
@Composable
fun ExperimentHeader(
    experimentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "← All experiments",
) {
    val experiment = Experiments.byId(experimentId)
    var showInfo by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text(backLabel)
            }
            if (experiment != null) {
                TextButton(onClick = { showInfo = true }) { Text("How this works") }
            }
        }
        Text(
            experiment?.title ?: experimentId,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        experiment?.blurb?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showInfo && experiment != null) {
        ExperimentInfoDialog(experiment) { showInfo = false }
    }
}

@Composable
private fun ExperimentInfoDialog(experiment: Experiment, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(experiment.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("What it measures", experiment.teaches)

                if (experiment.howTo.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "HOW TO RUN IT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    experiment.howTo.forEachIndexed { index, step ->
                        Row(Modifier.padding(bottom = 6.dp)) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(step, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                experiment.reading?.let {
                    Spacer(Modifier.height(8.dp))
                    Section("Reading the result", it)
                }

                experiment.limits?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LIMITATIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                experiment.needs?.let {
                    Spacer(Modifier.height(8.dp))
                    Section("Needs", it)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun Section(label: String, body: String) {
    Text(
        label.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodySmall)
}

/**
 * Count plus a pause control, for any screen with a list that updates itself.
 *
 * Pausing stops the redraw, never the recording - so nothing is missed while you read a
 * row or reach for one. Every live list needs this: a list that reorders itself twice a
 * second cannot be tapped.
 */
@Composable
fun PauseBar(
    paused: Boolean,
    onToggle: () -> Unit,
    summary: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                TextButton(onClick = onToggle) {
                    Text(
                        if (paused) "Resume" else "Pause",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (paused) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        if (paused) {
            Text(
                "Frozen so you can pick something. Still listening underneath.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
