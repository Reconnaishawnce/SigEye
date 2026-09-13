package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.IgnoreList
import com.sigeye.experiments.watchlist.MatchKind
import com.sigeye.experiments.watchlist.WatchRule
import com.sigeye.experiments.watchlist.WatchStore
import java.util.Locale

/**
 * Naming, grouping, watching and muting one device.
 *
 * Extracted because every screen that shows a device wants the same four actions, and a
 * mute applied in one place has to mean the same thing everywhere.
 */
@Composable
fun DeviceActions(
    address: String,
    displayName: String,
    isRandomAddress: Boolean,
    onRequestNewList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val ignoreList = remember { IgnoreList.get(context) }
    val watchStore = remember { WatchStore.get(context) }

    val rules by watchStore.rules.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()
    val lists by book.lists.collectAsStateWithLifecycle()
    val ignored by ignoreList.addresses.collectAsStateWithLifecycle()

    val key = address.uppercase()
    val memberOf = notes[key]?.lists.orEmpty()
    val muted = ignored.contains(key)
    var draft by remember(address) { mutableStateOf(notes[key]?.nickname.orEmpty()) }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                book.setNickname(address, it)
            },
            label = { Text("Nickname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isRandomAddress) {
            Text(
                "This address is randomized and will change within about fifteen minutes. " +
                    "A nickname will not follow it, and neither will a watch rule based " +
                    "on the address.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lists", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRequestNewList) {
                Text("New", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (lists.isEmpty()) {
            Text(
                "No lists yet. Create one to group devices, then a single watch rule can " +
                    "alert on the whole group.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            lists.forEach { list ->
                Row(
                    Modifier.fillMaxWidth().clickable { book.toggleList(address, list) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = memberOf.contains(list),
                        onCheckedChange = { book.toggleList(address, list) },
                    )
                    Text(list, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // The rule this device would have added, if it is there. Matching on value rather
        // than on a remembered id so a rule added from any other screen is recognized too.
        val existingRule = rules.firstOrNull {
            it.kind == MatchKind.ADDRESS && it.value.equals(address, ignoreCase = true)
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = {
                    if (existingRule != null) {
                        watchStore.delete(existingRule.id)
                    } else {
                        watchStore.upsert(
                            WatchRule(
                                id = watchStore.newId(),
                                label = draft.ifBlank { displayName },
                                kind = MatchKind.ADDRESS,
                                value = address,
                            ),
                        )
                    }
                },
            ) {
                Text(
                    if (existingRule != null) "Remove from watchlist" else "Add to watchlist",
                )
            }
            TextButton(onClick = { ignoreList.toggle(address) }) {
                Text(if (muted) "Unmute" else "Mute")
            }
        }
        if (existingRule != null) {
            Text(
                "On the watchlist as \"" + existingRule.label + "\". Alerts need Signal " +
                    "Watch switched on.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (muted) {
            Text(
                "Muted. Dropped before counting, so it affects no experiment.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** A labeled read-only field, monospaced so hex and addresses line up. */
@Composable
fun DetailField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp)) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun NewListDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Group devices you care about - Vehicles, Neighbors, Mine. A watch " +
                        "rule can then alert on the whole list at once.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(name)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
