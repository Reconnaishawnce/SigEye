package com.sigeye.experiments.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.sigeye.core.DeviceBook
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.ble.BeaconDecoder
import com.sigeye.core.ble.BleScanHub
import com.sigeye.experiments.watchlist.MatchKind
import com.sigeye.experiments.watchlist.WatchRule
import com.sigeye.experiments.watchlist.WatchStore
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale

private const val FRESH_MILLIS = 30_000L
private const val HUB_TAG = "inspector"

/** How often the device list is rebuilt for the UI. Fast enough to feel live, slow
 *  enough that a busy street does not recompose the list hundreds of times a second. */
private const val REFRESH_MS = 400L

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
    val book = remember { DeviceBook.get(context) }
    val ignoreList = remember { IgnoreList.get(context) }
    val watchStore = remember { WatchStore.get(context) }
    val table = remember { DeviceTable() }

    val health by BleScanHub.health.collectAsStateWithLifecycle()
    val notes by book.notes.collectAsStateWithLifecycle()
    val lists by book.lists.collectAsStateWithLifecycle()
    val ignored by ignoreList.addresses.collectAsStateWithLifecycle()

    var sort by remember { mutableStateOf(SortMode.STRONGEST) }
    var selected by remember { mutableStateOf<String?>(null) }
    var onlyList by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<SeenDevice>>(emptyList()) }
    var showNewList by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { table.record(it) }
    }

    // Snapshot on a timer rather than per advertisement - see REFRESH_MS. Pausing stops
    // the redraw, not the recording.
    LaunchedEffect(paused) {
        while (!paused) {
            delay(REFRESH_MS)
            devices = table.snapshot().freshWithin(FRESH_MILLIS, System.currentTimeMillis())
        }
    }

    fun nameOf(device: SeenDevice): String =
        notes[device.address.uppercase()]?.nickname?.takeIf { it.isNotBlank() }
            ?: device.fallbackName

    val visible = devices
        .filter { onlyList == null || book.listsOf(it.address).contains(onlyList) }
        .sortedBy(sort, ::nameOf)

    health.error?.let { message -> Banner(message, error = true) }
    if (health.starved) {
        Banner("Signal starved. Restarting the scan automatically.", error = true)
    }
    devices.firstNotNullOfOrNull { device ->
        Vendors.surveillanceNote(device.address, device.companyId, device.name)
    }?.let { note -> Banner(note, error = true) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

    if (lists.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = onlyList == null,
                onClick = { onlyList = null },
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
            )
            lists.forEach { list ->
                FilterChip(
                    selected = onlyList == list,
                    onClick = { onlyList = if (onlyList == list) null else list },
                    label = { Text(list, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${visible.size} shown · ${ignored.size} muted · " +
                String.format(Locale.US, "%.0f/s", health.advertsPerSecond),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { paused = !paused }) {
                Text(
                    if (paused) "Resume" else "Pause",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { showNewList = true }) {
                Text("New list", style = MaterialTheme.typography.labelSmall)
            }
            if (ignored.isNotEmpty()) {
                TextButton(onClick = { ignoreList.clear() }) {
                    Text("Unmute all", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(visible, key = { it.address }) { device ->
            DeviceRow(
                device = device,
                title = nameOf(device),
                nicknamed = notes[device.address.uppercase()]?.nickname != null,
                lists = book.listsOf(device.address),
                onClick = { selected = device.address },
            )
        }
    }

    selected?.let { address ->
        devices.firstOrNull { it.address == address }?.let { device ->
            DeviceDetail(
                device = device,
                title = nameOf(device),
                nickname = notes[address.uppercase()]?.nickname.orEmpty(),
                allLists = lists,
                memberOf = book.listsOf(address),
                muted = ignored.contains(address.uppercase()),
                onNickname = { book.setNickname(address, it) },
                onToggleList = { book.toggleList(address, it) },
                onNewList = { showNewList = true },
                onMute = {
                    ignoreList.toggle(address)
                    table.forget(address)
                    selected = null
                },
                onWatch = {
                    watchStore.upsert(
                        WatchRule(
                            id = watchStore.newId(),
                            label = nameOf(device),
                            kind = MatchKind.ADDRESS,
                            value = address,
                        ),
                    )
                    selected = null
                },
                onDismiss = { selected = null },
            )
        }
    }

    if (showNewList) {
        NewListDialog(
            onCreate = { book.createList(it) },
            onDismiss = { showNewList = false },
        )
    }
}

@Composable
private fun Banner(message: String, error: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            message,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: SeenDevice,
    title: String,
    nicknamed: Boolean,
    lists: Set<String>,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (device.flagged) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBar(device.rssi)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (nicknamed) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "★",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    device.address + if (device.isRandomAddress) "  (random)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val tags = buildList {
                    device.vendor?.let { add(it) }
                    if (lists.isNotEmpty()) add(lists.joinToString(", "))
                    add("${device.sightings}x")
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

/** Five blocks, one per ~12 dB. A coarse but honest proximity cue. */
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
    title: String,
    nickname: String,
    allLists: List<String>,
    memberOf: Set<String>,
    muted: Boolean,
    onNickname: (String) -> Unit,
    onToggleList: (String) -> Unit,
    onNewList: () -> Unit,
    onMute: () -> Unit,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(device.address) { mutableStateOf(nickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onNickname(it)
                    },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (device.isRandomAddress) {
                    Text(
                        "This address is randomised, so it will change within about " +
                            "fifteen minutes and the name will not follow it. Nicknames " +
                            "stick only to devices with a fixed address.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Lists", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onNewList) {
                        Text("New", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (allLists.isEmpty()) {
                    Text(
                        "No lists yet. Create one to group devices and watch the whole " +
                            "group with a single rule.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    allLists.forEach { list ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggleList(list) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = memberOf.contains(list),
                                onCheckedChange = { onToggleList(list) },
                            )
                            Text(list, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                BeaconDecoder.decode(
                    companyId = device.companyId,
                    manufacturerData = device.manufacturerData,
                    serviceData = device.serviceData,
                    serviceUuids = device.serviceUuids,
                )?.let { beacon ->
                    Field("Format", beacon.protocol + " — " + beacon.summary)
                    beacon.fields.forEach { Field(it.label, it.value) }
                    beacon.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                Field(
                    "Address",
                    device.address + if (device.isRandomAddress) "  (random)" else "",
                )
                Field(
                    "OUI",
                    device.oui + (Vendors.byAddress(device.address)?.let { "  $it" } ?: ""),
                )
                device.companyId?.let {
                    Field(
                        "Company ID",
                        Vendors.companyIdHex(it) +
                            (Vendors.byCompanyId(it)?.let { name -> "  $name" } ?: ""),
                    )
                }
                Field("Signal", "${device.rssi} dBm, best ${device.bestRssi}")
                Field("Rough range", String.format(Locale.US, "~%.1f m", device.roughMetres()))
                device.txPower?.let { Field("TX power", "$it dBm") }
                Field("Sightings", device.sightings.toString())
                if (device.serviceUuids.isNotEmpty()) {
                    Field("Services", device.serviceUuids.joinToString("\n"))
                }
                device.serviceData.forEach { (uuid, bytes) ->
                    Field("Service data ${uuid.take(8)}", bytes.toHex())
                }
                device.manufacturerData?.let { Field("Mfg data", it.toHex()) }

                Vendors.surveillanceNote(
                    device.address,
                    device.companyId,
                    device.name,
                )?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rough range assumes a clear path and is routinely wrong by a factor " +
                        "of two indoors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onWatch) { Text("Watch") }
                TextButton(onClick = onMute) { Text(if (muted) "Unmute" else "Mute") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NewListDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
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
                    "Group devices you care about - Vehicles, Neighbours, Mine. A watch " +
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
