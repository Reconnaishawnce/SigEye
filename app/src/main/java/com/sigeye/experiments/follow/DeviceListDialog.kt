package com.sigeye.experiments.follow

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook

/**
 * Name a device and put it on lists, from wherever you found it.
 *
 * The thing a follow produces is a device you now care about, and caring about a device in
 * this app means two things: giving it a name, and putting it on a list. Lists are what
 * every other experiment reads - Signal Watch alerts on them, Persistent Tracking follows
 * them across address changes, the pickers sort by them - so a list is the only way a
 * finding here travels anywhere else.
 *
 * Both in one dialog because they are one decision. Somebody who has just picked a phone
 * out of a street wants to call it something and keep hold of it, and making that two
 * separate trips through the app is how a finding gets lost.
 */
@Composable
fun DeviceListDialog(
    address: String,
    suggestedName: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val lists by book.lists.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()

    val note = notes[address.uppercase()]
    var nickname by remember { mutableStateOf(note?.nickname ?: suggestedName.orEmpty()) }
    var newList by remember { mutableStateOf("") }
    val on = note?.lists.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep this one") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = {
                        nickname = it
                        book.setNickname(address, it.takeIf { name -> name.isNotBlank() })
                    },
                    label = { Text("Call it something") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The name sticks to the address, so it survives until the device " +
                        "changes one - and Persistent Tracking is the experiment that " +
                        "carries it across when it does.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "On these lists",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "A list is how this reaches the rest of the app. Signal Watch will tell " +
                        "you when anything on one comes back into range.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                if (lists.isEmpty()) {
                    Text(
                        "No lists yet. Make one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                lists.forEach { list ->
                    val member = on.contains(list)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = if (member) {
                                    "Take it off $list"
                                } else {
                                    "Put it on $list"
                                },
                            ) { book.toggleList(address, list) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(list, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (member) "on" else "off",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (member) FontWeight.Bold else FontWeight.Normal,
                            color = if (member) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newList,
                        onValueChange = { newList = it },
                        label = { Text("New list") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val name = newList.trim()
                            if (name.isNotEmpty()) {
                                book.createList(name)
                                if (!book.listsOf(address).contains(name)) {
                                    book.toggleList(address, name)
                                }
                                newList = ""
                            }
                        },
                        enabled = newList.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
