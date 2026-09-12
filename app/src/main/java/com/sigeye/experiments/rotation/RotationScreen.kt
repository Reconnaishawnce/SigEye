package com.sigeye.experiments.rotation

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.SweepExport
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.AdvertShape
import com.sigeye.core.analysis.Chain
import com.sigeye.core.analysis.ChainTracker
import com.sigeye.core.analysis.HuntStage
import com.sigeye.core.analysis.HuntState
import com.sigeye.core.analysis.Identity
import com.sigeye.core.analysis.LinkConfidence
import com.sigeye.core.analysis.Rotation
import com.sigeye.core.analysis.RotationHunt
import com.sigeye.core.ble.AddressType
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.Phy
import com.sigeye.experiments.watchlist.MatchKind
import com.sigeye.experiments.watchlist.WatchStore
import com.sigeye.ui.AlertPicker
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
import kotlinx.coroutines.delay

private const val HUB_TAG = "rotation"
private const val TICK_MS = 1_000L

@Composable
fun RotationScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.ROTATION, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To follow one device through its address changes.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted. Use your own phone - that is the point.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val feedback = remember { Feedback(context) }
    val hunt = remember { RotationHunt() }
    // The room-wide pass runs the whole time, whether or not one device is being
    // followed - it costs nothing extra, since the packets are already arriving.
    val chains = remember { ChainTracker() }

    val notes by book.notes.collectAsStateWithLifecycle()
    val watchRules by remember { WatchStore.get(context) }.rules.collectAsStateWithLifecycle()
    val watched = remember(watchRules) {
        watchRules.filter { it.kind == MatchKind.ADDRESS }
            .map { it.value.uppercase(Locale.US) }
            .toSet()
    }

    var state by remember { mutableStateOf(HuntState()) }
    var candidates by remember { mutableStateOf<List<Identity>>(emptyList()) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BOTH) }
    var announced by remember { mutableStateOf(0) }
    var walkNote by remember { mutableStateOf<String?>(null) }
    var roomChains by remember { mutableStateOf<List<Chain>>(emptyList()) }
    var unlinked by remember { mutableStateOf(0) }
    var fixed by remember { mutableStateOf(0) }
    var showRoom by remember { mutableStateOf(true) }
    var expandedChain by remember { mutableStateOf<Int?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    var seedsHeard by remember { mutableStateOf(0) }

    KeepScreenOn(state.stage != HuntStage.PICK)

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
            val shape = AdvertShape(
                companyId = advert.companyId,
                serviceUuids = advert.serviceUuids,
                appearance = advert.appearance,
                txPower = advert.txPower,
                name = advert.name?.takeIf { it.isNotBlank() },
                manufacturerLength = advert.manufacturerData?.size ?: 0,
                manufacturerPrefix = advert.manufacturerData
                    ?.take(2)
                    ?.joinToString("") { "%02X".format(it) },
                serviceDataKeys = advert.serviceData.keys.toList(),
                isLegacy = advert.isLegacy,
                isConnectable = advert.isConnectable,
                primaryPhy = advert.primaryPhy,
                secondaryPhy = advert.secondaryPhy,
                advertisingSid = advert.advertisingSid,
            )
            hunt.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                shape = shape,
                isRandom = advert.isRandomAddress,
                manufacturerData = advert.manufacturerData,
            )
            chains.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                shape = shape,
                isRandom = advert.isRandomAddress,
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            chains.seed(watched)
            hunt.tick(now)
            chains.tick(now)
            state = hunt.state(now)
            candidates = hunt.candidates(now)
            roomChains = chains.chains { notes[it.uppercase(Locale.US)]?.nickname }
            unlinked = chains.unlinked(now)
            fixed = chains.fixedCount(now)
            seedsHeard = chains.seedsInRange(now)
            if (state.rotations.size > announced) {
                announced = state.rotations.size
                feedback.alert(alertStyle, urgent = true)
            }
        }
    }

    // Back abandons the hunt and returns to the picker rather than leaving.
    BackHandler(enabled = state.stage != HuntStage.PICK) {
        hunt.reset()
        walkNote = null
        state = hunt.state(System.currentTimeMillis())
    }

    when (state.stage) {
        HuntStage.PICK -> Pick(
            candidates = candidates,
            nicknameOf = { notes[it.uppercase(Locale.US)]?.nickname },
            alertStyle = alertStyle,
            onAlertStyle = { alertStyle = it },
            feedback = feedback,
            onPick = {
                hunt.track(it, System.currentTimeMillis())
                announced = 0
                walkNote = null
                state = hunt.state(System.currentTimeMillis())
            },
        )

        HuntStage.LEARN -> Learning(state)

        HuntStage.WATCH -> Watching(
            state = state,
            walking = hunt.walkInProgress,
            dropSoFar = hunt.walkDropSoFar,
            walkNote = walkNote,
            onStartWalk = { hunt.startWalkTest() },
            onFinishWalk = {
                walkNote = hunt.finishWalkTest()?.note
                state = hunt.state(System.currentTimeMillis())
            },
            onRestart = {
                hunt.reset()
                announced = 0
                walkNote = null
                state = hunt.state(System.currentTimeMillis())
            },
        )
    }

    Spacer(Modifier.height(20.dp))
    TheRoom(
        chains = roomChains,
        unlinked = unlinked,
        fixed = fixed,
        seedsHeard = seedsHeard,
        watchedCount = watched.size,
        expanded = expandedChain,
        shown = showRoom,
        exported = exported,
        onToggleShown = { showRoom = !showRoom },
        onExpand = { expandedChain = if (expandedChain == it) null else it },
        onExport = {
            val directory = File(context.getExternalFilesDir(null), "rotations")
            directory.mkdirs()
            val file = File(directory, "chains-${System.currentTimeMillis()}.csv")
            runCatching { file.writeText(chains.csv()) }
            exported = file.name
            SweepExport.share(context, file)
        },
    )
}

