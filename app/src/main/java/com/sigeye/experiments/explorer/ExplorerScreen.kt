package com.sigeye.experiments.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.Exploration
import com.sigeye.core.ble.ExploreState
import com.sigeye.core.ble.GattExplorer
import com.sigeye.core.ble.GattService
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PauseBar
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Source
import com.sigeye.ui.SourceOrder
import com.sigeye.ui.rememberSources
import java.util.Locale

private const val HUB_TAG = "explorer"


@Composable
fun ExplorerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.EXPLORER, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To find things to ask, and then to ask them.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Unlike every other experiment here, this one transmits. It asks " +
                "before it does.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val explorer = remember { GattExplorer(context) }

    val notes by book.notes.collectAsStateWithLifecycle()
    val exploration by explorer.state.collectAsStateWithLifecycle()

    var paused by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Source?>(null) }

    // Two sightings rather than three: this screen is about connecting to something, and a
    // device that has only spoken twice is still worth offering to connect to.
    val frozen = rememberSources(order = SourceOrder.RANKED, minSightings = 2, paused = paused)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            explorer.close()
            BleScanHub.release(HUB_TAG)
        }
    }


    // Back returns to the picker rather than leaving the experiment, and disconnects
    // on the way out - walking away from an open GATT connection would leave it open.
    BackHandler(enabled = exploration.state != ExploreState.IDLE) {
        explorer.close()
        explorer.reset()
    }

    if (exploration.state != ExploreState.IDLE) {
        Report(exploration = exploration, onClose = { explorer.close(); explorer.reset() })
        return
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "This one transmits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Every other experiment in this app listens to broadcasts that were going " +
                    "to happen anyway, and is invisible. This one opens a connection. The " +
                    "other device sees it, may log it, and on some hardware will show a " +
                    "pairing prompt to whoever is holding it. Only do this to things you " +
                    "own or have permission to poke.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "It reads. It never writes.",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "No characteristic is written, nothing is subscribed to, and it never pairs " +
                    "or bonds. It asks the device to describe itself and reads back what the " +
                    "device already marked readable, then hangs up. Nothing on the other end " +
                    "is changed and nothing is left connected. There is a test that reads " +
                    "the source and fails the build if that ever stops being true.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "What you get back",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A device that accepts a connection will describe its own layout: a list " +
                    "of services, each holding values. Standard ones are in the Bluetooth " +
                    "registry and this app names them. Most devices hand over a name, a " +
                    "maker, sometimes a model and serial number - without any pairing at " +
                    "all. Plenty refuse to talk entirely, which is also an answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    if (frozen.isEmpty()) {
        Text(
            "Listening for something to ask...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    PauseBar(
        paused = paused,
        onToggle = { paused = !paused },
        summary = "${frozen.size} in range",
    )
    Spacer(Modifier.height(6.dp))
    frozen.forEach { candidate ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { confirming = candidate },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        candidate.label(notes[candidate.address.uppercase(Locale.US)]?.nickname),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        candidate.address + if (candidate.isRandom) "  (random)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${candidate.rssi}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    confirming?.let { candidate ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Connect to this device?") },
            text = {
                Column {
                    Text(
                        candidate.label(null),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        candidate.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This will transmit. The device will know something connected to " +
                            "it, and may record that. Nothing is written or changed - the " +
                            "app only reads - but the connection itself is visible.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    explorer.explore(candidate.address)
                    confirming = null
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

// --------------------------------------------------------------------------- report

@Composable
private fun Report(exploration: Exploration, onClose: () -> Unit) {
    val working = exploration.state in setOf(
        ExploreState.CONNECTING,
        ExploreState.DISCOVERING,
        ExploreState.READING,
    )

    Text(
        exploration.state.label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    exploration.address?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (working) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    // The running commentary. This is the teaching part: each step says what just happened
    // and what the word for it means.
    Spacer(Modifier.height(14.dp))
    exploration.steps.forEach { step ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                String.format(Locale.US, "%4.1fs", step.atMs / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column {
                Text(step.text, style = MaterialTheme.typography.bodySmall)
                step.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (exploration.services.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        DiagnosticsPanel(
            title = "What came back",
            diagnostics = listOf(
                Diagnostic("Services", "${exploration.services.size}", "groups of values"),
                Diagnostic("Values", "${exploration.characteristicCount}", "declared"),
                Diagnostic("Read", "${exploration.readCount}", "without pairing"),
            ),
            footnote = "A service is a group of related values. What a device will hand " +
                "over without pairing is entirely its own choice, and varies enormously.",
        )

        exploration.services.forEach { service ->
            Spacer(Modifier.height(12.dp))
            ServiceCard(service)
        }
    }

    Spacer(Modifier.height(16.dp))
    if (working) {
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Stop and disconnect")
        }
    } else {
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Ask something else")
        }
    }
}

@Composable
private fun ServiceCard(service: GattService) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                service.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                service.uuid,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                service.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            service.characteristics.forEach { value ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        value.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        when {
                            value.value != null -> value.value
                            value.failed -> "refused"
                            value.readable -> "..."
                            else -> value.propertyWords
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (value.value != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    value.explanation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
