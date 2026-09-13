package com.sigeye.experiments.follow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.Handoff
import com.sigeye.core.analysis.identity.Handoffs
import com.sigeye.core.analysis.identity.LinkConfidence
import com.sigeye.core.analysis.identity.Successor
import java.util.Locale

/**
 * The moment a follow is won or lost, put to the person who can actually see the answer.
 *
 * A device has gone quiet in a way that looks like a rotation, and more than one address
 * appeared at about the right instant. The app will not pick between them: in a food court
 * every iPhone looks alike to a fingerprint, and a wrong pick produces a confident trail
 * leading to a stranger, which is worse than losing the target because it looks like
 * success.
 *
 * So it shows its working instead of its conclusion. Every number here is one somebody
 * standing on a street corner could use: how long after the old one stopped, how far the
 * level moved, whether the advertising rhythm matches. Nobody has to trust a verdict.
 */
@Composable
fun RotationDialog(
    ask: Handoff.Ask,
    label: String,
    onPick: (String) -> Unit,
    onNone: () -> Unit,
    onLater: () -> Unit,
) {
    var chosen by remember(ask.departure.address) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("$label changed address") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    ask.departure.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    Handoffs.whyAsking(ask),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))

                ask.options.forEachIndexed { index, option ->
                    OptionCard(
                        option = option,
                        rank = index,
                        selected = chosen == option.address,
                        onSelect = { chosen = option.address },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    "The share is how much of the evidence each one holds against the " +
                        "others here, not a probability that it is the right device. One " +
                        "option holding all of it because nothing else turned up is a very " +
                        "different situation from one holding all of it against two rivals, " +
                        "which is why the confidence is shown beside it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { chosen?.let(onPick) },
                enabled = chosen != null,
            ) { Text("That one") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLater) { Text("Later") }
                OutlinedButton(onClick = onNone) { Text("None of these") }
            }
        },
    )
}

@Composable
private fun OptionCard(
    option: Successor,
    rank: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (rank == 0) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    String.format(Locale.US, "%.0f%%", option.share * 100),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = shareColor(option),
                )
            }
            Text(
                option.confidence.label,
                style = MaterialTheme.typography.labelSmall,
                color = shareColor(option),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Handoffs.describe(option),
                style = MaterialTheme.typography.bodySmall,
            )

            val against = option.score.against
            if (against.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // What argues against it, in the same place and the same size as what
                // argues for it. A dialog that only showed supporting evidence would be
                // asking somebody to rubber-stamp a guess.
                against.forEach {
                    Text(
                        "· ${it.text}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun shareColor(option: Successor) = when {
    option.confidence == LinkConfidence.STRONG && option.share >= Handoffs.AUTO_SHARE ->
        MaterialTheme.colorScheme.primary

    option.confidence.ordinal >= LinkConfidence.LIKELY.ordinal ->
        MaterialTheme.colorScheme.onSurface

    else -> MaterialTheme.colorScheme.error
}
