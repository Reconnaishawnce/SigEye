package com.sigeye.experiments.congestion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CsvExport
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.RunFigure
import com.sigeye.core.analysis.rf.AdvertChannelLoad
import com.sigeye.core.analysis.rf.ChannelLoad
import com.sigeye.core.analysis.rf.Occupant
import com.sigeye.core.analysis.rf.Reception
import com.sigeye.core.analysis.rf.Spectrum
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.wifi.AccessPoint
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RunHistory
import com.sigeye.ui.Section
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "congestion"
private const val TICK_MS = 1_000L
private const val WIFI_INTERVAL_MS = 10_000L

/** How long a packet history is kept per device before it stops describing "now". */
private const val HISTORY_MS = 30_000L

/** Devices to report reception for. More than this is a wall of numbers, not a reading. */
private const val REPORTED = 6

@Composable
fun CongestionScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.CONGESTION, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To count how much of what each transmitter sends actually arrives.",
                ),
                PermissionReason(
                    "Location",
                    "Android gates both Wi-Fi scan results and Bluetooth results behind it.",
                ),
            ),
            footnote = "Nothing is connected to and nothing is transmitted. This is two " +
                "passive listens, put side by side.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Packet arrivals for one device, trimmed to the recent past. */
private class Traffic {
    val arrivals = ArrayDeque<Long>()
    val rssis = ArrayDeque<Int>()
    var name: String? = null

