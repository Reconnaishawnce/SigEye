package com.sigeye.experiments.ranging

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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CsvExport
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.SettingsStore
import com.sigeye.core.analysis.rf.RangeCheck
import com.sigeye.core.analysis.rf.TrueRange
import com.sigeye.core.wifi.RangeFix
import com.sigeye.core.wifi.RttRanger
import com.sigeye.core.wifi.RttSupport
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.ui.CountUp
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.NextStep
import com.sigeye.ui.NextStepCard
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.StepTone
import java.util.Locale
import kotlinx.coroutines.delay

private const val HUB_TAG = "ranging"

@Composable
fun RangingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.RTT, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Location",
                    "Android will not range without it, even though nothing here reads " +
                        "your position.",
                ),
            ),
            footnote = "This one transmits. Ranging is an exchange of timed frames with an " +
                "access point, so unlike the rest of SigEye it is not passive listening - " +
                "and it only happens when you press the button.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val ranger = remember { RttRanger(context) }
    val tuning = remember { SettingsStore.get(context).tuning }

    val fixes by ranger.fixes.collectAsStateWithLifecycle()
    val busy by ranger.busy.collectAsStateWithLifecycle()
    val trouble by ranger.trouble.collectAsStateWithLifecycle()
    val wifi by WifiScanHub.state.collectAsStateWithLifecycle()

    var responders by remember { mutableStateOf<List<RttRanger.Responder>>(emptyList()) }

    DisposableEffect(Unit) {
        WifiScanHub.init(context)
        WifiScanHub.acquire(context, HUB_TAG)
        onDispose { WifiScanHub.release(context, HUB_TAG) }
    }

    // Ranging works off the last scan, so the scan has to keep happening.
    LaunchedEffect(Unit) {
        while (true) {
            WifiScanHub.requestScan()
            responders = ranger.responders()
            delay(6_000)
        }
    }

    val support = remember(wifi.scans) { ranger.support() }

    if (support != RttSupport.Ready) {
        NextStepCard(
            when (support) {
                RttSupport.TooOld -> NextStep(
                    problem = "This phone is too old to range",
                    doThis = "802.11mc arrived in Android 9. Nothing before it can measure " +
                        "time of flight, and no setting changes that.",
                )

                RttSupport.NotSupported -> NextStep(
                    problem = "This phone has no ranging radio",
                    doThis = "802.11mc is a chipset feature and most phones do not have it. " +
                        "Pixels from the 3 onward mostly do, and a lot of other flagships " +
                        "do not. Everything else in SigEye works without it.",
                )

                RttSupport.Disabled -> NextStep(
                    problem = "Ranging is switched off",
                    doThis = "Turn Wi-Fi scanning on in Location settings. Ranging rides on " +
                        "the same switch.",
                    tone = StepTone.SHAKY,
                )

                RttSupport.Ready -> NextStep("", "")
            },
        )
        return
    }

    // The exponent this room has been assumed to have, which is what the check is against.
    val assumed = 2.5

    val verdict = remember(fixes) {
        TrueRange.check(
            readings = fixes.map {
                TrueRange.Reading(
                    bssid = it.bssid,
                    label = it.ssid ?: it.bssid,
                    distanceM = it.distanceM,
                    rssi = it.rssi,
                    trustworthy = it.trustworthy,
                )
            },
            assumed = assumed,
        )
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = responders.size, fontSize = 72.sp)
        Text(
            "access points here will answer a ranging request",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "of ${wifi.results.size} in range",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { ranger.measure() },
        enabled = !busy && responders.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (busy) "Ranging..." else "Measure the distances")
    }

    trouble?.let {
        Spacer(Modifier.height(10.dp))
        NextStepCard(
            NextStep(
                problem = "Nothing came back",
                doThis = it,
                tone = StepTone.SHAKY,
            ),
        )
    }

    if (responders.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        NextStepCard(
            NextStep(
                problem = "Nothing here answers ranging",
                doThis = "The access point has to support 802.11mc too, and most do not. " +
                    "Google Nest Wifi and a lot of recent mesh kit do. This is the one " +
                    "experiment that needs the other end to cooperate.",
                tone = StepTone.SHAKY,
            ),
        )
    }

    if (fixes.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text(
            "Two rulers, same instant",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "The bar is what the radio timed. The mark is where loudness thought it was.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val furthest = verdict.checks.maxOf { maxOf(it.truthM, it.guessM) }.coerceAtLeast(1.0)
        verdict.checks.forEach { check ->
            RangeRow(check, furthest)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !verdict.enough -> MaterialTheme.colorScheme.surfaceVariant
                    verdict.modelIsOff -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "What this says about the rest of the app",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(verdict.verdict(), style = MaterialTheme.typography.bodySmall)
                verdict.typicalErrorM?.let {
                    Spacer(Modifier.height(8.dp))
                    Field(
                        "Typical error",
                        String.format(Locale.US, "%.1f m", it),
                    )
                }
                verdict.measuredExponent?.let {
                    Field("Measured exponent", String.format(Locale.US, "%.2f", it))
                }
                Field("Assumed exponent", String.format(Locale.US, "%.2f", assumed))
            }
        }

        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = {
                CsvExport.shareText(
                    context = context,
                    folder = "ranging",
                    prefix = "rtt",
                    content = CsvExport.header(
                        "802.11mc ranging against the path loss estimate",
                        "assumed_exponent=$assumed",
                        "measured_exponent=${verdict.measuredExponent ?: "none"}",
                    ) + csv(fixes, verdict.checks),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export the readings") }
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "Why this is the only honest ruler here",
        summary = "Everything else in SigEye infers distance. This one measures it.",
    ) {
        Text(
            "Loudness is turned into metres by inverting a model: a signal is assumed to " +
                "fall away at some rate, and the rate is a number somebody picked. Two for " +
                "open air, three or so indoors, four through walls. Get it wrong and every " +
                "distance the app prints is wrong by the same factor, quietly.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "802.11mc does not care. The phone and the access point exchange timestamped " +
                "frames and the distance comes out of how long light took, which is a " +
                "length rather than an argument about one. Radio covers about thirty " +
                "centimetres in a nanosecond, so the whole trick is timing to that " +
                "precision at both ends - which is why both ends have to support it and " +
                "why most access points do not.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field("Under a metre of spread", "Clean. A direct path.")
        Field("Several metres", "The radio is timing a reflection, not the wall in front of you.")
        Field("Half the bursts failing", "Something is in the way. The number is mostly guesswork.")
        Spacer(Modifier.height(8.dp))
        Text(
            "It still is not perfect. A blocked direct path makes ranging read long, " +
                "because the first signal to arrive went the long way round.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One access point, both rulers side by side. */
@Composable
private fun RangeRow(check: RangeCheck, furthestM: Double) {
    val truth = MaterialTheme.colorScheme.primary
    val guess = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                check.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                String.format(Locale.US, "%+.1f m", check.errorM),
                style = MaterialTheme.typography.labelMedium,
                color = if (check.trustworthy) guess else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            check.bssid,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            drawRect(color = track, size = Size(size.width, size.height * 0.45f))
            drawRect(
                color = if (check.trustworthy) truth else truth.copy(alpha = 0.35f),
                size = Size(
                    size.width * (check.truthM / furthestM).toFloat().coerceIn(0f, 1f),
                    size.height * 0.45f,
                ),
            )
            // Where loudness put it, as a mark rather than a bar, because it is a claim
            // about the same distance rather than a second distance.
            val at = size.width * (check.guessM / furthestM).toFloat().coerceIn(0f, 1f)
            drawLine(
                color = guess,
                start = Offset(at, 0f),
                end = Offset(at, size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            check.describe() + if (check.trustworthy) "" else "  (not clean enough to fit)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun csv(fixes: List<RangeFix>, checks: List<RangeCheck>): String = buildString {
    appendLine("bssid,ssid,distance_m,spread_m,rssi_dbm,successful,attempted,estimate_m,error_m")
    val byAddress = checks.associateBy { it.bssid }
    fixes.forEach { fix ->
        val check = byAddress[fix.bssid]
        append(fix.bssid)
        append(',')
        append(fix.ssid?.replace(',', ' ').orEmpty())
        append(',')
        append(String.format(Locale.US, "%.3f", fix.distanceM))
        append(',')
        append(String.format(Locale.US, "%.3f", fix.spreadM))
        append(',')
        append(fix.rssi)
        append(',')
        append(fix.successful)
        append(',')
        append(fix.attempted)
        append(',')
        append(check?.let { String.format(Locale.US, "%.3f", it.guessM) }.orEmpty())
        append(',')
        appendLine(check?.let { String.format(Locale.US, "%.3f", it.errorM) }.orEmpty())
    }
}
