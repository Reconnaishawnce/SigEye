package com.sigeye.experiments.follow

import androidx.compose.foundation.clickable
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
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.SweepExport
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowDecision
import com.sigeye.core.analysis.identity.FollowPhase
import com.sigeye.core.analysis.identity.FollowSession
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.Following
import com.sigeye.core.analysis.identity.LegKind
import com.sigeye.core.analysis.identity.LiveAddress
import com.sigeye.core.analysis.identity.RotationRhythm
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.shape
import com.sigeye.ui.CountdownBar
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "follow"
private const val TICK_MS = 1_000L

@Composable
fun FollowScreen(
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
        ExperimentHeader(Experiments.FOLLOW, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To hear what is in range and work out which of it is coming with you.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Run this on a device you own, with the agreement of whoever is " +
                "carrying it. It is built to show what is possible, and what is possible " +
                "is following somebody.",
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
    val session = remember { FollowSession() }
    val feedback = remember { Feedback(context) }
    val live = remember { LinkedHashMap<String, LiveAddress>() }

    var state by remember {
        mutableStateOf(FollowState(FollowPhase.CENSUS, emptyList(), emptyList(), 0, null, 0, null, null))
    }
    var legName by remember { mutableStateOf("") }
    var wasLost by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    KeepScreenOn(true)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            feedback.release()
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            session.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = book.nicknameOf(advert.address) ?: advert.name,
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
            )
            // A second, parallel record of the same packets, because re-acquisition needs
            // identities and identities need interval and signal history.
            val key = advert.address.uppercase(Locale.US)
            val entry = live.getOrPut(key) {
                LiveAddress(advert.shape(), advert.isRandomAddress, advert.atMs, advert.atMs)
            }
            if (advert.shape().distinctiveness > entry.shape.distinctiveness) {
                entry.shape = advert.shape()
            }
            entry.observe(advert.rssi, advert.atMs)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val target = state.target

            // While the target is quiet, look for the address it has put on instead. The
            // decision is Following's, refusals and all - this only acts on a yes.
            if (target != null && now - target.lastSeenMs > Following.SILENCE_MS) {
                val previous = live[target.address]?.identity(target.address)
                if (previous != null) {
                    val named = session.state(now).candidates.map { it.address }.toSet()
                    val candidates = live
                        .filterKeys { it != target.address && it !in named }
                        .filterValues { now - it.lastSeenMs <= 15_000 && it.packets >= 8 }
                        .map { (address, entry) -> entry.identity(address) }
                    val decision = Following.decide(previous, candidates, now)
                    if (decision is FollowDecision.Reacquired) {
                        session.reacquire(decision.address, now)
                        log = listOf(
                            "Re-acquired on ${decision.address} · " +
                                "${decision.score.points} points of evidence",
                        ) + log
                        feedback.alert(AlertStyle.BOTH, urgent = true)
                    }
                }
            }

            val next = session.state(now)
            if (wasLost && next.phase == FollowPhase.HOLDING) {
                feedback.alert(AlertStyle.BOTH, urgent = true)
                log = listOf("Back in range") + log
            }
            wasLost = next.phase == FollowPhase.LOST
            state = next
        }
    }

    Headline(state)

    Spacer(Modifier.height(12.dp))
    when (state.phase) {
        FollowPhase.CENSUS, FollowPhase.NARROWING -> Narrowing(
            state = state,
            legName = legName,
            onLegName = { legName = it },
            onBeginLeg = { kind ->
                session.beginLeg(
                    legName.ifBlank { kind.label },
                    kind,
                    System.currentTimeMillis(),
                )
                legName = ""
            },
            onEndLeg = { session.endLeg(System.currentTimeMillis()) },
            onLock = { session.lock(it.address) },
        )

        FollowPhase.HOLDING, FollowPhase.LOST -> Holding(
            state = state,
            log = log,
            onUnlock = { session.unlock() },
            onLocate = { state.target?.let { onLocate(it.address) } },
        )
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "What this is, said plainly",
        summary = "A demonstration of following somebody by their phone.",
        emphasis = true,
    ) {
        Text(
            "This narrows a room down to the device traveling with you, without knowing " +
                "anything about it in advance. It works by elimination: stand together and " +
                "most of what is in range stays in range, which proves nothing. Walk a mile " +
                "together and almost nothing does - the shops fall away, the parked cars " +
                "fall away, the other passengers get off - and what is left is a very short " +
                "list with one particular pocket in it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "It is in the app because the alternative is asserting that this is possible " +
                "and asking to be believed. Run it on a phone you own, carried by somebody " +
                "who knows you are running it. Everything it does, anybody with a phone can " +
                "do, which is the part worth taking away.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The honest output is the short list and its denominator, never a name. Two " +
                "survivors out of two hundred after three miles is a strong claim. Forty " +
                "out of two hundred after standing in a lobby is no claim at all, and the " +
                "screen shows both numbers so you can tell which you have.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = {
            val directory = File(context.getExternalFilesDir(null), "follow")
            directory.mkdirs()
            val file = File(directory, "follow-${System.currentTimeMillis()}.csv")
            runCatching { file.writeText(session.csv()) }
            SweepExport.share(context, file)
        },
        enabled = state.legs.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export the session") }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What this is seeing",
        diagnostics = listOf(
            Diagnostic("In range", "${state.watching}", "addresses heard"),
            Diagnostic("Legs", "${state.legs.size}", "recorded"),
            Diagnostic("Moving legs", "${state.legs.count { it.moving }}", "the useful ones"),
            Diagnostic(
                "Centred",
                if (state.orbited) "${state.centred}" else "no circle",
                "survived the circle",
            ),
            Diagnostic("Survivors", "${state.survivors}", "in every leg"),
            Diagnostic(
                "Target",
                state.target?.let { "${it.rotations} rotations" } ?: "none",
                "followed through",
            ),
            Diagnostic("Silent", "${state.silentForMs / 1000}s", "since last packet"),
        ),
        footnote = "Only a device heard during a leg counts as having survived it, so " +
            "something that dropped out and came back has not. That is what makes the " +
            "elimination work, and it is also why walking through a tunnel will cost you " +
            "the target.",
    )
}

