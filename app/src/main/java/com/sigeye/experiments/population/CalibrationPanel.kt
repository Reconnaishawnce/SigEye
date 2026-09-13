package com.sigeye.experiments.population

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CalibrationStore
import com.sigeye.core.analysis.presence.Agreement
import com.sigeye.core.analysis.presence.Calibration
import com.sigeye.core.analysis.presence.CrowdAdvice
import com.sigeye.core.analysis.presence.CrowdCalibration
import com.sigeye.core.analysis.presence.CrowdFit
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.Section
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Long enough that the present count stops being about which second you pressed the button. */
private const val CALIBRATE_SECONDS = 30

/**
 * Measuring the devices-per-person factor against a headcount you actually took.
 *
 * The factor was the weakest number in the app, and the way to improve it was a slider and
 * the instruction to adjust until the estimate looked right - which is the answer being
 * typed in rather than measured. This does the Doppler Walk trick on people: supply the
 * truth you already know, let the radio supply what it heard, and solve for the constant
 * between them.
 */
@Composable
fun CalibrationSection(
    store: CalibrationStore,
    rssiFloor: Int,
    presenceSeconds: Int,
    factorInUse: Double,
    presentDevices: Int,
    onApply: (Double) -> Unit,
) {
    val saved by store.calibrations.collectAsStateWithLifecycle()
    var place by remember { mutableStateOf(store.places().firstOrNull() ?: "Here") }
    var counting by remember { mutableStateOf(false) }

    val advice = remember(saved, place, rssiFloor, presenceSeconds) {
        CrowdCalibration.adviseFor(saved, place, rssiFloor, presenceSeconds)
    }
    val fit = advice.suggested

    Section(
        title = "What your own headcounts say",
        summary = when {
            fit == null -> "No headcounts yet. The factor is still a guess."
            !advice.fromHere -> "${format(fit.factor)} per person, borrowed from elsewhere."
            near(fit.factor, factorInUse) ->
                "Your ${fit.calibrations} headcounts here agree with what you are using."
            else -> "$place says ${format(fit.factor)} per person, not ${format(factorInUse)}."
        },
        initiallyExpanded = fit != null && !near(fit.factor, factorInUse),
    ) {
        Text(
            "Count the people around you and tell the app the number. It averages what the " +
                "radio hears over $CALIBRATE_SECONDS seconds and divides. That is a measured " +
                "factor rather than a chosen one, and it quietly absorbs whatever the " +
                "presence window and randomization are doing to the count, because they are " +
                "doing it during the headcount too.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Text("Where you are", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = place,
            onValueChange = { place = it.take(40) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val others = store.places().filterNot { it.equals(place, ignoreCase = true) }.take(4)
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { name ->
                    AssistChip(onClick = { place = name }, label = { Text(name) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { counting = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Take a headcount here")
        }

        if (fit != null) FitCard(advice, fit, factorInUse, onApply)

        if (advice.wrongFloor > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${advice.wrongFloor} other " +
                    (if (advice.wrongFloor == 1) "headcount is" else "headcounts are") +
                    " on file but not in use: they were taken at a different floor, which " +
                    "means they were counting a different size of room. A factor does not " +
                    "carry across that.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val mine = saved.filter { it.place.equals(place, ignoreCase = true) }
        if (mine.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Headcounts in $place", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            mine.take(8).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${row.headcount} people, " +
                            String.format(Locale.US, "%.1f", row.devices) +
                            " devices at ${row.rssiFloor} dBm",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            format(row.ratio) + "x",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { store.remove(row.takenAtMs) }) { Text("Forget") }
                    }
                }
            }
        }
    }

    if (counting) {
        CalibrationDialog(
            place = place,
            rssiFloor = rssiFloor,
            presenceSeconds = presenceSeconds,
            presentDevices = presentDevices,
            onSave = { store.add(it) },
            onDismiss = { counting = false },
        )
    }
}

@Composable
private fun FitCard(
    advice: CrowdAdvice,
    fit: CrowdFit,
    factorInUse: Double,
    onApply: (Double) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (fit.agreement) {
                Agreement.TIGHT -> MaterialTheme.colorScheme.primaryContainer
                Agreement.LOOSE -> MaterialTheme.colorScheme.surfaceVariant
                Agreement.SCATTERED -> MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${format(fit.factor)} devices per person",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("From ${fit.calibrations} ")
                    append(if (fit.calibrations == 1) "headcount" else "headcounts")
                    append(" covering ${fit.people} people")
                    if (!advice.fromHere) {
                        append(
                            ", none of them in ${advice.place} - this is what everywhere " +
                                "else says, so treat it as a starting point",
                        )
                    }
                    append(". ")
                    when {
                        fit.calibrations < 2 -> append(
                            "One headcount is a measurement of one moment. Take another " +
                                "somewhere busier and somewhere emptier before leaning on it.",
                        )

                        fit.agreement == Agreement.TIGHT -> append(
                            "They land between ${format(fit.lowRatio)} and " +
                                "${format(fit.highRatio)}, close enough that this is a " +
                                "measured number rather than an average of guesses.",
                        )

                        fit.agreement == Agreement.LOOSE -> append(
                            "They range from ${format(fit.lowRatio)} to " +
                                "${format(fit.highRatio)}. Usable, with that much slack " +
                                "around any estimate it produces.",
                        )

                        else -> append(
                            "They range from ${format(fit.lowRatio)} to " +
                                "${format(fit.highRatio)}, too far apart to average honestly. " +
                                "Something other than headcount is driving the device count " +
                                "here - passing traffic, or fixed kit that belongs to nobody " +
                                "in the room.",
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!near(fit.factor, factorInUse)) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onApply(fit.factor) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use ${format(fit.factor)}") }
            }
        }
    }
}

/**
 * The headcount itself: a number you know, and half a minute of holding still.
 *
 * The window matters. The present count moves every second as packets arrive and lapse, and
 * whichever second a button happened to be pressed in has no claim to being the right one.
 */
@Composable
private fun CalibrationDialog(
    place: String,
    rssiFloor: Int,
    presenceSeconds: Int,
    presentDevices: Int,
    onSave: (Calibration) -> Unit,
    onDismiss: () -> Unit,
) {
    var heads by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf<Calibration?>(null) }
    val samples = remember { mutableStateListOf<Int>() }

    // The live count arrives through recomposition, so the sampling loop has to read the
    // latest one rather than the one it closed over when it started.
    val live by rememberUpdatedState(presentDevices)
    val count = heads.toIntOrNull() ?: 0

    KeepScreenOn(running)

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        samples.clear()
        seconds = 0
        while (seconds < CALIBRATE_SECONDS) {
            delay(1_000)
            seconds++
            samples.add(live)
        }
        done = Calibration(
            place = place.ifBlank { "Here" },
            headcount = count,
            devices = CrowdCalibration.meanDevices(samples),
            samples = samples.size,
            seconds = CALIBRATE_SECONDS,
            rssiFloor = rssiFloor,
            presenceSeconds = presenceSeconds,
            takenAtMs = System.currentTimeMillis(),
        )
        running = false
    }

    val finished = done
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                when {
                    finished != null -> "Counted"
                    running -> "Hold still"
                    else -> "Headcount in $place"
                },
            )
        },
        text = {
            Column {
                when {
                    finished != null -> {
                        Text(
                            "${finished.headcount} people and " +
                                String.format(Locale.US, "%.1f", finished.devices) +
                                " devices on average, which is ${format(finished.ratio)} " +
                                "per person here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Field("Floor", "$rssiFloor dBm")
                        Field("Present window", "$presenceSeconds s")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Saved against those two. A headcount taken at a different floor " +
                                "is counting a different room, so this one will not be " +
                                "pooled with it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    running -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CountdownRing(
                            elapsedMs = seconds * 1_000L,
                            totalMs = CALIBRATE_SECONDS * 1_000L,
                            label = "counting",
                            caption = "$presentDevices devices now",
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Stay where you are and keep the group where it is. Anyone who " +
                                "wanders off mid-count takes their devices with them, and " +
                                "the factor comes out low.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    else -> {
                        Text(
                            "How many people are within range right now? Count everyone, " +
                                "including yourself.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = heads,
                            onValueChange = { heads = it.filter(Char::isDigit).take(4) },
                            singleLine = true,
                            label = { Text("People") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Right now the radio hears $presentDevices devices above " +
                                "$rssiFloor dBm. Count only the people inside that same " +
                                "reach - if the floor is set wide enough to hear the street, " +
                                "the people on the street count too, or the factor comes out " +
                                "high.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                finished != null -> TextButton(
                    onClick = {
                        onSave(finished)
                        onDismiss()
                    },
                ) { Text("Save it") }

                running -> TextButton(onClick = {}, enabled = false) { Text("Counting") }

                else -> TextButton(
                    onClick = { running = true },
                    enabled = count > 0,
                ) { Text("Start") }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { if (running) running = false else onDismiss() },
            ) { Text(if (running) "Stop" else "Cancel") }
        },
    )
}

private fun near(a: Double, b: Double): Boolean = abs(a - b) < 0.05

private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
    .trimEnd('0')
    .trimEnd('.')
