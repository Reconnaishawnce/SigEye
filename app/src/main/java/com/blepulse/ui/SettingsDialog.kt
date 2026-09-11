package com.blepulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blepulse.core.PulseConfig
import com.blepulse.core.SettingsStore
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    store: SettingsStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val initial = remember { store.load() }

    var binSeconds by remember { mutableFloatStateOf(initial.binSeconds.toFloat()) }
    var windowMinutes by remember { mutableFloatStateOf(initial.windowMinutes.toFloat()) }
    var rssiFloor by remember { mutableFloatStateOf(initial.rssiFloor.toFloat()) }
    var historyMinutes by remember { mutableFloatStateOf(initial.historyMinutes.toFloat()) }
    var spikeFactor by remember { mutableFloatStateOf(initial.spikeFactor.toFloat()) }
    var spikeMinCount by remember { mutableFloatStateOf(initial.spikeMinCount.toFloat()) }
    var cooldown by remember { mutableFloatStateOf(initial.alertCooldownSeconds.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SliderRow(
                    label = "Bin length",
                    value = binSeconds,
                    range = 1f..30f,
                    steps = 28,
                    display = binSeconds.roundToInt().toString() + " s",
                    hint = "How wide one bar of the chart is.",
                ) { binSeconds = it }

                SliderRow(
                    label = "New-device window",
                    value = windowMinutes,
                    range = 1f..60f,
                    steps = 58,
                    display = windowMinutes.roundToInt().toString() + " min",
                    hint = "An address counts as new again after this long unseen. " +
                        "Phones rotate their address roughly every 15 minutes.",
                ) { windowMinutes = it }

                SliderRow(
                    label = "Signal floor",
                    value = rssiFloor,
                    range = -100f..-40f,
                    steps = 59,
                    display = rssiFloor.roundToInt().toString() + " dBm",
                    hint = "Ignore anything weaker. Raise it toward -70 to hear only " +
                        "what is close to the window.",
                ) { rssiFloor = it }

                SliderRow(
                    label = "Chart history",
                    value = historyMinutes,
                    range = 5f..120f,
                    steps = 22,
                    display = historyMinutes.roundToInt().toString() + " min",
                    hint = "How far back the chart reaches.",
                ) { historyMinutes = it }

                SliderRow(
                    label = "Burst threshold",
                    value = spikeFactor,
                    range = 1.5f..8f,
                    steps = 12,
                    display = String.format(Locale.US, "%.1fx", spikeFactor) + " the usual rate",
                    hint = "Lower it if trains are missed, raise it if it cries wolf.",
                ) { spikeFactor = it }

                SliderRow(
                    label = "Minimum burst size",
                    value = spikeMinCount,
                    range = 2f..30f,
                    steps = 27,
                    display = spikeMinCount.roundToInt().toString() + " devices",
                    hint = "Never alert on fewer than this, however quiet the street is.",
                ) { spikeMinCount = it }

                SliderRow(
                    label = "Alert cooldown",
                    value = cooldown,
                    range = 30f..600f,
                    steps = 18,
                    display = cooldown.roundToInt().toString() + " s",
                    hint = "One train should be one buzz, not twelve.",
                ) { cooldown = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.save(
                    PulseConfig(
                        binSeconds = binSeconds.roundToInt(),
                        windowMinutes = windowMinutes.roundToInt(),
                        rssiFloor = rssiFloor.roundToInt(),
                        historyMinutes = historyMinutes.roundToInt(),
                        baselineBins = initial.baselineBins,
                        warmupBins = initial.warmupBins,
                        spikeFactor = spikeFactor.toDouble(),
                        spikeMinCount = spikeMinCount.roundToInt(),
                        alertCooldownSeconds = cooldown.roundToInt(),
                    ),
                )
                onSaved()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    hint: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}
