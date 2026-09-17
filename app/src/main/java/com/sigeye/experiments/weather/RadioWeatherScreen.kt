package com.sigeye.experiments.weather

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.rf.Emitter
import com.sigeye.core.analysis.rf.RadioWeather
import com.sigeye.core.analysis.rf.Source
import com.sigeye.core.analysis.rf.Weather
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.cell.CellReader
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "weather"

/** How long a Bluetooth device counts toward the total after its last packet. */
private const val BLE_FRESH_MS = 20_000L

@Composable
fun RadioWeatherScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.WEATHER, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To hear Bluetooth transmitters. Nothing connects to anything.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no Wi-Fi or Bluetooth scan results without it.",
                ),
                PermissionReason(
                    "Phone state",
                    "To read the cell the modem is camped on and the neighbours it sees.",
                ),
            ),
            footnote = "Passive listening. This counts other people's transmitters and " +
                "cannot see your own phone's, which is the largest one near you by a wide " +
                "margin.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val cells = remember { CellReader(context) }
    val ignoreList = remember { IgnoreList.get(context) }

    val wifi by WifiScanHub.state.collectAsStateWithLifecycle()
    var weather by remember { mutableStateOf(Weather(emptyList(), null, emptyMap(), emptyMap())) }
    val heard = remember { LinkedHashMap<String, Pair<Int, Long>>() }

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
            if (ignoreList.isIgnored(advert.address)) return@collect
            heard[advert.address.uppercase(Locale.US)] = advert.rssi to advert.atMs
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            val now = System.currentTimeMillis()
            heard.entries.removeAll { now - it.value.second > BLE_FRESH_MS }

            val emitters = buildList {
                // The serving cell only. The reader gives one sample rather than the whole
                // neighbour list, so cellular here is the tower this phone is actually
                // talking to and not everything the modem can see. The channel key is
                // carried anyway, because the moment neighbours are read they will include
                // several reports of one transmission and adding those up would invent
                // power that is not arriving.
                cells.read(now)?.let { cell ->
                    cell.dbm?.let { dbm ->
                        add(
                            Emitter(
                                source = Source.CELLULAR,
                                label = cell.technology + (cell.channel?.let { " $it" } ?: ""),
                                dbm = dbm,
                                channelKey = cell.channel?.let { "${cell.technology}-$it" },
                            ),
                        )
                    }
                }
                wifi.results.forEach { access ->
                    add(Emitter(Source.WIFI, access.ssid ?: access.bssid, access.rssi))
                }
                heard.forEach { (address, reading) ->
                    add(Emitter(Source.BLUETOOTH, address.takeLast(8), reading.first))
                }
            }

            weather = RadioWeather.combine(emitters)
            delay(3_000)
        }
    }

    Gauge(weather)

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                RadioWeather.describe(weather.totalDbm),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(RadioWeather.shape(weather), style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Where it is coming from",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Source.entries.forEach { source ->
        SourceBar(source, weather)
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Cellular here is the one tower this phone is camped on, not every tower the modem " +
            "can see. The others are there and are not counted.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "The bars are shares of power, not of how many there are. One cell tower at -55 " +
            "outweighs forty Bluetooth tags at -90 by a factor of thousands, which is why " +
            "counting transmitters tells you almost nothing.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The point of the whole screen, and it goes above the loudest list rather than below
    // it, because somebody who reads one thing here should read this.
    Spacer(Modifier.height(16.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "The biggest transmitter near you is not on this list",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "It is this phone. A handset pushing a signal to a tower a mile away, held " +
                    "against a head, puts out more than everything on this screen put " +
                    "together and by a long way. No phone will tell an app its own transmit " +
                    "power, so the one thing people install a meter to find out is the one " +
                    "thing no meter on a phone can measure.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Which is worth knowing before trusting any app in this category, including " +
                    "this one.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (weather.emitters.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "The loudest things in earshot",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        weather.emitters.take(12).forEach { emitter ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${emitter.source.label}  ${emitter.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colourOf(emitter.source),
                )
                Text("${emitter.dbm} dBm", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "Why this is not an exposure meter",
        summary = "It measures what arrives here. That is a different thing.",
        emphasis = true,
    ) {
        Text(
            "Three reasons, and none of them is a technicality.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Field("Duty cycle", "An access point beaconing while idle and one running flat out " +
            "read the same here. They are not the same amount of radio, and nothing in a " +
            "scan result says which you are looking at.")
        Spacer(Modifier.height(6.dp))
        Field("Calibration", "RSSI is uncalibrated. Antenna gain, which way the phone is " +
            "facing, the hand around it and the manufacturer's own correction move it by " +
            "several decibels before anything else does.")
        Spacer(Modifier.height(6.dp))
        Field("The missing transmitter", "Your own uplink, which dominates everything here " +
            "and is invisible to every app on every phone.")
        Spacer(Modifier.height(10.dp))
        Text(
            "So this screen says busy or quiet, and never safe or unsafe. A busy place and " +
                "a quiet one differ by a factor of thousands in power and both sit far " +
                "below any exposure limit anybody has written down. A number that cannot " +
                "distinguish those two things has no business being coloured red.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(12.dp))
    Section(
        title = "Why decibels cannot be added",
        summary = "Two signals at -70 are -67, not -140.",
    ) {
        Text(
            "A decibel is a logarithm, and logarithms multiply rather than add. Two equal " +
                "transmitters are twice the power, which is three decibels more - so -70 " +
                "and -70 together are -67. Ten of them are ten decibels more, at -60.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Everything here is converted to power, added, and converted back. It is the " +
                "same arithmetic Channel Congestion uses to stack overlapping Wi-Fi " +
                "channels, and getting it wrong is the most common fault in anything that " +
                "claims to measure radio.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Cellular gets one extra step. A modem reports the cell it is camped on and the " +
                "neighbours it can see, and several of those are frequently the same " +
                "transmission arriving once. Where the channel is known, only the strongest " +
                "on it is counted, or cellular would inflate itself two or three times over.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The total, as a dial that fills. */
@Composable
private fun Gauge(weather: Weather) {
    val target = RadioWeather.scale(weather.totalDbm)
    val filled by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800),
        label = "gauge",
    )

    val track = MaterialTheme.colorScheme.surfaceVariant
    val cool = MaterialTheme.colorScheme.primary
    val warm = MaterialTheme.colorScheme.tertiary
    val needle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val stroke = 16.dp.toPx()
            val radius = minOf(size.width / 2f, size.height) - stroke
            val centre = Offset(size.width / 2f, size.height - 8.dp.toPx())
            val box = Size(radius * 2, radius * 2)
            val corner = Offset(centre.x - radius, centre.y - radius)

            // Two thirds of a circle, opening downward, which is the shape every meter
            // that has ever existed uses and so needs no explaining.
            drawArc(
                color = track,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = corner,
                size = box,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = lerp(cool, warm, filled),
                startAngle = 180f,
                sweepAngle = 180f * filled,
                useCenter = false,
                topLeft = corner,
                size = box,
                style = Stroke(width = stroke),
            )

            // A needle, because a dial without one is a progress bar bent into a curve.
            val angle = (180f + 180f * filled) * PI.toFloat() / 180f
            drawLine(
                color = needle,
                start = centre,
                end = Offset(
                    centre.x + (radius - stroke) * kotlin.math.cos(angle),
                    centre.y + (radius - stroke) * kotlin.math.sin(angle),
                ),
                strokeWidth = 2.dp.toPx(),
            )
        }

        Column(
            Modifier.padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                weather.totalDbm?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp,
            )
            Text(
                "dBm arriving here",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${weather.heard} transmitters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One radio's share, as a bar. */
@Composable
private fun SourceBar(source: Source, weather: Weather) {
    val dbm = weather.perSource[source]
    val total = weather.perSource.values.sumOf { RadioWeather.milliwatts(it) }
    val share = if (dbm == null || total <= 0.0) {
        0f
    } else {
        (RadioWeather.milliwatts(dbm) / total).toFloat()
    }
    val filled by animateFloatAsState(share, tween(600), label = "share")

    val colour = colourOf(source)
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                source.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colour,
            )
            Text(
                if (dbm == null) {
                    "nothing heard"
                } else {
                    String.format(
                        Locale.US,
                        "%.0f dBm  ·  %d%%  ·  %d",
                        dbm,
                        (share * 100).roundToInt(),
                        weather.counts[source] ?: 0,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRect(track, size = size)
            drawRect(colour, size = Size(size.width * filled.coerceIn(0f, 1f), size.height))
        }
    }
}

@Composable
private fun colourOf(source: Source): Color = when (source) {
    Source.CELLULAR -> MaterialTheme.colorScheme.error
    Source.WIFI -> MaterialTheme.colorScheme.primary
    Source.BLUETOOTH -> MaterialTheme.colorScheme.tertiary
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t.coerceIn(0f, 1f),
    green = from.green + (to.green - from.green) * t.coerceIn(0f, 1f),
    blue = from.blue + (to.blue - from.blue) * t.coerceIn(0f, 1f),
    alpha = 1f,
)
