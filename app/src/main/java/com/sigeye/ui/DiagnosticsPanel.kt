package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** One counter, with an optional line saying what it means. */
data class Diagnostic(val label: String, val value: String, val hint: String? = null)

/**
 * What an experiment is actually seeing, rather than what it concluded.
 *
 * Collapsed by default so it does not compete with the measurement, and expandable for
 * anyone who wants to know why a reading looks the way it does. A verdict - the one-line
 * "here is what is wrong" - stays visible either way, because that is the part you need
 * when the experiment appears to be doing nothing.
 *
 * This started as a panel bolted onto Body Absorption to diagnose a capture bug that had
 * twice been fixed wrongly from first principles. It turned out to be the most useful
 * thing on the screen, so it is shared: every experiment that discards, filters or waits
 * for something should be able to say so out loud.
 */
@Composable
fun DiagnosticsPanel(
    diagnostics: List<Diagnostic>,
    modifier: Modifier = Modifier,
    title: String = "What this is seeing",
    verdict: String? = null,
    footnote: String? = null,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val troubled = verdict != null

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (troubled) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (expanded) "Hide  ▴" else "Show  ▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The verdict is the point of the panel, so it is not hidden behind the fold.
            verdict?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                diagnostics.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        row.forEach { Cell(it, Modifier.weight(1f)) }
                        // Keep a short final row aligned with the ones above it.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                footnote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(diagnostic: Diagnostic, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            diagnostic.label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            diagnostic.value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        diagnostic.hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
