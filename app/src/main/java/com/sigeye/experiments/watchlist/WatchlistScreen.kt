package com.sigeye.experiments.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.ScanService
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WatchlistScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.WATCHLIST, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To read Bluetooth advertisements. SigEye never connects to anything.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it. Your location is never " +
                        "read or stored.",
                ),
                PermissionReason(
                    "Notifications",
                    "To tell you when a watched device turns up.",
                ),
            ),
            footnote = "Watching runs in the background on the same scan as any other " +
                "running experiment, so leaving both on costs one scan, not two.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val store = remember { WatchStore.get(context) }
    val book = remember { DeviceBook.get(context) }

    val rules by store.rules.collectAsStateWithLifecycle()
    val hits by store.hits.collectAsStateWithLifecycle()
    val lists by book.lists.collectAsStateWithLifecycle()
    val activeModes by ScanService.activeModes.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<WatchRule?>(null) }
    val armed = activeModes.contains(ScanService.Mode.WATCHLIST)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (armed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (armed) "Watching" else "Not watching",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (armed) {
                        "Running in the background. Alerts arrive with the screen off."
                    } else {
                        "Turn on to be alerted even when SigEye is closed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = armed,
                onCheckedChange = { on ->
                    if (on) {
                        ScanService.start(context, ScanService.Mode.WATCHLIST)
                    } else {
                        ScanService.stop(context, ScanService.Mode.WATCHLIST)
                    }
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Rules", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = {
            editing = WatchRule(
                id = store.newId(),
                label = "",
                kind = MatchKind.OUI,
                value = "",
            )
        }) { Text("Add rule") }
    }

    if (rules.isEmpty()) {
        Text(
            "No rules yet. Add one here, or open the Device Inspector and tap Watch on " +
                "anything you can see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    rules.forEach { rule ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { editing = rule },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.label.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${rule.kind.label}: ${rule.value}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "above ${rule.minRssi} dBm · one alert per ${rule.cooldownSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { store.setEnabled(rule.id, it) },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Recent hits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (hits.isNotEmpty()) {
            TextButton(onClick = { store.clearHits() }) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (hits.isEmpty()) {
        Text(
            "Nothing yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    hits.take(40).forEach { hit ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                clock.format(Date(hit.atMs)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.displayName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${hit.ruleLabel} · ${hit.address}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${hit.rssi}", style = MaterialTheme.typography.labelMedium)
        }
    }

    editing?.let { rule ->
        RuleDialog(
            rule = rule,
            lists = lists,
            onSave = {
                store.upsert(it)
                editing = null
            },
            onDelete = {
                store.delete(rule.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RuleDialog(
    rule: WatchRule,
    lists: List<String>,
    onSave: (WatchRule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(rule.label) }
    var kind by remember { mutableStateOf(rule.kind) }
    var value by remember { mutableStateOf(rule.value) }
    var minRssi by remember { mutableStateOf(rule.minRssi.toFloat()) }
    var cooldown by remember { mutableStateOf(rule.cooldownSeconds.toFloat()) }
    var kindMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.label.isBlank()) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Alert name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("Match on", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { kindMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(kind.label) }
                DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                    MatchKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                kind = option
                                kindMenu = false
                            },
                        )
                    }
                }
                Text(
                    kind.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(12.dp))
                if (kind == MatchKind.LIST) {
                    if (lists.isEmpty()) {
                        Text(
                            "You have no lists yet. Create one in the Device Inspector.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    lists.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { value = name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (if (value == name) "●  " else "○  ") + name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Only above ${minRssi.roundToInt()} dBm",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = minRssi,
                    onValueChange = { minRssi = it },
                    valueRange = -100f..-40f,
                    steps = 59,
                )
                Text(
                    "Raise this toward -70 to mean \"close to me\" rather than \"somewhere " +
                        "in the area\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "One alert per ${cooldown.roundToInt()}s per device",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = cooldown,
                    onValueChange = { cooldown = it },
                    valueRange = 30f..1800f,
                    steps = 30,
                )
                Text(
                    "A device that stays in range advertises constantly. This is what " +
                        "keeps one arrival to one buzz.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    rule.copy(
                        label = label.ifBlank { kind.label },
                        kind = kind,
                        value = value,
                        minRssi = minRssi.roundToInt(),
                        cooldownSeconds = cooldown.roundToInt(),
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
