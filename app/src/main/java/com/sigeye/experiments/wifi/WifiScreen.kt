package com.sigeye.experiments.wifi

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.sigeye.core.Experiments
import com.sigeye.core.OuiRegistry
import com.sigeye.core.Permissions
import com.sigeye.core.wifi.AccessPoint
import com.sigeye.core.wifi.Confidence
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.core.wifi.WifiSurveillance
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale

private const val HUB_TAG = "wifi"
private const val SCAN_INTERVAL_MS = 10_000L

private enum class Filter(val label: String) {
    ALL("All"),
    FLAGGED("Flagged"),
    HIDDEN("Hidden"),
    OPEN("Open"),
}

@Composable
fun WifiScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.WIFI, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Location",
                    "Android treats the list of nearby access points as location data, so " +
                        "it will not hand one over without this.",
                ),
                PermissionReason("Nearby devices", "Shared with the Bluetooth experiments."),
            ),
            footnote = "Nothing is transmitted beyond an ordinary Wi-Fi scan.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val state by WifiScanHub.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.ALL) }

    DisposableEffect(Unit) {
        WifiScanHub.acquire(context, HUB_TAG)
        onDispose { WifiScanHub.release(context, HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            delay(SCAN_INTERVAL_MS)
        }
    }

    val flagged = state.results.mapNotNull { access ->
        WifiSurveillance.match(access.bssid, access.ssid)?.let { access to it }
    }
    val strong = flagged.count { it.second.confidence == Confidence.STRONG }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Access points only",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This sees networks broadcasting, not the devices connected to them, and " +
                    "not the probe requests phones send out naming networks they have " +
                    "joined before. Capturing those needs monitor mode, which needs a " +
                    "chipset, a driver and root - no app on a stock phone can do it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Networks", "${state.results.size}", "in range")
        Stat("Flagged", "${flagged.size}", "worth a look")
        Stat("Scans", "${state.scans}", "completed")
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Filter.entries.forEach { entry ->
            FilterChip(
                selected = filter == entry,
                onClick = { filter = entry },
                label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What the Wi-Fi scan is seeing",
        verdict = when {
            state.throttled -> state.error
            state.results.isEmpty() && state.scans > 1 ->
                "Scans are completing but returning nothing. Check Wi-Fi is on and that " +
                    "location services are enabled - Android needs both."
            else -> null
        },
        diagnostics = listOf(
            Diagnostic("Networks", "${state.results.size}", "access points"),
            Diagnostic("Scans", "${state.scans}", "since opening"),
            Diagnostic("Strong", "$strong", "registry matches"),
            Diagnostic("Weak", "${flagged.size - strong}", "hints only"),
            Diagnostic("Hidden", "${state.results.count { it.hidden }}", "no name"),
            Diagnostic("Open", "${state.results.count { it.open }}", "no encryption"),
        ),
        footnote = "Android caps scans at four every two minutes unless Wi-Fi scan " +
            "throttling is turned off in developer options. With it on, this picture " +
            "updates far more slowly than it looks like it should.",
    )

    val shown = when (filter) {
        Filter.ALL -> state.results
        Filter.FLAGGED -> flagged.map { it.first }
        Filter.HIDDEN -> state.results.filter { it.hidden }
        Filter.OPEN -> state.results.filter { it.open }
    }

    if (shown.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing to show under this filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    shown.forEach { access ->
        Spacer(Modifier.height(8.dp))
        AccessPointCard(access, WifiSurveillance.match(access.bssid, access.ssid))
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(
        onClick = { WifiScanHub.clear() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Forget what has been seen") }
}

@Composable
private fun AccessPointCard(
    access: AccessPoint,
    match: com.sigeye.core.wifi.WifiMatch?,
) {
    val strong = match?.confidence == Confidence.STRONG
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                strong -> MaterialTheme.colorScheme.errorContainer
                match != null -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        access.ssid ?: "(hidden network)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        access.bssid,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${access.rssi}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${access.band} ch ${access.channel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                listOfNotNull(
                    access.security,
                    OuiRegistry.lookup(WifiSurveillance.ouiOf(access.bssid)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            match?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it.confidence.label.uppercase(Locale.US) + " · " + it.reason,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (strong) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                it.caveat?.let { caveat ->
                    Text(
                        caveat,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (strong) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