    fun add(atMs: Long, rssi: Int) {
        arrivals.addLast(atMs)
        rssis.addLast(rssi)
        while (arrivals.isNotEmpty() && atMs - arrivals.first() > HISTORY_MS) {
            arrivals.removeFirst()
            if (rssis.isNotEmpty()) rssis.removeFirst()
        }
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val wifi by WifiScanHub.state.collectAsStateWithLifecycle()

    val traffic = remember { LinkedHashMap<String, Traffic>() }
    var receptions by remember { mutableStateOf<List<Reception>>(emptyList()) }
    var packets by remember { mutableStateOf(0) }

    KeepScreenOn(true)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        WifiScanHub.init(context)
        WifiScanHub.acquire(context, HUB_TAG)
        onDispose {
            BleScanHub.release(HUB_TAG)
            WifiScanHub.release(context, HUB_TAG)
        }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            synchronized(traffic) {
                val entry = traffic.getOrPut(advert.address) { Traffic() }
                entry.add(advert.atMs, advert.rssi)
                advert.name?.takeIf { it.isNotBlank() }?.let { entry.name = it }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            delay(WIFI_INTERVAL_MS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val snapshot = synchronized(traffic) {
                traffic.entries.map { (address, entry) ->
                    Triple(address, entry.arrivals.toList(), entry.name to entry.rssis.toList())
                }
            }
            packets = snapshot.sumOf { it.second.size }
            receptions = snapshot
                .filter { it.second.isNotEmpty() && now - it.second.last() < 5_000 }
                .map { (address, arrivals, named) ->
                    Reception.of(address, named.first, arrivals, named.second)
                }
                .filter { it.usable }
                .sortedByDescending { it.meanRssi ?: -127 }
                .take(REPORTED)
        }
    }

    val occupants = remember(wifi.results) { wifi.results.map { it.asOccupant() } }
    val band24 = remember(occupants) { occupants.filter { it.centerMhz in 2400..2500 } }
    val channels = remember(band24) { Spectrum.channels24(band24) }
    val advertChannels = remember(band24) { Spectrum.advertChannels(band24) }
    val best = remember(receptions) { Reception.best(receptions) }

    Headline(band24 = band24, advertChannels = advertChannels, wifiScans = wifi.scans)

    Spacer(Modifier.height(14.dp))
    Text("The 2.4 GHz band", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Bar height is everything overlapping that channel, added as power. The three " +
            "markers are where Bluetooth advertises.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    SpectrumChart(channels = channels, advertChannels = advertChannels)

    Spacer(Modifier.height(14.dp))
    AdvertChannelCards(advertChannels)

    Spacer(Modifier.height(14.dp))
    Text(
        "What is actually arriving",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "Each device's own advertising interval says how often it transmits. This is how " +
            "much of that reached the phone in the last thirty seconds.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (receptions.isEmpty()) {
        Text(
            "Listening. A device needs a few seconds of packets before its interval can " +
                "be estimated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        receptions.forEach { ReceptionRow(it, best) }
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "Why those three frequencies",
        summary = "2402, 2426 and 2480 - the edges and the gap.",
    ) {
        Text(
            "Bluetooth Low Energy advertises on three fixed frequencies and never moves " +
                "them, because a device that has never spoken to you has no way to " +
                "negotiate where to speak. They were placed around Wi-Fi rather than in " +
                "it: 2402 and 2480 sit at the two ends of the band, outside channels 1 " +
                "and 11, and 2426 sits in the gap between channels 1 and 6. On a band " +
                "where everyone follows the 1/6/11 convention, all three have somewhere " +
                "to breathe.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Which is why an access point parked on channel 3, 4 or 9 does more damage " +
                "than its own users ever see. It is not just overlapping two Wi-Fi " +
                "channels - it is sitting on the frequency every Bluetooth device in the " +
                "building uses to announce itself.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field("Advert channel 37", "2402 MHz, bottom edge of the band")
        Field("Advert channel 38", "2426 MHz, between Wi-Fi 1 and 6")
        Field("Advert channel 39", "2480 MHz, above Wi-Fi 11")
    }

    Spacer(Modifier.height(10.dp))
    OffGrid(band24)

    Spacer(Modifier.height(14.dp))
    OutlinedButton(
        onClick = {
            CsvExport.shareText(
                context = context,
                folder = "congestion",
                prefix = "band",
                content = CsvExport.header(
                    "2.4 GHz congestion",
                    "wifi_scans=${wifi.scans}",
                ) + Spectrum.csv(band24),
            )
        },
        enabled = band24.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the band") }

    Spacer(Modifier.height(14.dp))
    RunHistory(
        experiment = Experiments.CONGESTION,
        figures = if (band24.isEmpty()) {
            emptyList()
        } else {
            val loaded = channels.mapNotNull { load -> load.loadDbm?.let { load to it } }
            listOfNotNull(
                RunFigure("Access points on 2.4", band24.size.toDouble(), decimals = 0),
                loaded.maxByOrNull { it.second }?.let {
                    RunFigure("Busiest channel", it.first.channel.toDouble(), decimals = 0)
                },
                loaded.maxByOrNull { it.second }?.let {
                    RunFigure("Load there", it.second, "dBm", 1, higherIsBetter = false)
                },
                loaded.minByOrNull { it.second }?.let {
                    RunFigure("Emptiest channel", it.first.channel.toDouble(), decimals = 0)
                },
                best?.let {
                    RunFigure("Best reception here", it.observedPerSecond, "pkt/s", 1, higherIsBetter = true)
                },
            )
        },
    )

    Spacer(Modifier.height(14.dp))
    DiagnosticsPanel(
        title = "What this is seeing",
        diagnostics = listOf(
            Diagnostic("Wi-Fi scans", "${wifi.scans}", "completed"),
            Diagnostic("Access points", "${wifi.results.size}", "all bands"),
            Diagnostic("On 2.4 GHz", "${band24.size}", "counted into the chart"),
            Diagnostic("BLE packets", "$packets", "in the last 30 s"),
            Diagnostic("Devices rated", "${receptions.size}", "interval known"),
            Diagnostic(
                "Band busy",
                "${(Spectrum.busyFraction(band24) * 100).roundToInt()}%",
                "of channels 1-13",
            ),
        ),
        verdict = when {
            wifi.throttled -> "Android is throttling Wi-Fi scans, so the chart is going " +
                "stale. Developer options can turn that off."
            wifi.scans == 0 -> "No Wi-Fi scan has come back yet. Wi-Fi has to be switched " +
                "on even to scan - being connected is not required, being enabled is."
            else -> null
        },
        footnote = "A phone cannot measure airtime, only who is present and how loud. " +
            "Occupancy here is summed neighbor power, which correlates with congestion " +
            "but is not the same thing as a busy channel - a single loud access point " +
            "with no traffic on it will read as loud.",
    )
}

// -------------------------------------------------------------------------- headline

@Composable
private fun Headline(
    band24: List<Occupant>,
    advertChannels: List<AdvertChannelLoad>,
    wifiScans: Int,
) {
    val blocked = advertChannels.count { !it.clear }
    val busy = Spectrum.busyFraction(band24)
    val quietest = Spectrum.quietestOfOneSixEleven(band24)

    val container = when {
        wifiScans == 0 -> MaterialTheme.colorScheme.surfaceVariant
        blocked >= 2 -> MaterialTheme.colorScheme.errorContainer
        blocked == 1 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        wifiScans == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        blocked >= 2 -> MaterialTheme.colorScheme.onErrorContainer
        blocked == 1 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when {
                    wifiScans == 0 -> "Waiting for the first Wi-Fi scan"
                    blocked == 0 -> "All three advertising channels are in the clear"
                    blocked == 1 -> "One advertising channel has company"
                    else -> "$blocked of the three advertising channels are under Wi-Fi"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${band24.size} access points on 2.4 GHz · " +
                    "${(busy * 100).roundToInt()}% of channels carrying a loud neighbor" +
                    (quietest?.let { " · emptiest lane is channel ${it.channel}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

// ----------------------------------------------------------------------- the chart

@Composable
private fun SpectrumChart(
    channels: List<ChannelLoad>,
    advertChannels: List<AdvertChannelLoad>,
) {
    val barBusy = MaterialTheme.colorScheme.error
    val barQuiet = MaterialTheme.colorScheme.primary
    val lane = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val labelColour = MaterialTheme.colorScheme.onSurfaceVariant
    val advertColour = MaterialTheme.colorScheme.tertiary
    val measurer = rememberTextMeasurer()

    // Frequency, not channel index, is the x axis. Channels are not evenly spaced in the
    // sense that matters here - drawing them as thirteen equal columns would hide the
    // single most important fact on the chart, which is that they overlap.
    val lowMhz = 2395f
    val highMhz = 2495f

    val spoken = buildString {
        append("Spectrum from 2400 to 2490 megahertz. ")
        val loaded = channels.mapNotNull { load -> load.loadDbm?.let { load to it } }
        loaded.maxByOrNull { it.second }?.let { (load, dbm) ->
            append("Busiest is channel ${load.channel} at ${dbm.roundToInt()} dBm ")
            append("with ${load.occupants} transmitter")
            if (load.occupants != 1) append("s")
            append(". ")
        }
        loaded.minByOrNull { it.second }?.let { (load, _) ->
            append("Emptiest is channel ${load.channel}. ")
        }
        if (advertChannels.isNotEmpty()) {
            append("Bluetooth advertising lanes are drawn on top.")
        }
    }

    Box(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxSize()) {
            val plotHeight = size.height - 26f
            fun x(mhz: Float): Float = (mhz - lowMhz) / (highMhz - lowMhz) * size.width

            // The three conventional lanes, drawn behind everything as context.
            listOf(1, 6, 11).forEach { channel ->
                val center = Spectrum.centerOf(channel).toFloat()
                drawRect(
                    color = lane,
                    topLeft = Offset(x(center - 10f), 0f),
                    size = Size(x(center + 10f) - x(center - 10f), plotHeight),
                )
            }

            repeat(3) { line ->
                val y = plotHeight * (line + 1) / 4f
                drawLine(
                    color = grid,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
            drawLine(
                color = grid,
                start = Offset(0f, plotHeight),
                end = Offset(size.width, plotHeight),
                strokeWidth = 1.5f,
            )

            channels.filter { it.channel in 1..13 }.forEach { channel ->
                val height = Spectrum.barHeight(channel.loadDbm) * plotHeight
                if (height <= 0f) return@forEach
                val center = channel.centerMhz.toFloat()
                val left = x(center - 2f)
                val right = x(center + 2f)
                drawRect(
                    color = if (channel.busy) barBusy else barQuiet,
                    topLeft = Offset(left, plotHeight - height),
                    size = Size((right - left).coerceAtLeast(3f), height),
                )
            }

            advertChannels.forEach { advert ->
                val at = x(advert.frequencyMhz.toFloat())
                drawLine(
                    color = advertColour,
                    start = Offset(at, 0f),
                    end = Offset(at, plotHeight),
                    strokeWidth = 2f,
                    pathEffect = if (advert.clear) {
                        PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                    } else {
                        null
                    },
                )
                drawCircle(
                    color = advertColour,
                    radius = 5f,
                    center = Offset(at, 7f),
                    style = if (advert.clear) Stroke(width = 2f) else Fill,
                )
                val layout = measurer.measure(
                    text = "${advert.channel}",
                    style = TextStyle(fontSize = 9.sp, color = advertColour),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(at + 7f, 1f),
                )
            }

            listOf(1, 6, 11).forEach { channel ->
                val layout = measurer.measure(
                    text = "$channel",
                    style = TextStyle(fontSize = 10.sp, color = labelColour),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x(Spectrum.centerOf(channel).toFloat()) - layout.size.width / 2f,
                        plotHeight + 5f,
                    ),
                )
            }
            listOf(2400 to "2400", 2450 to "2450", 2490 to "2490 MHz").forEach { (mhz, text) ->
                val layout = measurer.measure(
                    text = text,
                    style = TextStyle(fontSize = 9.sp, color = labelColour.copy(alpha = 0.7f)),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (x(mhz.toFloat()) - layout.size.width / 2f)
                            .coerceIn(0f, size.width - layout.size.width),
                        plotHeight + 16f,
                    ),
                )
            }
        }
    }
}

// ------------------------------------------------------------- the three channels

@Composable
private fun AdvertChannelCards(advertChannels: List<AdvertChannelLoad>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        advertChannels.forEach { advert ->
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (advert.clear) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(
                    Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Ch ${advert.channel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (advert.clear) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                    Text(
                        if (advert.clear) "clear" else Spectrum.formatDbm(advert.loadDbm),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (advert.clear) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                    Text(
                        "${advert.frequencyMhz} MHz",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (advert.clear) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- what arrived

@Composable
private fun ReceptionRow(reception: Reception, best: Reception?) {
    val ratio = reception.ratio ?: 0.0
    val poor = ratio < Reception.POOR_RATIO
    val shortfall = Reception.shortfall(reception, best)

    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        reception.label ?: reception.address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (reception.label == null) FontFamily.Monospace else null,
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "every %d ms · %.1f of %.1f per second%s",
                            reception.baseIntervalMs,
                            reception.observedPerSecond,
                            reception.expectedPerSecond ?: 0.0,
                            reception.meanRssi?.let { " · $it dBm" } ?: "",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    reception.percent(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (poor) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Bar(fraction = ratio.toFloat(), poor = poor)
            if (shortfall != null && shortfall >= 15) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "$shortfall points below the best link in view, which rules out the " +
                        "phone's own scanner as the explanation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, poor: Boolean) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val fill = if (poor) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawRect(color = track, size = Size(size.width, size.height))
        drawRect(
            color = fill,
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
        )
    }
}

// ------------------------------------------------------------------- the culprits

@Composable
private fun OffGrid(band24: List<Occupant>) {
    val off = remember(band24) { Spectrum.offGrid(band24).sortedByDescending { it.rssi } }
    if (off.isEmpty()) return

    Section(
        title = "Off the three lanes",
        summary = "${off.size} access point${if (off.size == 1) "" else "s"} not on 1, 6 or 11.",
        emphasis = true,
    ) {
        Text(
            "These overlap two Wi-Fi channels each instead of taking one, and they are " +
                "what puts power onto Bluetooth's advertising frequencies.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        off.take(8).forEach { occupant ->
            Field(
                occupant.label ?: "${occupant.centerMhz} MHz",
                "${occupant.centerMhz} MHz, ${occupant.widthMhz} MHz wide, ${occupant.rssi} dBm",
            )
        }
    }
}

private fun AccessPoint.asOccupant(): Occupant = Occupant(
    centerMhz = occupiedCentreMhz,
    widthMhz = channelWidthMhz,
    rssi = rssi,
    label = ssid?.takeIf { it.isNotBlank() } ?: "hidden ($bssid)",
)
