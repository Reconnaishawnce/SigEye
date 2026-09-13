package com.sigeye.experiments.trainspotter

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.ui.Field
import com.sigeye.ui.Section
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the trains you marked say the threshold should be.
 *
 * The ground-truth button has been here since this experiment was written, and until now
 * the labels went into the CSV and nowhere else - days of somebody standing at a window
 * doing the one thing a machine cannot, while the threshold stayed a number I picked out of
 * the air.
 *
 * Collapsed, because most of the time there is nothing to say. It opens itself out with a
 * suggestion when there is.
 */
@Composable
fun ThresholdCard(config: PulseConfig, onApply: (Double) -> Unit) {
    val context = LocalContext.current
    var advice by remember { mutableStateOf<ThresholdAdvice?>(null) }
    var applied by remember { mutableStateOf<Double?>(null) }

    // Off the main thread: this reads every CSV on the phone, which after a fortnight of
    // unattended running is a few megabytes.
    LaunchedEffect(config.spikeFactor, config.spikeMinCount) {
        advice = withContext(Dispatchers.IO) { fit(context, config) }
    }

    val result = advice ?: return
    val suggested = result.suggested

    Section(
        title = "What your own trains say",
        summary = when {
            !result.enough -> "${result.marked} marked. Press Train now when one goes past."
            suggested == null -> "${result.marked} marked, nothing to suggest yet."
            suggested.factor == config.spikeFactor -> "Your ${result.marked} trains agree " +
                "with the threshold you are on."
            else -> "Try ${format(suggested.factor)}x - it catches " +
                "${suggested.caught} of your ${result.marked}."
        },
        initiallyExpanded = suggested != null && suggested.factor != config.spikeFactor,
    ) {
        if (!result.enough) {
            Text(
                "Every time a train actually goes past, press Train now. After about " +
                    "${ThresholdFit.MIN_MARKS} of them this replays every bin it has " +
                    "recorded against every candidate threshold and says which one would " +
                    "have caught them. The count and the baseline in each row are what the " +
                    "app really had at that moment, so it is a replay rather than a " +
                    "simulation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Field("Marked so far", "${result.marked}")
            Field("Bins recorded", "${result.bins} across ${result.days} days")
            return@Section
        }

        Text(
            "${result.marked} trains marked across ${result.days} days, replayed against " +
                "${result.bins} recorded bins.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "How each threshold would have done",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("threshold", style = MaterialTheme.typography.labelSmall)
            Text("caught", style = MaterialTheme.typography.labelSmall)
            Text("missed", style = MaterialTheme.typography.labelSmall)
            Text("other alerts", style = MaterialTheme.typography.labelSmall)
        }

        // Every quarter step would be thirty rows of almost the same thing. Whole and half
        // steps are what somebody would actually choose between.
        result.scores
            .filter { (it.factor * 2) % 1.0 == 0.0 }
            .forEach { score ->
                val here = score.factor == config.spikeFactor
                val pick = score.factor == suggested?.factor
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        format(score.factor) + "x" + when {
                            here && pick -> " (now, suggested)"
                            here -> " (now)"
                            pick -> " (suggested)"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (pick) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text("${score.caught}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${score.missed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (score.missed > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text("${score.unmarked}", style = MaterialTheme.typography.bodySmall)
                }
            }

        Spacer(Modifier.height(10.dp))
        Text(
            "A missed train is unambiguously wrong: one went past and the app said nothing. " +
                "An alert away from a mark is not - nobody stands at a window for days with " +
                "a finger on the button, so plenty of real trains go unmarked. That is why " +
                "the two are separate columns rather than one accuracy figure, and why the " +
                "suggestion is the quietest threshold that still misses none of yours " +
                "rather than the one with the best-looking score.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        suggested?.takeIf { it.factor != config.spikeFactor }?.let { pick ->
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (pick.missed == 0) {
                            "Try ${format(pick.factor)}x"
                        } else {
                            "Best available is ${format(pick.factor)}x"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append("It would have caught ${pick.caught} of your ")
                            append("${result.marked}")
                            if (pick.missed > 0) {
                                append(", which is the most any of them manage - the ones " +
                                    "it misses were too quiet for the minimum burst size")
                            }
                            append(", with ${pick.unmarked} other alerts. ")
                            result.current?.let { now ->
                                append("What you are on catches ${now.caught} with ")
                                append("${now.unmarked} others.")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onApply(pick.factor)
                            applied = pick.factor
                        },
                        enabled = applied != pick.factor,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (applied == pick.factor) {
                                "Set to ${format(pick.factor)}x"
                            } else {
                                "Use ${format(pick.factor)}x"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Reads every recorded day back and scores the candidates against it. */
private fun fit(context: Context, config: PulseConfig): ThresholdAdvice? {
    val dir: File = context.getExternalFilesDir(null) ?: return null
    val files = dir.listFiles { f -> f.name.startsWith("sigeye-") && f.name.endsWith(".csv") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) return null

    val bins = files.flatMap { file ->
        runCatching { file.readLines().mapNotNull(ThresholdFit::parse) }.getOrDefault(emptyList())
    }
    if (bins.isEmpty()) return null

    return ThresholdFit.advise(
        bins = bins,
        binMillis = config.binMillis,
        currentFactor = config.spikeFactor,
        minCount = config.spikeMinCount,
    )
}

private fun format(factor: Double): String = String.format(Locale.US, "%.2f", factor)
    .trimEnd('0')
    .trimEnd('.')
