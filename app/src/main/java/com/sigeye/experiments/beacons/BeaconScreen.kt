package com.sigeye.experiments.beacons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.sigeye.core.Vendors
import com.sigeye.core.ble.Beacon
import com.sigeye.core.ble.BeaconDecoder
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.DeviceActions
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.NewListDialog
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.pow

private const val HUB_TAG = "beacons"
private const val REFRESH_MS = 500L
private const val FRESH_MS = 30_000L

/** One beacon-speaking device, as most recently heard. */
private data class DecodedDevice(
    val address: String,
    val beacon: Beacon,
    val rssi: Int,
    val name: String?,
    val lastSeenMs: Long,
    val sightings: Int,
) {
    /**
     * Distance from the log-distance model, using the beacon's own declared power at 1 m
     * when it has one. Still only as good as the assumption of a clear path.
     */
    fun metres(): Double? {
        val reference = beacon.measuredPower ?: return null
        return 10.0.pow((reference - rssi) / 20.0)
    }
}

@Composable
fun BeaconScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.BEACONS, onBack)
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
            ),
            footnote = "Everything here is broadcast in the clear, to anyone in range, " +
                "by design. Decoding it reveals nothing that was hidden.",
        ) {
            Live()
        }
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val decoded = remember { mutableMapOf<String, DecodedDevice>() }

    val health by BleScanHub.health.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()

    var devices by remember { mutableStateOf<List<DecodedDevice>>(emptyList()) }
    var protocolFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            val beacon = BeaconDecoder.decode(advert) ?: return@collect
            val existing = decoded[advert.address]
            decoded[advert.address] = DecodedDevice(
                address = advert.address,
                beacon = beacon,
                rssi = advert.rssi,
                name = advert.name ?: existing?.name,
                lastSeenMs = advert.atMs,
                sightings = (existing?.sightings ?: 0) + 1,
            )
        }
    }

    // Scanning continues while paused; it is only the rendered snapshot that freezes, so
    // nothing is missed and a row stays still long enough to read and tap.
    LaunchedEffect(paused) {
        while (!paused) {
            delay(REFRESH_MS)
            val now = System.currentTimeMillis()
            devices = decoded.values
                .filter { now - it.lastSeenMs <= FRESH_MS }
                .sortedByDescending { it.rssi }
        }
    }

    val protocols = devices.map { it.beacon.protocol }.distinct().sorted()
    val visible = devices.filter { protocolFilter == null || it.beacon.protocol == protocolFilter }

    health.error?.let { message ->
        Card(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                message,
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    if (protocols.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = protocolFilter == null,
                onClick = { protocolFilter = null },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
            )
            protocols.forEach { protocol ->
                FilterChip(
                    selected = protocolFilter == protocol,
                    onClick = {
                        protocolFilter = if (protocolFilter == protocol) null else protocol
                    },
                    label = { Text(protocol, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${visible.size} decoding · ${protocols.size} formats · " +
                String.format(Locale.US, "%.0f/s", health.advertsPerSecond),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { paused = !paused }) {
            Text(
                if (paused) "Resume" else "Pause",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (paused) {
        Text(
            "Frozen. Still listening in the background, so nothing is missed.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    if (devices.isEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Nothing recognised yet. Most devices advertise nothing structured - it is " +
                "the beacons, tags, headphones and phones running Continuity that show up " +
                "here. Give it a moment, or walk somewhere busier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(8.dp))
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(visible, key = { it.address }) { device ->
            val nickname = notes[device.address.uppercase()]?.nickname
            BeaconRow(
                device = device,
                nickname = nickname,
                onClick = { selected = device.address },
            )
        }
    }

    if (showNewList) {
        NewListDialog(
            onCreate = { book.createList(it) },
            onDismiss = { showNewList = false },
        )
    }

    selected?.let { address ->
        devices.firstOrNull { it.address == address }?.let { device ->
            BeaconDetail(
                device = device,
                nickname = notes[address.uppercase()]?.nickname,
                onRequestNewList = { showNewList = true },
                onDismiss = { selected = null },
            )
        }
    }
}

@Composable
private fun BeaconRow(device: DecodedDevice, nickname: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    nickname ?: device.beacon.summary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    device.beacon.protocol,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${device.rssi}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                device.metres()?.let {
                    Text(
                        String.format(Locale.US, "~%.1f m", it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BeaconDetail(
    device: DecodedDevice,
    nickname: String?,
    onRequestNewList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nickname ?: device.beacon.summary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    device.beacon.protocol,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )

                Spacer(Modifier.height(12.dp))
                DeviceActions(
                    address = device.address,
                    displayName = device.beacon.summary,
                    isRandomAddress = Vendors.isRandomAddress(device.address),
                    onRequestNewList = onRequestNewList,
                )

                Spacer(Modifier.height(12.dp))

                device.beacon.fields.forEach { field ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(
                            field.label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            field.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "ADDRESS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        device.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        "SIGNAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${device.rssi} dBm · seen ${device.sightings}x",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                device.beacon.note?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (device.metres() != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Range comes from the power the beacon claims at one metre. It " +
                            "assumes a clear path and is routinely wrong by a factor of " +
                            "two indoors.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
