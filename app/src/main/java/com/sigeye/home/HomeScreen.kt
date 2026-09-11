package com.sigeye.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiment
import com.sigeye.core.Experiments
import com.sigeye.core.ScanService
import com.sigeye.core.ble.BleScanHub
import java.util.Locale

@Composable
fun HomeScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "SigEye",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Experiments for the radio signals around you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Experiments.readyCount} ready · ${Experiments.all.size} planned in total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        RadioStatus()
        Spacer(Modifier.height(14.dp))

        Experiments.byCategory().forEach { (category, experiments) ->
            Text(
                text = category.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = category.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            experiments.forEach { experiment ->
                ExperimentCard(
                    experiment = experiment,
                    onClick = {
                        if (experiment.status == Experiment.Status.READY) onOpen(experiment.id)
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        Text(
            text = "Everything runs on this phone. No accounts, no network, no analytics.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Whether the radio is actually on, and whether it will stay on after you leave.
 *
 * Worth its own place on the home screen: scanning is the one thing this app does that
 * costs battery, and until now there was no way to tell from the outside whether anything
 * was running. Two separate facts, deliberately kept apart - an experiment holds the radio
 * only while its screen is open, whereas a background mode survives leaving the app and
 * keeps a notification in the status bar.
 */
@Composable
private fun RadioStatus() {
    val health by BleScanHub.health.collectAsStateWithLifecycle()
    val activeModes by ScanService.activeModes.collectAsStateWithLifecycle()

    val background = activeModes.isNotEmpty()
    val scanning = health.scanning

    val container = when {
        background -> MaterialTheme.colorScheme.errorContainer
        scanning -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        background -> MaterialTheme.colorScheme.onErrorContainer
        scanning -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(10.dp)) {
                drawCircle(color = if (scanning || background) dotOn else dotOff)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        background -> "Recording in the background"
                        scanning -> "Radio on"
                        else -> "Idle"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                Text(
                    when {
                        background -> activeModes.joinToString(", ") { modeLabel(it) } +
                            " will keep scanning after you leave the app, and will keep " +
                            "using battery, until you stop it from its own screen."
                        scanning -> String.format(
                            Locale.US,
                            "%.0f packets a second. Stops when you leave the experiment.",
                            health.advertsPerSecond,
                        )
                        else -> "Nothing is scanning. Open an experiment to start."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
            }
        }
    }
}

private fun modeLabel(mode: ScanService.Mode): String = when (mode) {
    ScanService.Mode.TRAIN_SPOTTER -> "Train Spotter"
    ScanService.Mode.WATCHLIST -> "Signal Watch"
}

private val dotOn = Color(0xFF35C759)
private val dotOff = Color(0xFF8A8A8E)

@Composable
private fun ExperimentCard(experiment: Experiment, onClick: () -> Unit) {
    val ready = experiment.status == Experiment.Status.READY
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ready, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = experiment.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = if (ready) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!ready) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "soon",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = experiment.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Planned entries get the "why" too - the roadmap should be readable, not a
            // row of teasers.
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = experiment.teaches,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                experiment.needs?.let { needs ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Needs: $needs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
