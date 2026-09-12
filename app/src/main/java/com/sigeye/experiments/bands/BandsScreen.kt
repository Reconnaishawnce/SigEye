package com.sigeye.experiments.bands

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.CsvExport
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.BandPair
import com.sigeye.core.analysis.DualBand
import com.sigeye.core.analysis.Penetration
import com.sigeye.core.analysis.Radio
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val HUB_TAG = "bands"
private const val SCAN_INTERVAL_MS = 6_000L

/** Readings kept per radio. Enough to average out a fade, few enough to still mean "here". */
private const val WINDOW = 6

/** A saved spot: where you stood, and what the building did to 5 GHz there. */
private data class Spot(
    val name: String,
    val typicalExcessDb: Double?,
    val pairs: Int,
    val lost5: Int,
)

@Composable
fun BandsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.BANDS, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Location",
                    "Android returns no Wi-Fi scan results at all without it, even though " +
                        "nothing here uses your position.",
                ),
            ),
            footnote = "Passive listening only. Nothing connects and nothing transmits - " +
                "the phone is reading beacons every access point already sends.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val wifi by WifiScanHub.state.collectAsStateWithLifecycle()

    // Keyed by BSSID, so each radio is averaged separately - the whole measurement is a
    // difference between two radios and a mean over both would erase it.
    val history = remember { LinkedHashMap<String, ArrayDeque<Int>>() }
    var pairs by remember { mutableStateOf<List<BandPair>>(emptyList()) }
    var baseline by remember { mutableStateOf<Map<String, Double>?>(null) }
    var baselineAt by remember { mutableStateOf(0L) }
    var spots by remember { mutableStateOf<List<Spot>>(emptyList()) }

    KeepScreenOn(true)

    DisposableEffect(Unit) {
        WifiScanHub.init(context)
        WifiScanHub.acquire(context, HUB_TAG)
        onDispose { WifiScanHub.release(context, HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            delay(SCAN_INTERVAL_MS)
        }
    }

    LaunchedEffect(wifi.scans) {
        val now = System.currentTimeMillis()
        wifi.results.forEach { access ->
            if (now - access.seenAtMs > 20_000) return@forEach
            val run = history.getOrPut(access.bssid) { ArrayDeque() }
            run.addLast(access.rssi)
            while (run.size > WINDOW) run.removeFirst()
        }
        val radios = wifi.results.mapNotNull { access ->
            if (now - access.seenAtMs > 20_000) return@mapNotNull null
            val run = history[access.bssid].orEmpty().toList()
            val mean = DualBand.mean(run) ?: return@mapNotNull null
            Radio(
                bssid = access.bssid,
                ssid = access.ssid,
                frequencyMhz = access.frequencyMhz,
                rssi = mean,
                scans = run.size,
            )
        }
        pairs = DualBand.pair(radios)
    }

    val settled = pairs.filter { it.scans >= DualBand.MIN_SCANS }
    val penetrations = remember(pairs, baseline) {
        DualBand.penetration(pairs, baseline.orEmpty())
    }
    val typical = DualBand.typicalExcessDb(penetrations)

    Headline(
        pairs = pairs,
        settled = settled.size,
        baselineSet = baseline != null,
        typicalExcessDb = typical,
        scans = wifi.scans,
    )

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                baseline = pairs.filter { it.scans >= DualBand.MIN_SCANS }
                    .associate { it.key to it.gapDb }
                baselineAt = System.currentTimeMillis()
                spots = emptyList()
            },
            enabled = settled.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { Text(if (baseline == null) "Set the baseline here" else "Re-baseline here") }

        OutlinedButton(
            onClick = {
                spots = spots + Spot(
                    name = "Spot ${spots.size + 1}",
                    typicalExcessDb = typical,
                    pairs = penetrations.count { it.excessDb != null },
                    lost5 = penetrations.count { it.excessDb != null && !it.usable5 },
                )
            },
            enabled = baseline != null && typical != null,
            modifier = Modifier.weight(1f),
        ) { Text("Save this spot") }
    }

    if (baseline == null) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Why one reading is not the answer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Everyone has heard that 5 GHz does not go through walls as well, and " +
                        "it is true. But the two radios of one access point already differ " +
                        "before any wall is involved: an antenna's effective area falls " +
                        "with the square of frequency, worth about 6.6 dB on its own, the " +
                        "regulations permit different transmit powers in the two bands, " +
                        "the vendor may have picked different ones again, and this phone " +
                        "does not receive both bands equally well either.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "All of that is a constant. So stand somewhere with a clear view of " +
                        "the router, set the baseline, and walk. What changes is the " +
                        "building, and nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    if (pairs.isEmpty()) {
        Text(
            if (wifi.scans == 0) {
                "Waiting for the first scan. Wi-Fi has to be switched on to scan - being " +
                    "connected is not required, being enabled is."
            } else {
                "No dual-band access point in range. This needs one box broadcasting on " +
                    "both 2.4 and 5 GHz, which most routers of the last ten years do."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Dual-band access points in range",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        penetrations.forEach { PairCard(it, baseline != null) }
    }

    if (spots.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("Saved spots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        spots.forEach { spot ->
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(spot.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${spot.pairs} access point${if (spot.pairs == 1) "" else "s"}" +
                                if (spot.lost5 > 0) " · ${spot.lost5} lost 5 GHz" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        spot.typicalExcessDb?.let {
                            String.format(Locale.US, "%+.1f dB", it)
                        } ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if ((spot.typicalExcessDb ?: 0.0) >= DualBand.SERIOUS_DB) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Section(
        title = "What the number means",
        summary = "Excess loss is the part the building is responsible for.",
    ) {
        Text(
            "The gap between the two bands at the baseline is everything that is not the " +
                "building: frequency, transmit power, antennas, and this phone. Subtract " +
                "it and what is left is how much more the walls between here and there " +
                "took from 5 GHz than from 2.4.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field("Under 3 dB", "Not distinguishable from scan noise. Same as the baseline.")
        Field("3 to 10 dB", "A partition, a floor, or simply more distance.")
        Field("Over 10 dB", "Something structural. This is where 5 GHz starts costing you.")
        Field("5 GHz under -75 dBm", "The faster band has stopped being the faster band.")
        Spacer(Modifier.height(8.dp))
        Text(
            "A negative number - 5 GHz doing better than at the baseline - is not a " +
                "mistake. It happens where 2.4 is being interfered with and 5 GHz is not, " +
                "which is most of what Channel Congestion is about.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            CsvExport.shareText(
                context = context,
                folder = "bands",
                prefix = "penetration",
                content = CsvExport.header(
                    "2.4 against 5 GHz penetration",
                    "scans=${wifi.scans}",
                    "spots_saved=${spots.size}",
                ) + DualBand.csv(penetrations, baseline != null),
            )
        },
        enabled = pairs.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the pairs") }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What this is seeing",
        diagnostics = listOf(
            Diagnostic("Scans", "${wifi.scans}", "completed"),
            Diagnostic("Access points", "${wifi.results.size}", "all bands"),
            Diagnostic("Dual-band", "${pairs.size}", "pairs matched"),
            Diagnostic("Settled", "${settled.size}", "${DualBand.MIN_SCANS}+ scans each"),
            Diagnostic("Guessed pairs", "${pairs.count { !it.sameHardware }}", "matched by name"),
            Diagnostic(
                "Baseline",
                if (baseline == null) "none" else "${baseline?.size} pairs",
                if (baselineAt == 0L) "not set" else "locked in",
            ),
        ),
        verdict = when {
            wifi.throttled -> "Android is throttling Wi-Fi scans, so readings are going " +
                "stale between updates."
            pairs.any { !it.sameHardware } -> "Some pairs were matched on network name " +
                "rather than on hardware. In a building with mesh nodes that can pair two " +
                "different boxes, and the gap then measures the distance between them."
            else -> null
        },
        footnote = "Averages are taken in decibels rather than in power, because " +
            "shadowing from walls is close to Gaussian in dB - which is exactly the " +
            "quantity being compared between two places. Averaging power instead would " +
            "let one constructive fade make a wall look thinner than it is.",
    )
}

// -------------------------------------------------------------------------- headline

@Composable
private fun Headline(
    pairs: List<BandPair>,
    settled: Int,
    baselineSet: Boolean,
    typicalExcessDb: Double?,
    scans: Int,
) {
    val serious = typicalExcessDb != null && typicalExcessDb >= DualBand.SERIOUS_DB
    val container = when {
        !baselineSet -> MaterialTheme.colorScheme.surfaceVariant
        serious -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        !baselineSet -> MaterialTheme.colorScheme.onSurfaceVariant
        serious -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when {
                    scans == 0 -> "Waiting for the first scan"
                    pairs.isEmpty() -> "No dual-band access point yet"
                    !baselineSet -> "Stand in sight of the router, then set the baseline"
                    typicalExcessDb == null -> "Walk somewhere else"
                    else -> String.format(
                        Locale.US,
                        "%+.1f dB of extra loss on 5 GHz here",
                        typicalExcessDb,
                    )
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (baselineSet) {
                    "Measured against where you set the baseline, across $settled access " +
                        "point${if (settled == 1) "" else "s"}. The median, so one " +
                        "router behind a lift shaft cannot speak for a building."
                } else {
                    "$settled of ${pairs.size} dual-band pair" +
                        "${if (pairs.size == 1) "" else "s"} settled and ready."
                },
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

// --------------------------------------------------------------------- one access point

@Composable
private fun PairCard(penetration: Penetration, baselineSet: Boolean) {
    val pair = penetration.pair
    val excess = penetration.excessDb

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(pair.label(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${pair.low.bssid} · ${pair.low.frequencyMhz} / " +
                            "${pair.high.frequencyMhz} MHz",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (baselineSet) {
                    Text(
                        excess?.let { String.format(Locale.US, "%+.1f", it) } ?: "new",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            excess == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            excess >= DualBand.SERIOUS_DB -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            BandBars(lowDbm = pair.low.rssi, highDbm = pair.high.rssi)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "2.4 GHz ${pair.low.rssi.roundToInt()} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "5 GHz ${pair.high.rssi.roundToInt()} dBm" +
                        if (!penetration.usable5) " · too weak to be worth it" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (penetration.usable5) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                String.format(
                    Locale.US,
                    "Gap %.1f dB, of which %.1f is frequency alone · %s",
                    pair.gapDb,
                    pair.freeSpaceGapDb,
                    DualBand.describe(excess),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!pair.sameHardware) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Matched on network name, not hardware - these two radios may be " +
                        "different boxes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            penetration.verdict()?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Two bars on one scale, because the comparison is the whole point. */
@Composable
private fun BandBars(lowDbm: Double, highDbm: Double) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val lowColour = MaterialTheme.colorScheme.primary
    val highColour = MaterialTheme.colorScheme.tertiary

    fun fraction(dbm: Double): Float =
        (((dbm + 95.0) / 60.0).coerceIn(0.0, 1.0)).toFloat()

    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val barHeight = size.height / 2f - 3f
        drawRect(color = track, size = Size(size.width, barHeight))
        drawRect(color = lowColour, size = Size(size.width * fraction(lowDbm), barHeight))
        drawRect(
            color = track,
            topLeft = Offset(0f, barHeight + 6f),
            size = Size(size.width, barHeight),
        )
        drawRect(
            color = highColour,
            topLeft = Offset(0f, barHeight + 6f),
            size = Size(size.width * fraction(highDbm), barHeight),
        )
    }
}
