package com.sigeye.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Four letters instead of sixty rows of scrolling.
 *
 * Every list in this app fills up in a busy room, and the thing you are looking for is
 * usually something you can name: a JBL speaker, a Bose headset, an address you half
 * remember. Typing is faster than reading, so this sits above the list and above the radar
 * and narrows both.
 *
 * Kept deliberately plain. It is a box you type in, and the hint says what it will match,
 * because a filter that silently ignores what somebody typed is worse than no filter.
 */
@Composable
fun QuickFilter(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "JBL, Bose, AA:BB, kitchen...",
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Filter") },
        placeholder = { Text(placeholder) },
        supportingText = {
            Text(
                "Matches the name, the vendor, a nickname you gave it, or the start of " +
                    "the address.",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQuery("") }) { Text("Clear") }
            }
        },
    )
}