/**
 * Every rotation the app thinks it has spotted, without anyone having picked a subject.
 *
 * The focused hunt above can be proved by walking away with the device. This cannot -
 * there is no way to test a claim about a stranger's phone - so it is presented as an
 * argument rather than a finding, and the denominators are shown next to it. Three chains
 * against forty unlinked addresses is the honest picture of most rooms, and hiding that
 * would make the three look far more impressive than they are.
 */
@Composable
private fun TheRoom(
    chains: List<Chain>,
    unlinked: Int,
    fixed: Int,
    seedsHeard: Int,
    watchedCount: Int,
    expanded: Int?,
    shown: Boolean,
    exported: String?,
    onToggleShown: () -> Unit,
    onExpand: (Int) -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggleShown() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Watchlist, followed through rotation",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (shown) "Hide  \u25B4" else "Show  \u25BE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!shown) return

    Spacer(Modifier.height(8.dp))
    Text(
        when {
            watchedCount == 0 -> "Nothing is on your watchlist, so nothing is being " +
                "followed here. Add a device from any screen's actions and it will be " +
                "tracked through its address changes from then on."
            seedsHeard == 0 -> "$watchedCount on your watchlist, none of them audible " +
                "right now."
            chains.isEmpty() -> "Following $seedsHeard of $watchedCount watchlisted " +
                "devices. None has rotated yet - most take about fifteen minutes, and " +
                "many only rotate with the screen off."
            else -> "Following $seedsHeard of $watchedCount watchlisted devices."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    DiagnosticsPanel(
        title = "The rest of the room, for scale",
        diagnostics = listOf(
            Diagnostic("Watched", "$seedsHeard/$watchedCount", "in range now"),
            Diagnostic("Chains", "${chains.size}", "have rotated"),
            Diagnostic("Rotations", "${chains.sumOf { it.rotations }}", "links made"),
            Diagnostic("Others", "$unlinked", "random, not followed"),
            Diagnostic("Fixed", "$fixed", "never rotate"),
            Diagnostic(
                "Strong",
                "${chains.count { it.weakestLink == LinkConfidence.STRONG }}",
                "chains throughout",
            ),
        ),
        footnote = "Only watchlisted devices are followed. Chaining everything in range " +
            "produced a wall of claims about strangers' phones that nobody could check, " +
            "and nothing here can be proved the way the focused hunt above can - you " +
            "cannot walk away with someone else's device.",
    )

    chains.take(12).forEach { chain ->
        Spacer(Modifier.height(8.dp))
        ChainCard(chain, expanded == chain.id) { onExpand(chain.id) }
    }

    if (chains.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("Export the chains")
        }
        exported?.let {
            Text(
                "Wrote $it - every link with the confidence it was made on.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChainCard(chain: Chain, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (chain.weakestLink == LinkConfidence.STRONG) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
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
                        chain.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${chain.addresses.size} addresses, " + chain.describePeriod(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${chain.rotations}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "rotations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                chain.weakestLink.label + " throughout",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                chain.links.forEachIndexed { index, link ->
                    Text(
                        (if (index == 0) "" else "\u2192  ") + link.address +
                            String.format(
                                Locale.US,
                                "   %.0f min",
                                link.durationMs / 60_000.0,
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    link.score?.supporting?.forEach {
                        Text(
                            "      " + it.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    "Tap for the chain and its reasoning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------- stage one

@Composable
private fun Pick(
    candidates: List<Identity>,
    nicknameOf: (String) -> String?,
    alertStyle: AlertStyle,
    onAlertStyle: (AlertStyle) -> Unit,
    feedback: Feedback,
    onPick: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Follow a phone through its disguises",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Phones change their Bluetooth address every quarter of an hour so they " +
                    "cannot be followed. This tries to follow one anyway, and - more to " +
                    "the point - lets you check whether it succeeded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your own phone. Pick it from the list, wait while its fingerprint is " +
                    "learned, then carry it out of the room and watch the signal collapse " +
                    "- that proves the app is looking at the right thing. Wait for the " +
                    "address to change, and it will name the replacement and say why. " +
                    "Then carry it away again. If the new address fades too, the link was " +
                    "real.",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    AlertPicker(
        style = alertStyle,
        onStyle = onAlertStyle,
        feedback = feedback,
        title = "Alert on a rotation",
        note = "Fires when the address changes, so you do not have to watch the screen.",
    )

    Spacer(Modifier.height(12.dp))
    if (candidates.isEmpty()) {
        Text(
            "Listening for something chatty enough to follow...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text("Pick something to follow", style = MaterialTheme.typography.labelLarge)
    Text(
        "Randomised addresses are the interesting ones - a fixed address has nothing to " +
            "defeat. The more distinctive the advertisement, the better the chances.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    candidates.take(20).forEach { candidate ->
        Card(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable { onPick(candidate.address) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        // A randomised address has no vendor of its own, but the company
                        // id in the payload does - and showing "Apple" beats showing hex
                        // when every other screen already says Apple.
                        nicknameOf(candidate.address)
                            ?: candidate.shape.name
                            ?: candidate.shape.companyId?.let { Vendors.byCompanyId(it) }
                            ?: candidate.address,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        candidate.address +
                            if (candidate.isRandom) "  (random)" else "  (fixed)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (candidate.isRandom) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format(Locale.US, "%.0f", candidate.recentRssi),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${candidate.shape.distinctiveness} traits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------- stage two

@Composable
private fun Learning(state: HuntState) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Learning what it looks like",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.learnProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Keep it nearby and still. Measuring how often it advertises and what " +
                    "shape the advertisement is - neither of which changes when the " +
                    "address does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Fingerprint(state)
}

// ------------------------------------------------------------------------ stage three

@Composable
private fun Watching(
    state: HuntState,
    walking: Boolean,
    dropSoFar: Double,
    walkNote: String?,
    onStartWalk: () -> Unit,
    onFinishWalk: () -> Unit,
    onRestart: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Stat("Addresses", "${state.addressesLinked}", "linked so far")
        Stat("Confirmed", "${state.confirmedRotations}", "by walking away")
        Stat("Signal", state.currentRssi?.toString() ?: "-", "dBm now")
    }

    Spacer(Modifier.height(12.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (walking) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (walking) "Walk away now" else "The proof",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (walking) {
                    String.format(
                        Locale.US,
                        "Carry the device out of the room. The signal has fallen %.0f dB " +
                            "so far - press finish once you are well away, or once it has " +
                            "gone silent.",
                        dropSoFar,
                    )
                } else {
                    "Everything else on this screen is inference. This is not: carry the " +
                        "device away and watch whether the address the app is following " +
                        "fades with it. If it does not, the link was wrong, and the app " +
                        "will say so."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (walking) {
                Button(onClick = onFinishWalk, modifier = Modifier.fillMaxWidth()) {
                    Text("I am far away - check")
                }
            } else {
                OutlinedButton(onClick = onStartWalk, modifier = Modifier.fillMaxWidth()) {
                    Text("Start a walk-away test")
                }
            }
            walkNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Fingerprint(state)

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What the hunt is seeing",
        verdict = if (state.rotations.isEmpty()) {
            "No rotation yet. Phones change address every fifteen minutes or so, and some " +
                "do it only when the screen is off - leave it alone and wait."
        } else {
            null
        },
        diagnostics = listOf(
            Diagnostic("Following", state.tracking?.takeLast(8) ?: "-", "address now"),
            Diagnostic("Started at", state.originalAddress?.takeLast(8) ?: "-", "address"),
            Diagnostic("Rotations", "${state.rotations.size}", "claimed"),
            Diagnostic("Confirmed", "${state.confirmedRotations}", "walk tests passed"),
            Diagnostic("Disproved", "${state.disprovedRotations}", "walk tests failed"),
            Diagnostic("Interval", "${state.intervalMs} ms", "between packets"),
        ),
        footnote = "A disproved link is the most useful result this can produce. It means " +
            "the reasoning looked sound and was wrong anyway, which is worth far more " +
            "than a confident claim nobody checked.",
    )

    state.rotations.reversed().forEach { rotation ->
        Spacer(Modifier.height(10.dp))
        RotationCard(rotation)
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
        Text("Follow something else")
    }
}

@Composable
private fun RotationCard(rotation: Rotation) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                rotation.disproved -> MaterialTheme.colorScheme.errorContainer
                rotation.confirmed -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "It rotated",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                rotation.fromAddress + "  →  " + rotation.toAddress,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                rotation.score.confidence.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(8.dp))
            Text("Because", style = MaterialTheme.typography.labelSmall)
            rotation.score.supporting.forEach {
                Text(
                    "✓  " + it.text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            rotation.score.against.forEach {
                Text(
                    "✗  " + it.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            rotation.proof?.let { proof ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (proof.confirmed) "Confirmed by walking away" else "Not confirmed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (proof.confirmed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(proof.note, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ------------------------------------------------------------------------------ parts

/**
 * Everything measured about the subject, one question at a time.
 *
 * This was five headings of numbers shown at once, which is the same as showing none of
 * them - by the fourth heading nobody is reading. Each section now carries the answer to
 * its own question on the collapsed row, and keeps the workings behind a tap. The two that
 * can produce an alarming answer open themselves when they do.
 */
@Composable
private fun Fingerprint(state: HuntState) {
    val traits = state.traits
    val shape = state.shape

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("Interval", "${traits.intervalSlots}", "slots")
                Stat("Traits", "${shape.distinctiveness}", "distinctive")
                Stat("Packets", "${state.packets}", "heard")
            }

            Spacer(Modifier.height(10.dp))

            val staticAddress = traits.addressType == AddressType.RANDOM_STATIC
            Section(
                title = "Address",
                summary = traits.addressType.label,
                emphasis = staticAddress,
                initiallyExpanded = staticAddress,
            ) {
                Text(
                    traits.addressType.rotates,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(
                title = "Timing",
                summary = String.format(
                    Locale.US,
                    "%d ms, %s",
                    traits.intervalMs,
                    traits.intervalStability,
                ),
            ) {
                Field(
                    "Interval",
                    String.format(
                        Locale.US,
                        "%d ms (%d \u00D7 0.625)",
                        traits.intervalMs,
                        traits.intervalSlots,
                    ),
                )
                Field(
                    "Jitter",
                    String.format(Locale.US, "\u00B1%.1f%%", traits.intervalJitter * 100),
                )
                Field("Signal spread", String.format(Locale.US, "%.1f dB", traits.rssiSpread))
                Text(
                    "The interval is a firmware constant, given in the 0.625 ms slots the " +
                        "specification uses - 244 slots is recognisably Apple in a way " +
                        "that 152 ms is not.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Section(
                title = "Link layer",
                summary = listOf(
                    if (traits.isLegacy) "legacy" else "extended",
                    if (traits.isConnectable) "connectable" else "broadcast only",
                    Phy.label(traits.primaryPhy),
                ).joinToString(", "),
            ) {
                Field("Advertising", if (traits.isLegacy) "legacy" else "extended (BT 5)")
                Field("Connectable", if (traits.isConnectable) "yes" else "no")
                Field(
                    "PHY",
                    Phy.label(traits.primaryPhy) +
                        if (traits.secondaryPhy != 0) {
                            " / " + Phy.label(traits.secondaryPhy)
                        } else {
                            ""
                        },
                )
                if (traits.advertisingSid != 0xFF) {
                    Field("Advertising set", traits.advertisingSid.toString())
                }
                Text(
                    "Set by the advertising parameters, so a device cannot change any of " +
                        "these without changing how it advertises.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Section(
                title = "Payload",
                summary = when {
                    traits.payloadLength == 0 -> "nothing carried"
                    traits.payloadLeaksIdentity ->
                        "${traits.payloadStaticBytes} of ${traits.payloadLength} bytes " +
                            "never change"
                    else -> "${traits.payloadLength} bytes, " +
                        "${traits.payloadStaticBytes} unchanging"
                },
                emphasis = traits.payloadLeaksIdentity,
                initiallyExpanded = traits.payloadLeaksIdentity,
            ) {
                shape.name?.let { Field("Name", it) }
                shape.companyId?.let {
                    Field(
                        "Company",
                        String.format(Locale.US, "0x%04X", it) +
                            (Vendors.byCompanyId(it)?.let { name -> "  $name" } ?: ""),
                    )
                }
                if (shape.serviceUuids.isNotEmpty()) {
                    Field("Services", "${shape.serviceUuids.size} advertised")
                }
                shape.txPower?.let { Field("TX power", "$it dBm") }
                if (traits.payloadLength > 0) {
                    Field("Bytes", "${traits.payloadLength}")
                    Text(
                        traits.payloadMask,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    traits.payloadNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (shape.tooPlainToMatchOn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This advertisement carries almost nothing distinctive, so a match on " +
                        "its structure would mean very little. Something with a name and a " +
                        "few services is a far better subject.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
