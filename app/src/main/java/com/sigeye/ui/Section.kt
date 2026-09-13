package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * A labeled block that shows its conclusion and hides its working.
 *
 * The screens in this app accumulated a great deal of genuinely useful detail and then
 * showed all of it at once, which is the same as showing none of it - by the fourth
 * heading nobody is reading. A section carries the one line that answers the question in
 * the header, and the numbers behind that line only when asked for.
 *
 * The summary is not optional by accident. A collapsed section with nothing but a title is
 * a thing to tap hopefully; one that says "Resolvable private, rotates every 15 min" has
 * already done its job and the tap is for people who want the workings.
 */
@Composable
fun Section(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    emphasis: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
                    color = if (emphasis) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** A label and a value on one line, monospaced so columns of them line up. */
@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
