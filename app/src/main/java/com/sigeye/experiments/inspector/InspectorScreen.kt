package com.sigeye.experiments.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale

private const val FRESH_MILLIS = 30_000L

@Composable
fun InspectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("← All experiments")
        }
        Text(
            text = "Device Inspector",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Everything broadcasting around you, decoded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    "Android returns no scan results at all without it. Your location is " +
                        "never read or stored.",
                ),
            ),
            footnote = "Advertisements are public broadcasts. Listening is passive - " +
                "nothing you see here can tell it was seen.",
        ) {
            Live()
        }
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val scanner = remember { InspectorScanner(context) }
    val ignoreList = remember { IgnoreList.get(context) }
    val state by scanner.state.collectAsStateWithLifecycle()
    val ignored by ignoreList.addresses.collectAsStateWithLifecycle()

    var sort by remember { mutableStateOf(SortMode.STRONGEST) }
    var selected by remember { mutableStateOf<SeenDevice?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    // Drives both the freshness filter and the periodic scan restart.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            scanner.maintain(4 * 60_000L)
        }
    }

    val visible = state.devices.freshWithin(FRESH_MILLIS, now).sortedBy(sort)

    state.error?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                message,
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    if (state.axonPresent) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Axon hardware in range",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "A device is advertising on Axon Enterprise's IEEE block (00:25:DF). " +
                        "That covers body cameras, docks, TASERs and fleet gear alike, and " +
                        "says nothing about whether anything is recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortMode.entries.forEach { mode ->
            FilterChip(
                selected = sort == mode,
                onClick = { sort = mode },
                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            visible.size.toString() + " nearby  ·  " + ignored.size + " muted",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ignored.isNotEmpty()) {
            TextButton(onClick = { ignoreList.clear() }) {
                Text("Unmute all", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(visible, key = { it.address }) { device ->
            DeviceRow(device = device, onClick = { selected = device })
        }
    }

    selected?.let { device ->
        DeviceDetail(
            device = device,
            muted = ignored.contains(device.address.uppercase()),
            onMute = {
                ignoreList.toggle(device.address)
                scanner.forget(device.address)
                selected = null
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun DeviceRow(device: SeenDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isAxon) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBar(device.rssi)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(device.address)
                        if (device.isRandomAddress) append("  (random)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = buildList {
                    device.vendor?.let { add(it) }
                    if (device.serviceUuids.isNotEmpty()) {
                        add(device.serviceUuids.size.toString() + " svc")
                    }
                    add(device.sightings.toString() + "x")
                }
                Text(
                    tags.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    device.rssi.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Five blocks, one per ~10 dB. A coarse but honest proximity cue. */
@Composable
private fun SignalBar(rssi: Int) {
    val strength = ((rssi + 100) / 12).coerceIn(0, 5)
    Column(
        modifier = Modifier.width(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (level in 5 downTo 1) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (level <= strength) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun DeviceDetail(
    device: SeenDevice,
    muted: Boolean,
    onMute: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.displayName) },
        text = {
            Column {
                Field("Address", device.address + if (device.isRandomAddress) "  (random)" else "")
                Field("OUI", device.oui + (Vendors.byAddress(device.address)?.let { "  $it" } ?: ""))
                device.companyId?.let {
                    Field(
                        "Company ID",
                        Vendors.companyIdHex(it) +
                            (Vendors.byCompanyId(it)?.let { n -> "  $n" } ?: ""),
                    )
                }
                Field("Signal", device.rssi.toString() + " dBm, best " + device.bestRssi)
                Field("Rough range", String.format(Locale.US, "~%.1f m", device.roughMetres()))
                device.txPower?.let { Field("TX power", it.toString() + " dBm") }
                Field("Sightings", device.sightings.toString())
                if (device.serviceUuids.isNotEmpty()) {
                    Field("Services", device.serviceUuids.joinToString("\n"))
                }
                device.serviceData.forEach { (uuid, bytes) ->
                    Field("Service data " + uuid.take(8), bytes.toHex())
                }
                device.manufacturerData?.let { Field("Mfg data", it.toHex()) }

                if (device.isAxon) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "On Axon Enterprise's IEEE block. Could be a body camera, a dock, " +
                            "a TASER or fleet gear - and presence is not recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Rough range assumes a clear path and is routinely wrong by a factor " +
                        "of two indoors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onMute) {
                Text(if (muted) "Unmute" else "Mute this device")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