// -------------------------------------------------------------------------- headline

@Composable
private fun Headline(state: FollowState) {
    val container = when (state.phase) {
        FollowPhase.LOST -> MaterialTheme.colorScheme.errorContainer
        FollowPhase.HOLDING -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (state.phase) {
        FollowPhase.LOST -> MaterialTheme.colorScheme.onErrorContainer
        FollowPhase.HOLDING -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when (state.phase) {
                    FollowPhase.CENSUS -> "Listening to everything"
                    FollowPhase.NARROWING -> "${state.survivors} still in the running"
                    FollowPhase.HOLDING -> "Holding " + (state.target?.label
                        ?: state.target?.address ?: "the target")
                    FollowPhase.LOST -> "Lost it"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.narrowing(),
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

// ------------------------------------------------------------------------- narrowing

@Composable
private fun Narrowing(
    state: FollowState,
    legName: String,
    onLegName: (String) -> Unit,
    onBeginLeg: (LegKind) -> Unit,
    onEndLeg: () -> Unit,
    onLock: (FollowCandidate) -> Unit,
) {
    val running = state.legs.lastOrNull()?.running == true

    Text(
        if (state.legs.isEmpty()) {
            "Start a leg. Standing still cuts whatever walks past; walking cuts everything " +
                "that stayed behind, which is nearly everything. Two or three moving legs " +
                "is usually enough."
        } else {
            "Keep going. Each leg cuts what did not come with you."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    if (running) {
        Button(onClick = onEndLeg, modifier = Modifier.fillMaxWidth()) {
            Text("End this leg")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onBeginLeg(LegKind.TOGETHER) }, modifier = Modifier.weight(1f)) {
                Text("Walk together")
            }
            OutlinedButton(
                onClick = { onBeginLeg(LegKind.STILL) },
                modifier = Modifier.weight(1f),
            ) { Text("Stand still") }
        }
        if (!state.orbited) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onBeginLeg(LegKind.ORBIT) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Walk a circle around them") }
            Text(
                "About five paces out, one slow lap, roughly a minute. Anything on the " +
                    "person stays the same distance from you the whole way round. Anything " +
                    "across the room does not.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.legs.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        state.legs.forEach { leg ->
            Field(
                leg.label,
                leg.kind.label.lowercase(Locale.US) + if (leg.running) " · running" else "",
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    val survivors = state.candidates.filter { it.survivedAll }
    if (survivors.isEmpty()) {
        Text(
            "Nothing has survived every leg yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        "Still with you",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    if (state.legs.none { it.moving }) {
        Text(
            "None of these legs involved moving, so this list is everything that was in " +
                "the room - not everything that is following you.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(6.dp))

    survivors.take(12).forEach { candidate ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onLock(candidate) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        candidate.label ?: candidate.vendor ?: candidate.address,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        candidate.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        candidate.describe(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${candidate.meanRssi.roundToInt()} dBm",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- holding

@Composable
private fun Holding(
    state: FollowState,
    log: List<String>,
    onUnlock: () -> Unit,
    onLocate: () -> Unit,
) {
    val target = state.target
    if (target == null) {
        Text("The target is no longer in the list.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
            Text("Back to the list")
        }
        return
    }

    Field("Address now", target.address)
    Field("Survived", target.describe())
    Field(
        "Signal",
        "${target.meanRssi.roundToInt()} dBm average",
    )

    if (state.phase == FollowPhase.LOST) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Out of range for ${state.silentForMs / 1000} seconds",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))

                val due = state.expectedReturnMs
                if (due != null) {
                    val now = System.currentTimeMillis()
                    val period = state.rhythm?.medianPeriodMs
                        ?: RotationRhythm.SPEC_DEFAULT_MS
                    CountdownBar(
                        elapsedMs = (period - (due - now)).coerceIn(0L, period),
                        totalMs = period,
                        label = if (state.rhythm?.measurable == true) {
                            "next address due, on its measured rhythm"
                        } else {
                            "next address due, on the specification default"
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When it changes address it will reappear as a stranger, and this " +
                            "will only take it back if the evidence is unambiguous. If the " +
                            "countdown passes with nothing found, the trail is cold.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Text(
                        "It has not changed address while being watched, so there is no " +
                            "rhythm to predict from. It could put on a new one at any " +
                            "moment, and a countdown to a made-up deadline would be worse " +
                            "than none.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
                    Text("Try to locate it")
                }
            }
        }
    } else {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
            Text("Locate it")
        }
    }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "What it has done",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        log.take(10).forEach {
            Text(
                "· $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onUnlock) { Text("Let it go") }
}
