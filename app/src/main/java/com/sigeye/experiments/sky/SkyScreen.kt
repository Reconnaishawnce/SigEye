package com.sigeye.experiments.sky

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CsvExport
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.gnss.Sky
import com.sigeye.core.sensors.SkyWatcher
import com.sigeye.ui.CountUp
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.NextStep
import com.sigeye.ui.NextStepCard
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.StepTone
import java.util.Locale

@Composable
fun SkyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.SKY, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Location",
                    "Android will not report satellite status without it. Nothing here " +
                        "asks the phone for a position or records one.",
                ),
            ),
            footnote = "Receive only, and more so than the rest of SigEye - this reads what " +
                "the receiver already hears without asking it to go and find a fix.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val watcher = remember { SkyWatcher(context) }
    val view by watcher.sky.collectAsStateWithLifecycle()
    val heard by watcher.heard.collectAsStateWithLifecycle()

    KeepScreenOn(true)

    DisposableEffect(Unit) {
        watcher.start()
        onDispose { watcher.stop() }
    }

    if (!watcher.available) {
        NextStepCard(
            NextStep(
                problem = "This phone reports no satellite receiver",
                doThis = "Which is unusual enough that it is probably an emulator or a " +
                    "device with location hardware disabled at the system level.",
            ),
        )
        return
    }

    if (!watcher.enabled) {
        NextStepCard(
            NextStep(
                problem = "Location is switched off",
                doThis = "Turn location on in system settings. The receiver reports nothing " +
                    "at all while it is off, which is different from hearing nothing.",
                tone = StepTone.SHAKY,
            ),
        )
        Spacer(Modifier.height(12.dp))
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = view.used.size, fontSize = 88.sp)
        Text(
            "satellites being used for a position",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${view.visible} heard across ${view.constellations.size} constellations",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !heard -> MaterialTheme.colorScheme.surfaceVariant
                !view.canFix -> MaterialTheme.colorScheme.errorContainer
                view.dualFrequency -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Text(
            Sky.verdict(view),
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Spacer(Modifier.height(16.dp))
    SkyPlot(view)

    if (view.satellites.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Every satellite it can hear",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "dB-Hz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        view.satellites
            .sortedWith(compareByDescending<com.sigeye.core.analysis.gnss.Satellite> { it.usedInFix }
                .thenByDescending { it.cn0DbHz })
            .forEach { SatelliteBar(it) }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = {
                CsvExport.shareText(
                    context = context,
                    folder = "sky",
                    prefix = "satellites",
                    content = CsvExport.header(
                        "Satellites in view",
                        "visible=${view.visible}",
                        "used=${view.used.size}",
                        "dual_frequency=${view.dualFrequency}",
                    ) + csv(view),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export what is overhead") }
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "Two frequencies, and why your phone might be better than an old one",
        summary = if (view.dualFrequency) {
            "This phone hears the modern second band."
        } else {
            "Nothing on a second band here yet."
        },
        emphasis = view.dualFrequency,
    ) {
        Text(
            "Civilian receivers spent thirty years listening on one frequency each, and one " +
                "frequency cannot tell a signal that came straight down from one that " +
                "bounced off the building opposite. Both arrive; the reflection arrives a " +
                "little later and a little weaker, and to a single-frequency receiver it " +
                "looks like the satellite being further away than it is.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A receiver that hears the same satellite on two widely separated frequencies " +
                "can compare them, because the ionosphere and the reflections do different " +
                "things at different frequencies. That is most of why a recent handset is " +
                "metres better in a city than one from a few years ago, and it is a thing " +
                "you can check rather than a thing you take on trust.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Field("Bands heard", view.bands.joinToString(", ") { it.label }.ifEmpty { "none yet" })
        if (view.onTwoBands.isNotEmpty()) {
            Field("Heard on both", view.onTwoBands.joinToString(", "))
        }
        view.medianCn0?.let {
            Field(
                "Typical strength",
                String.format(Locale.US, "%.0f dB-Hz, %s", it, Sky.describeCn0(it)),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Section(
        title = "Why four and not three",
        summary = "A position needs one more satellite than a position needs.",
    ) {
        Text(
            "Three ranges would fix a point in space, the way three tape measures would. " +
                "They do not, because a range is worked out from how long the signal took, " +
                "and that needs the receiver's clock to agree with the satellite's. The " +
                "satellite has an atomic clock. Your phone has a crystal worth a few pence " +
                "that drifts by microseconds, and a microsecond is three hundred metres of " +
                "light.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "So the receiver solves for four unknowns rather than three: where it is, plus " +
                "how wrong its own clock is. Four satellites, four equations. That is also " +
                "why a phone with three satellites has no position at all rather than a bad " +
                "one, and why the count on this screen is the number that matters rather " +
                "than how many are visible.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Field("Heard", "${view.visible}")
        Field("Used in the fix", "${view.used.size}")
        Field(
            "Spread around the sky",
            String.format(Locale.US, "%.0f%% of the compass", view.spread * 100),
        )
    }

    Spacer(Modifier.height(12.dp))
    Section(
        title = "What this cannot tell you",
        summary = "It reads the receiver. It does not check the receiver.",
    ) {
        Text(
            "Carrier to noise is not the dBm every other experiment here deals in. It is " +
                "power against noise in one hertz of bandwidth, and a satellite at 45 dB-Hz " +
                "is arriving at around -155 dBm - which every other screen in SigEye would " +
                "call silence. The two numbers are not comparable and nothing here tries.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Everything on this screen is what the receiver says it can hear. A phone that " +
                "was being deceived by a spoofed signal would report that signal here " +
                "looking perfectly healthy, because the deception is upstream of anything " +
                "an app can see.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Elevation and azimuth come from the almanac rather than from measurement, so " +
                "they are where the satellite should be. A satellite the receiver has not " +
                "yet placed is left off the plot rather than drawn on the horizon due north.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun csv(view: SkyViewAlias): String = buildString {
    appendLine("constellation,svid,band,cn0_dbhz,elevation_deg,azimuth_deg,used_in_fix,ephemeris")
    view.satellites.forEach { satellite ->
        append(satellite.constellation.label)
        append(',')
        append(satellite.id)
        append(',')
        append(satellite.band.label)
        append(',')
        append(String.format(Locale.US, "%.1f", satellite.cn0DbHz))
        append(',')
        append(satellite.elevationDeg?.let { String.format(Locale.US, "%.1f", it) }.orEmpty())
        append(',')
        append(satellite.azimuthDeg?.let { String.format(Locale.US, "%.1f", it) }.orEmpty())
        append(',')
        append(if (satellite.usedInFix) "1" else "0")
        append(',')
        appendLine(if (satellite.hasEphemeris) "1" else "0")
    }
}

private typealias SkyViewAlias = com.sigeye.core.analysis.gnss.SkyView
