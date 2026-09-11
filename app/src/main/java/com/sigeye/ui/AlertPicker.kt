package com.sigeye.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.sigeye.core.AlertStyle
import com.sigeye.core.Feedback

/**
 * Choosing how an experiment should shout, with a button that makes it shout now.
 *
 * The Test button is the important part. An alert is by definition something you are not
 * watching for, so when it fails it fails invisibly - and it did, for several releases,
 * because the app was missing the VIBRATE permission and the exception that came back was
 * being swallowed. One tap now answers "is this going to work" without having to stage a
 * whole event and hope.
 */
@Composable
fun AlertPicker(
    style: AlertStyle,
    onStyle: (AlertStyle) -> Unit,
    feedback: Feedback,
    modifier: Modifier = Modifier,
    title: String = "Alert",
    note: String? = null,
) {
    // Bumped on every test so the warning below re-reads the live alarm volume - the user
    // may well have gone and turned it up because of what it said.
    var tested by remember { mutableStateOf(0) }
    val trouble = remember(style, tested) { feedback.trouble(style) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = {
                    feedback.alert(style, urgent = true)
                    tested++
                },
                enabled = style != AlertStyle.SILENT,
            ) { Text("Test", style = MaterialTheme.typography.labelSmall) }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AlertStyle.entries.forEach { entry ->
                FilterChip(
                    selected = style == entry,
                    onClick = { onStyle(entry) },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            style.hint + (note?.let { " $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trouble?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
