package com.sigeye.experiments.radar

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.rf.ProximityEstimator
import com.sigeye.core.analysis.rf.ProximityReading
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BleScanHub
import com.sigeye.experiments.watchlist.WatchStore
import com.sigeye.ui.DeviceActions
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.NewListDialog
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.radar.RadarPanel
import com.sigeye.ui.radar.RadarTarget
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "radar"

/** How often blips take a new position. Slow on purpose - see the comment below. */
private const val TICK_MS = 1_500L
private const val STALE_MS = 12_000L

/** What the radar is showing. */
private sealed interface RadarFilter {
    val label: String

    data object Everything : RadarFilter {
        override val label = "Everything"
    }

    data object Watchlist : RadarFilter {
        override val label = "Watchlist"
    }

    data object Flagged : RadarFilter {
        override val label = "Flagged"
    }

    data class UserList(val name: String) : RadarFilter {
        override val label = name
    }
}

@Composable
fun RadarScreen(
    onBack: () -> Unit,
    onLocate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.RADAR, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To measure signal strength. SigEye never connects to anything.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no scan results without it. Your location is never " +
                        "read or stored.",
                ),
            ),
            footnote = "Distance is inferred from signal strength, which is a rough " +
                "instrument. Direction is not measured at all.",
        ) {
            Live(onLocate)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live(onLocate: (String) -> Unit) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val watchStore = remember { WatchStore.get(context) }

    val estimators = remember { mutableMapOf<String, ProximityEstimator>() }
    val latest = remember { mutableMapOf<String, Advert>() }
    val readings = remember { mutableMapOf<String, ProximityReading>() }

    val notes by book.notes.collectAsStateWithLifecycle()
    val lists by book.lists.collectAsStateWithLifecycle()
    val rules by watchStore.rules.collectAsStateWithLifecycle()
    val health by BleScanHub.health.collectAsStateWithLifecycle()

    var targets by remember { mutableStateOf<List<RadarTarget>>(emptyList()) }
    var filter by remember { mutableStateOf<RadarFilter>(RadarFilter.Everything) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            latest[advert.address] = advert
            val estimator = estimators.getOrPut(advert.address) { ProximityEstimator() }
            readings[advert.address] = estimator.observe(advert.rssi, advert.atMs)
        }
    }

    // Positions are recomputed slowly and then eased into place by the radar. Updating on
    // every packet made blips jitter constantly, which read as noise rather than motion -
    // a device you are walking towards should glide inward, not vibrate.
    LaunchedEffect(filter, rules, notes) {
        while (true) {
            val now = System.currentTimeMillis()
            latest.entries.removeAll { now - it.value.atMs > STALE_MS }
            targets = latest.values
                .filter { matches(it, filter, book, watchStore) }
                .mapNotNull { advert ->
                    val reading = readings[advert.address] ?: return@mapNotNull null
                    RadarTarget(
                        address = advert.address,
                        label = notes[advert.address.uppercase()]?.nickname
                            ?: advert.name?.takeIf { it.isNotBlank() }
                            ?: advert.vendor
                            ?: advert.address,
                        smoothedRssi = reading.smoothedRssi,
                        flagged = Vendors.surveillanceNote(
                            advert.address,
                            advert.companyId,
                            advert.name,
                        ) != null,
                        watched = rules.any { it.matches(advert, book) },
                    )
                }
            delay(TICK_MS)
        }
    }

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

    val options = buildList<RadarFilter> {
        add(RadarFilter.Everything)
        add(RadarFilter.Watchlist)
        add(RadarFilter.Flagged)
        lists.forEach { add(RadarFilter.UserList(it)) }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            FilterChip(
                selected = filter == option,
                onClick = { filter = option },
                label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    RadarPanel(
        targets = targets,
        selected = selected,
        onSelect = { selected = it },
        // This screen draws its own, fuller card below.
        showSelectionCard = false,
        footnote = String.format(
            Locale.US,
            "%.0f packets a second. Pinch or use the buttons to zoom, tap a blip to " +
                "select it. Angle is not direction - one antenna cannot measure a " +
                "bearing, so only distance from the centre means anything.",
            health.advertsPerSecond,
        ),
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { showList = !showList },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (showList) "Hide list" else "Show list (${targets.size})") }

    // Tapping a four-pixel blip is fiddly, and a moving one more so. The list is the
    // reliable way to pick something; hidden by default so the radar stays clean.
    if (showList) {
        Spacer(Modifier.height(8.dp))
        targets.sortedByDescending { it.smoothedRssi }.forEach { target ->
            val isSelected = target.address == selected
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clickable {
                        selected = if (isSelected) null else target.address
                    },
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                        target.flagged -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            target.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (target.watched || target.flagged) {
                            Text(
                                if (target.flagged) "flagged vendor" else "on your watchlist",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (target.flagged) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                        }
                    }
                    Text(
                        "${target.smoothedRssi.roundToInt()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (targets.isEmpty()) {
            Text(
                "Nothing in range on this filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    selected?.let { address ->
        val advert = latest[address]
        val reading = readings[address]
        if (advert != null && reading != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        notes[address.uppercase()]?.nickname
                            ?: advert.name?.takeIf { it.isNotBlank() }
                            ?: advert.vendor
                            ?: address,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Stat(
                            "Signal",
                            "${reading.smoothedRssi.roundToInt()}",
                            "dBm smoothed",
                        )
                        Stat(
                            "Range",
                            String.format(Locale.US, "~%.1f m", reading.metres),
                            reading.zone.label,
                        )
                        Stat(
                            "Trend",
                            reading.trend.arrow,
                            reading.trend.label,
                        )
                    }
                    Vendors.surveillanceNote(
                        address,
                        advert.companyId,
                        advert.name,
                    )?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DeviceActions(
                        address = address,
                        displayName = advert.name?.takeIf { it.isNotBlank() }
                            ?: advert.vendor
                            ?: address,
                        isRandomAddress = advert.isRandomAddress,
                        onRequestNewList = { showNewList = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onLocate(address) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Locate this") }
                    OutlinedButton(
                        onClick = { selected = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Deselect") }
                }
            }
        }
    }

    RadarNewListDialog(showNewList, book) { showNewList = false }
}

@Composable
private fun RadarNewListDialog(show: Boolean, book: DeviceBook, onDismiss: () -> Unit) {
    if (show) NewListDialog(onCreate = { book.createList(it) }, onDismiss = onDismiss)
}

private fun matches(
    advert: Advert,
    filter: RadarFilter,
    book: DeviceBook,
    watchStore: WatchStore,
): Boolean = when (filter) {
    RadarFilter.Everything -> true
    RadarFilter.Watchlist -> watchStore.rules.value.any { it.matches(advert, book) }
    RadarFilter.Flagged ->
        Vendors.surveillanceNote(advert.address, advert.companyId, advert.name) != null

    is RadarFilter.UserList -> book.listsOf(advert.address).contains(filter.name)
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
