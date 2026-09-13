package com.sigeye.experiments.follow

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.FollowLead
import com.sigeye.core.FollowLibrary
import com.sigeye.core.Permissions
import com.sigeye.core.SavedFollow
import com.sigeye.core.SweepExport
import com.sigeye.core.Takeaway
import com.sigeye.core.TargetDevice
import com.sigeye.core.TargetStore
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowDecision
import com.sigeye.core.analysis.identity.FollowPhase
import com.sigeye.core.analysis.identity.FollowSession
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.core.analysis.identity.Following
import com.sigeye.core.analysis.identity.LiveAddress
import com.sigeye.core.analysis.identity.Probe
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.shape
import com.sigeye.ui.CountUp
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.LiveBars
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RotationCountdown
import com.sigeye.ui.Section
import com.sigeye.ui.TakeawayButton
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "follow"
private const val TICK_MS = 1_000L

/** A minute of bars, one a second. */
private const val BARS = 60

/** Long enough that a couple of dropped packets is not a loss. */
private const val TARGET_LOST_MS = 60_000L

/** Long enough that the number means something before it can be shown big. */
private const val TAKEAWAY_AFTER_MS = 2 * 60_000L

/**
 * Where a follow has got to on screen.
 *
 * Deliberately few. Following somebody is one continuous thing, and the app's job during it
 * is to stay out of the way: one screen, one falling number, nothing to press while you
 * walk. The circle and the walk-by are the only interruptions, and they are things you
 * chose to do.
 */
private enum class Step {
    /** Past follows, targets, and the way into a new one. */
    LIBRARY,

    /** What this is, and naming it. */
    BRIEF,

    /** The opening census. */
    BASELINE,

    /** The one question that decides what happens next. */
    ASK_HERE,

    /** Baseline taken, target not here yet: watching the door. */
    WAITING,

    /** Following. The screen you are on for most of it. */
    FOLLOWING,

    /** Walking a circle around them. */
    CIRCLE,

    /** Walking past them. */
    WALK_BY,

    /** What the walk-by saw, with the traces to check it against. */
    REVIEW,

    /** One device chosen and held onto. */
    HOLD,

    /** The devices previous follows settled on. */
    TARGETS,
}

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
    val library = remember { FollowLibrary.get(context) }
    val targetStore = remember { TargetStore.get(context) }
    val settings = remember { FollowSettings(context) }
    val feedback = remember { Feedback(context) }
    val live = remember { LinkedHashMap<String, LiveAddress>() }

    var tuning by remember { mutableStateOf(settings.load()) }
    var session by remember { mutableStateOf(FollowSession(tuning)) }
    var state by remember { mutableStateOf(FollowState()) }

    var step by remember { mutableStateOf(Step.LIBRARY) }
    var stepStartedMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var followName by remember { mutableStateOf("") }
    var startedAtMs by remember { mutableStateOf(0L) }
    var theyAreHere by remember { mutableStateOf<Boolean?>(null) }
    var levelMarked by remember { mutableStateOf(false) }
    var dismissedRebaseline by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var wasLost by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val tests = remember { mutableStateListOf<String>() }

    // Two different quantities, both measured here rather than read from somewhere that
    // updates on its own schedule. During the baseline the useful one is how fast the room
    // is filling up; during a follow it is how many are left. The first version of these
    // bars sampled a rate the radio republishes every ten seconds, so thirty identical
    // readings were drawn as a chart - which is exactly as informative as it sounds.
    val bars = remember { mutableStateListOf<Float>() }
    var lastWatched by remember { mutableStateOf(0) }

    val follows by library.follows.collectAsStateWithLifecycle()
    val targets by targetStore.targets.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(state)
    val currentStep by rememberUpdatedState(step)
    val stepStarted by rememberUpdatedState(stepStartedMs)
    val hereAnswer by rememberUpdatedState(theyAreHere)
    val currentTuning by rememberUpdatedState(tuning)

    fun goTo(next: Step) {
        step = next
        stepStartedMs = System.currentTimeMillis()
    }

    fun reset() {
        tuning = settings.load()
        session = FollowSession(tuning)
        live.clear()
        log = emptyList()
        tests.clear()
        bars.clear()
        lastWatched = 0
        theyAreHere = null
        levelMarked = false
        dismissedRebaseline = false
        startedAtMs = 0L
    }

    KeepScreenOn(step != Step.LIBRARY && step != Step.TARGETS)

    BackHandler(enabled = step != Step.LIBRARY) {
        step = if (startedAtMs == 0L) Step.LIBRARY else Step.FOLLOWING
    }

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
            targetStore.heard(key, advert.atMs)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            nowMs = now

            watchForRotations(
                state = latest,
                session = session,
                live = live,
                nowMs = now,
                onMoved = { from, to, message ->
                    log = listOf(message) + log
                    feedback.alert(AlertStyle.BOTH, urgent = true)
                    targetStore.reacquire(from, to, now)
                },
            )

            val next = session.state(now)

            // One bar a second, of whatever the screen is about at the time.
            bars.add(
                if (next.followStartedAtMs != null) {
                    next.stillIn.size.toFloat()
                } else {
                    (next.watching - lastWatched).coerceAtLeast(0).toFloat()
                },
            )
            lastWatched = next.watching
            while (bars.size > BARS) bars.removeAt(0)

            if (currentStep == Step.BASELINE && now - stepStarted >= currentTuning.baselineMs) {
                session.endBaseline(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                when (hereAnswer) {
                    null -> step = Step.ASK_HERE

                    true -> {
                        session.startFollowing(now)
                        bars.clear()
                        step = Step.FOLLOWING
                    }

                    else -> step = Step.WAITING
                }
                stepStartedMs = now
            }

            if (currentStep == Step.CIRCLE && now - stepStarted >= currentTuning.circleMs) {
                session.endProbe(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                step = Step.FOLLOWING
                stepStartedMs = now
            }

            if (wasLost && next.phase == FollowPhase.HOLDING) {
                feedback.alert(AlertStyle.BOTH, urgent = true)
                log = listOf("Back in range") + log
            }
            wasLost = next.phase == FollowPhase.LOST
            state = next
        }
    }

    if (showSettings) {
        FollowSettingsDialog(
            initial = tuning,
            onDismiss = { showSettings = false },
            onSave = {
                settings.save(it)
                tuning = it
                session.tuning = it
                showSettings = false
            },
        )
    }

    when (step) {
        Step.LIBRARY -> Library(
            follows = follows,
            targetCount = targets.size,
            onNew = {
                reset()
                goTo(Step.BRIEF)
            },
            onTargets = { goTo(Step.TARGETS) },
            onSettings = { showSettings = true },
            onForget = { library.delete(it) },
        )

        Step.BRIEF -> Brief(
            name = followName,
            tuning = tuning,
            onName = { followName = it },
            onSettings = { showSettings = true },
            onStart = {
                val now = System.currentTimeMillis()
                startedAtMs = now
                session.startBaseline(now)
                tests += "baseline"
                goTo(Step.BASELINE)
            },
            onCancel = { goTo(Step.LIBRARY) },
        )

        Step.BASELINE -> Baseline(
            state = state,
            bars = bars.toList(),
            elapsedMs = nowMs - stepStartedMs,
            totalMs = tuning.baselineMs,
            theyAreHere = theyAreHere,
            onAnswer = { theyAreHere = it },
        )

        Step.ASK_HERE -> AskHere(
            heard = state.watching,
            onAnswer = { here ->
                theyAreHere = here
                if (here) {
                    session.startFollowing(System.currentTimeMillis())
                    bars.clear()
                    goTo(Step.FOLLOWING)
                } else {
                    goTo(Step.WAITING)
                }
            },
        )

        Step.WAITING -> Waiting(
            state = state,
            onArrived = {
                theyAreHere = true
                session.startFollowing(System.currentTimeMillis())
                bars.clear()
                goTo(Step.FOLLOWING)
            },
        )

        Step.FOLLOWING -> Following(
            state = state,
            bars = bars.toList(),
            targets = targets,
            nowMs = nowMs,
            rebaselinePrompt = state.shouldRebaseline && !dismissedRebaseline,
            onDismissRebaseline = { dismissedRebaseline = true },
            onRebaseline = {
                val now = System.currentTimeMillis()
                session.rebaseline(now)
                bars.clear()
                tests += "re-baseline"
                dismissedRebaseline = false
                goTo(Step.BASELINE)
            },
            onCircle = {
                session.beginProbe(Probe.ORBIT, System.currentTimeMillis())
                tests += "circled them"
                goTo(Step.CIRCLE)
            },
            onWalkBy = {
                levelMarked = false
                session.beginProbe(Probe.WALK_BY, System.currentTimeMillis())
                tests += "walked past them"
                goTo(Step.WALK_BY)
            },
            onReview = { goTo(Step.REVIEW) },
            onHold = {
                session.lock(it.address)
                goTo(Step.HOLD)
            },
            onPromote = { candidate -> targetStore.add(candidate.asTarget(followName)) },
            onTargets = { goTo(Step.TARGETS) },
            onSettings = { showSettings = true },
            onFinish = {
                library.save(state.asSavedFollow(startedAtMs, followName, tests.toList()))
                goTo(Step.LIBRARY)
            },
        )

        Step.CIRCLE -> Timed(
            elapsedMs = nowMs - stepStartedMs,
            totalMs = tuning.circleMs,
            label = "Circling",
            caption = "${state.centred} of ${state.stillIn.size} at the same distance",
            instruction = "Keep walking. One slow lap, about five paces out, phone in your " +
                "hand in front of you. Do not double back.",
        )

        Step.WALK_BY -> WalkByStep(
            state = state,
            elapsedMs = nowMs - stepStartedMs,
            marked = levelMarked,
            onLevel = {
                session.markClosest(System.currentTimeMillis())
                levelMarked = true
                feedback.alert(AlertStyle.BOTH, urgent = false)
            },
            onDone = {
                session.endProbe(System.currentTimeMillis())
                goTo(if (levelMarked) Step.REVIEW else Step.FOLLOWING)
            },
        )

        Step.REVIEW -> Review(
            state = state,
            targets = targets,
            onPromote = { candidate -> targetStore.add(candidate.asTarget(followName)) },
            onHold = {
                session.lock(it.address)
                goTo(Step.HOLD)
            },
            onBack = { goTo(Step.FOLLOWING) },
        )

        Step.HOLD -> Holding(
            state = state,
            log = log,
            onUnlock = {
                session.unlock()
                goTo(Step.FOLLOWING)
            },
            onLocate = { state.target?.let { onLocate(it.address) } },
        )

        Step.TARGETS -> Targets(
            targets = targets,
            nowMs = nowMs,
            onLocate = onLocate,
            onForget = { targetStore.remove(it) },
            onBack = { goTo(if (startedAtMs == 0L) Step.LIBRARY else Step.FOLLOWING) },
        )
    }

    if (step != Step.LIBRARY && step != Step.TARGETS) {
        Spacer(Modifier.height(16.dp))
        Explainer()

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val directory = File(context.getExternalFilesDir(null), "follow")
                directory.mkdirs()
                val file = File(directory, "follow-${System.currentTimeMillis()}.csv")
                runCatching { file.writeText(session.csv()) }
                SweepExport.share(context, file)
            },
            enabled = state.watching > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export the session") }

        Spacer(Modifier.height(12.dp))
        DiagnosticsPanel(
            title = "What this is seeing",
            diagnostics = listOf(
                Diagnostic("Heard", "${state.watching}", "addresses, ever"),
                Diagnostic("In the pool", "${state.poolSize}", "when the follow started"),
                Diagnostic("Still with them", "${state.stillIn.size}", "have not dropped"),
                Diagnostic("Dropped", "${state.dropped.size}", "went quiet"),
                Diagnostic("Came back", "${state.returned.size}", "after dropping"),
                Diagnostic("Arrived", "${state.arrivals.size}", "after the baseline"),
                Diagnostic(
                    "Circle",
                    if (state.orbited) "${state.centred}" else "not walked",
                    "stayed at range",
                ),
                Diagnostic(
                    "Walk-by",
                    if (state.walkedBy) "${state.passed}" else "not walked",
                    "peaked as you passed",
                ),
                Diagnostic("Drop-off", "${tuning.dropAfterMs / 1000}s", "of silence"),
            ),
            footnote = "A device drops out when it has not been heard for the drop-off, and " +
                "it stays out. Something that went quiet for a minute while you covered a " +
                "quarter of a mile did not come with you, and letting it back in when it " +
                "reappears would undo the only claim this makes.",
        )
    }
}

/**
 * Keeps the short list attached to its devices across address changes.
 *
 * Watched for every candidate on the short list rather than only for a locked target. Five
 * devices being watched is five chances to keep the trail; watching only the one already
 * committed to means the rotation that loses you the target is the one nobody was looking
 * at.
 *
 * The decision stays [Following]'s, refusals and all.
 */
private fun watchForRotations(
    state: FollowState,
    session: FollowSession,
    live: Map<String, LiveAddress>,
    nowMs: Long,
    onMoved: (from: String, to: String, message: String) -> Unit,
) {
    val watching = (state.shortlist + listOfNotNull(state.target)).distinctBy { it.address }
    if (watching.isEmpty()) return

    val known = state.candidates.map { it.address }.toSet()
    watching.forEach { candidate ->
        if (nowMs - candidate.lastSeenMs <= Following.SILENCE_MS) return@forEach
        val previous = live[candidate.address]?.identity(candidate.address) ?: return@forEach
        val successors = live
            .filterKeys { it != candidate.address && it !in known }
            .filterValues { nowMs - it.lastSeenMs <= 15_000 && it.packets >= 8 }
            .map { (address, entry) -> entry.identity(address) }

        val decision = Following.decide(previous, successors, nowMs)
        if (decision is FollowDecision.Reacquired) {
            if (session.reacquire(candidate.address, decision.address, nowMs)) {
                onMoved(
                    candidate.address,
                    decision.address,
                    "${candidate.label ?: candidate.address} rotated to ${decision.address}, " +
                        "${decision.score.points} points of evidence",
                )
            }
        }
    }
}

private fun FollowCandidate.asTarget(followName: String) = TargetDevice(
    address = address,
    label = label,
    vendor = vendor,
    evidence = describe(),
    fromFollow = followName.ifBlank { "Unnamed follow" },
    addedAtMs = System.currentTimeMillis(),
    addresses = addresses,
)

private fun FollowState.asSavedFollow(
    startedAtMs: Long,
    name: String,
    tests: List<String>,
) = SavedFollow(
    id = startedAtMs.toString(),
    name = name.ifBlank { "Unnamed follow" },
    startedAtMs = startedAtMs,
    endedAtMs = atMs,
    watched = watching,
    leads = stillIn.take(tuning.listableAt).map {
        FollowLead(it.address, it.label, it.vendor, it.describe())
    },
    tests = tests,
)

// -------------------------------------------------------------------------- the steps

@Composable
private fun Library(
    follows: List<SavedFollow>,
    targetCount: Int,
    onNew: () -> Unit,
    onTargets: () -> Unit,
    onSettings: () -> Unit,
    onForget: (String) -> Unit,
) {
    Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Start a new follow") }

    if (targetCount > 0) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTargets, modifier = Modifier.fillMaxWidth()) {
            Text("Targets ($targetCount)")
        }
    }
    Spacer(Modifier.height(4.dp))
    Grey("How this follow behaves", onSettings)

    Spacer(Modifier.height(12.dp))
    Text(
        "Past follows",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))

    if (follows.isEmpty()) {
        Text(
            "None yet. What gets kept is the conclusion and the conditions - how many were " +
                "in range when you started, what it came down to, which tests you ran - " +
                "rather than the packets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    follows.forEach { follow ->
        Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(follow.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        follow.summary(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    follow.stamp() + " · " + (follow.durationMs / 60_000L) + " min · " +
                        follow.tests.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                follow.leads.take(5).forEach { lead ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (lead.label ?: lead.vendor ?: lead.address) + " · " + lead.evidence,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Forget this one",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClickLabel = "Forget ${follow.name}") {
                            onForget(follow.id)
                        }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Brief(
    name: String,
    tuning: FollowTuning,
    onName: (String) -> Unit,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        "A new follow",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "First a baseline: ${tuning.baselineMs / 1000} seconds of listening to everything " +
            "audible from where you are standing. That is the denominator, and it is also " +
            "the pool - whatever is in range when you start following is what can still be " +
            "with you later.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Then you walk, and the list shrinks on its own. Anything that goes unheard for " +
            "${tuning.dropAfterMs / 1000} seconds drops out and stays out. No buttons, " +
            "nothing to press - the point is to be following somebody, not operating a " +
            "phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Run it on a phone you own, carried by somebody who knows you are running it.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
    )

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Name this follow") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(10.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Take a baseline") }
    Spacer(Modifier.height(4.dp))
    Grey("Change how it behaves", onSettings)
    Grey("Not now", onCancel)
}

@Composable
private fun Baseline(
    state: FollowState,
    bars: List<Float>,
    elapsedMs: Long,
    totalMs: Long,
    theyAreHere: Boolean?,
    onAnswer: (Boolean) -> Unit,
) {
    Text(
        "ESTABLISHING A BASELINE",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(10.dp))

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = state.watching, fontSize = 72.sp)
        Text(
            "devices audible from here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        CountdownRing(
            elapsedMs = elapsedMs.coerceIn(0L, totalMs),
            totalMs = totalMs,
            label = "baseline",
            caption = "stand still",
        )
    }

    Spacer(Modifier.height(14.dp))
    LiveBars(
        values = bars,
        spoken = "New devices found each second. " +
            "${bars.sumOf { it.toInt() }} across the last ${bars.size} seconds.",
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Each bar is how many devices were heard for the first time in that second. It " +
            "starts tall and flattens out, and when it has flattened the census is done.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Text(
        "Is the person you are following in the room right now?",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Answer while this runs. If they are here, the follow starts the moment the " +
            "baseline ends and everything audible is in the pool. If they are not, the app " +
            "waits and watches the door - whoever walks in is a far shorter list than the " +
            "building.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("They are here", theyAreHere == true, Modifier.weight(1f)) { onAnswer(true) }
        Choice("Not yet", theyAreHere == false, Modifier.weight(1f)) { onAnswer(false) }
    }
}

@Composable
private fun AskHere(heard: Int, onAnswer: (Boolean) -> Unit) {
    Text(
        "$heard devices in range from here",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "That is the denominator. Every claim from here on is some number out of this one, " +
            "and a claim with a small denominator is not much of a claim.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Text(
        "Is the person you are following here?",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
            Text("They are here")
        }
        OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f)) {
            Text("Not yet")
        }
    }
}

@Composable
private fun Waiting(state: FollowState, onArrived: () -> Unit) {
    Text(
        "Watching the door",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "The baseline is everything that was here before them. Anything heard from now on " +
            "walked in, which is a far shorter list than the building - so the moment they " +
            "arrive, whatever is on them is already on it.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = state.arrivals.size, fontSize = 72.sp)
        Text(
            "have arrived since the baseline",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "out of ${state.watching} heard in total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.arrivals.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        state.arrivals.take(8).forEach { CandidateCard(it, state.atMs, onClick = null) }
    }

    Spacer(Modifier.height(12.dp))
    Button(onClick = onArrived, modifier = Modifier.fillMaxWidth()) {
        Text("They are here now - start following")
    }
}

/**
 * The screen you are on for most of a follow.
 *
 * One number, and it only falls. Everything else here is either something you might choose
 * to do or a record of what has already gone, and none of it needs touching while you walk.
 */
@Composable
private fun Following(
    state: FollowState,
    bars: List<Float>,
    targets: List<TargetDevice>,
    nowMs: Long,
    rebaselinePrompt: Boolean,
    onDismissRebaseline: () -> Unit,
    onRebaseline: () -> Unit,
    onCircle: () -> Unit,
    onWalkBy: () -> Unit,
    onReview: () -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onPromote: (FollowCandidate) -> Unit,
    onTargets: () -> Unit,
    onSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.narrowed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CountUp(value = state.stillIn.size, fontSize = 96.sp)
            Text(
                "still with them, of ${state.poolSize} when you started",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${state.watching} heard in total · ${state.runningForMs / 60_000} min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.narrowed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Short list. Every one of these is being watched for an address change.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    LiveBars(
        values = bars,
        spoken = "How many are still with them, once a second. Now " +
            "${bars.lastOrNull()?.toInt() ?: 0}, highest ${bars.maxOrNull()?.toInt() ?: 0} " +
            "over the last ${bars.size} seconds.",
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "One bar a second. This is the list emptying out as you walk.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (rebaselinePrompt) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "This is not narrowing. Start the baseline again?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.runningForMs / 60_000} minutes in and still " +
                        "${state.stillIn.size} with them. Almost always one thing: their " +
                        "phone changed address partway through, so the device you were " +
                        "converging on stopped existing and its replacement was never in " +
                        "the pool. Starting again reopens the pool to whatever is audible " +
                        "now, without forgetting the room.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onRebaseline, modifier = Modifier.weight(1f)) {
                        Text("Re-baseline")
                    }
                    OutlinedButton(
                        onClick = onDismissRebaseline,
                        modifier = Modifier.weight(1f),
                    ) { Text("Carry on") }
                }
            }
        }
    }

    if (state.listable) {
        Spacer(Modifier.height(16.dp))
        Text(
            if (state.narrowed) "Short list" else "Still with them",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (state.narrowed) {
                "Tap one to hold onto it, or add it to your targets to use it elsewhere."
            } else {
                "Short enough to look down. Add anything worth keeping to your targets."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        state.stillIn.forEach { candidate ->
            val already = targets.any { it.address.equals(candidate.address, true) }
            CandidateCard(
                candidate = candidate,
                nowMs = nowMs,
                onClick = { onHold(candidate) },
                action = if (already) null else "Add to targets",
                onAction = { onPromote(candidate) },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Anything you can do without giving yourself away",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    ProbeCard(
        title = "Walk past them",
        detail = if (state.walkedBy) {
            "Done. ${state.passed} of the ones still with them peaked as you passed. Tap " +
                "to look at the traces."
        } else {
            "They stand still; you walk past and stop the same distance away on the far " +
                "side. Whatever is on them rises as you draw level and comes back down. " +
                "This one picks devices out rather than ruling them out."
        },
        onClick = if (state.walkedBy) onReview else onWalkBy,
        emphasis = state.walkedBy,
    )
    ProbeCard(
        title = "Circle them",
        detail = if (state.orbited) {
            "Done. ${state.centred} stayed at the same distance all the way round."
        } else {
            "One slow lap about five paces out. Anything on them stays the same distance " +
                "from you the whole way round; anything across the room does not."
        },
        onClick = onCircle,
        enabled = !state.orbited,
    )

    if (state.dropped.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Section(
            title = "Dropped out",
            summary = "${state.dropped.size} gone, newest first." +
                if (state.returned.isEmpty()) "" else " ${state.returned.size} came back.",
        ) {
            Text(
                "A device is out when it has not been heard for " +
                    "${state.tuning.dropAfterMs / 1000} seconds, and it stays out. Coming " +
                    "back is recorded rather than undone - usually it means you walked a " +
                    "loop past the same fixed thing twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.dropped.take(12).forEach { candidate ->
                Field(
                    candidate.label ?: candidate.vendor ?: candidate.address,
                    "lasted ${candidate.heldForMs(nowMs) / 1000}s" +
                        if (candidate.returnedAtMs != null) " · came back" else "",
                )
            }
        }
    }

    TakeawayButton(takeawayFrom(state))

    Spacer(Modifier.height(10.dp))
    if (targets.isNotEmpty()) {
        OutlinedButton(onClick = onTargets, modifier = Modifier.fillMaxWidth()) {
            Text("Targets (${targets.size})")
        }
        Spacer(Modifier.height(6.dp))
    }
    Grey("How this follow behaves", onSettings)
    Grey("Finish and save this follow", onFinish)
}

@Composable
private fun WalkByStep(
    state: FollowState,
    elapsedMs: Long,
    marked: Boolean,
    onLevel: () -> Unit,
    onDone: () -> Unit,
) {
    Text(
        "Walking past them",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (marked) {
            "Keep going at the same pace, and stop about as far past them as you started " +
                "before them. The two ends have to be the same distance, or the test cannot " +
                "tell a device beside your path from one along it."
        } else {
            "Walk at a steady pace. Tap the moment you draw level with them - you know when " +
                "that is and no sensor on this phone does."
        },
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${elapsedMs / 1000} s",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${state.stillIn.size} still with them",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    if (marked) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Stop here, I am past them")
        }
    } else {
        Button(onClick = onLevel, modifier = Modifier.fillMaxWidth()) {
            Text("I am level with them now")
        }
        Spacer(Modifier.height(6.dp))
        Grey("Give up on this one", onDone)
    }
}

/**
 * What the walk-by saw, with the traces it saw it in.
 *
 * Ranked, best first, and every one drawn. A verdict you cannot check is an assertion, and
 * the whole reason for showing the shape is that a person can tell a clean hill from a mess
 * the thresholds happened to let through in about a second - which is faster and more
 * reliable than any amount of tuning.
 */
@Composable
private fun Review(
    state: FollowState,
    targets: List<TargetDevice>,
    onPromote: (FollowCandidate) -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onBack: () -> Unit,
) {
    val walk = state.probes.firstOrNull { it.kind == Probe.WALK_BY }
    val scored = state.candidates
        .filter { it.walkBy != null && it.walkByTrail.size >= 2 }
        .sortedWith(
            compareByDescending<FollowCandidate> { it.walkBy!!.passed }
                .thenByDescending { it.walkBy!!.riseDb },
        )

    Text(
        "What the walk-by saw",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))

    val mid = walk?.midAtMs
    val end = walk?.endedAtMs
    if (mid == null || end == null || scored.isEmpty()) {
        Text(
            "Nothing to show. A walk-by needs a start, a tap when you drew level, and an " +
                "end the same distance the other side - without the middle mark there is no " +
                "peak to test against.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Grey("Back", onBack)
        return
    }

    val passed = scored.count { it.walkBy!!.passed }
    Text(
        if (passed == 0) {
            "None of ${scored.size} passed. Look at the shapes anyway - if one is a hill " +
                "the thresholds just missed, that is worth knowing, and the thresholds are " +
                "settings."
        } else {
            "$passed of ${scored.size} rose as you drew level and came back down. The best " +
                "is first."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "The vertical line is where you tapped. The dashed line is the level the rise is " +
            "measured against, and the shaded ends are the two windows it came from.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    scored.take(8).forEach { candidate ->
        val score = candidate.walkBy!!
        val already = targets.any { it.address.equals(candidate.address, true) }
        Card(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (score.passed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
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
                            candidate.label ?: candidate.vendor ?: candidate.address,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            candidate.address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        if (score.passed) "passed" else "no",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(8.dp))
                WalkByChart(
                    trail = candidate.walkByTrail,
                    score = score,
                    startMs = walk.startedAtMs,
                    endMs = end,
                )

                Spacer(Modifier.height(6.dp))
                Field("Rise as you passed", "${score.riseDb.roundToInt()} dB")
                Field("Ends differ by", "${score.symmetryDb.roundToInt()} dB")
                Field("Peak off the mark by", "${score.offsetMs / 1000} s")
                Field("Readings", "${score.packets}")
                Spacer(Modifier.height(4.dp))
                Text(score.describe(), style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!already) {
                        Button(
                            onClick = { onPromote(candidate) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Add to targets") }
                    }
                    OutlinedButton(
                        onClick = { onHold(candidate) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Hold this one") }
                }
            }
        }
    }

    Grey("Back to the follow", onBack)
}

@Composable
private fun Targets(
    targets: List<TargetDevice>,
    nowMs: Long,
    onLocate: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
) {
    Text("Targets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Devices a follow settled on, with the evidence it settled on them for. Each one is " +
            "also on a device list called \"${TargetStore.LIST}\", which means every other " +
            "experiment here can already see it: Signal Watch will alert when it comes back " +
            "into range, Persistent Tracking will follow it across address changes, and " +
            "Device Inspector will decode whatever it is broadcasting.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    if (targets.isEmpty()) {
        Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium)
    }

    targets.forEach { target ->
        val silentFor = nowMs - target.lastSeenMs
        val lost = silentFor > TARGET_LOST_MS
        Card(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (lost) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    target.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Field("From", target.fromFollow + " · " + target.added())
                Field("Evidence", target.evidence)
                if (target.rotations > 0) {
                    Field("Followed through", "${target.rotations} address changes")
                }
                Field(
                    "Heard",
                    if (lost) "not for ${silentFor / 1000} s" else "${silentFor / 1000} s ago",
                )

                if (lost) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "HUNT MODE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Lost. Far more often than not that means it is still here wearing " +
                            "a name nothing recognises, rather than gone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    RotationCountdown(changesAtMs = target.changesAtMs, nowMs = nowMs)
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onLocate(target.address) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (lost) "Hunt for it" else "Locate it") }
                    OutlinedButton(
                        onClick = { onForget(target.address) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Forget") }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Grey("Back", onBack)
}

/** A step the clock ends rather than a tap: the ring is the whole instruction. */
@Composable
private fun Timed(
    elapsedMs: Long,
    totalMs: Long,
    label: String,
    caption: String,
    instruction: String,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            elapsedMs = elapsedMs.coerceIn(0L, totalMs),
            totalMs = totalMs,
            label = label,
            caption = caption,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(instruction, style = MaterialTheme.typography.bodySmall)
}

// ------------------------------------------------------------------------ small parts

@Composable
private fun ProbeCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasis: Boolean = false,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = title, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                emphasis -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/** A way onward, in grey, for the thing that is not the main action. */
@Composable
private fun Grey(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClickLabel = label, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CandidateCard(
    candidate: FollowCandidate,
    nowMs: Long,
    onClick: (() -> Unit)?,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${candidate.meanRssi.roundToInt()} dBm",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "${candidate.heldForMs(nowMs) / 60_000} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            action?.let {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onAction) { Text(it) }
            }
        }
    }
}

@Composable
private fun Explainer() {
    Section(
        title = "What this is, said plainly",
        summary = "A demonstration of following somebody by their phone.",
        emphasis = true,
    ) {
        Text(
            "This narrows a street down to the device travelling with somebody, without " +
                "knowing anything about it in advance. It works by elimination: stand " +
                "together and most of what is in range stays in range, which proves " +
                "nothing. Walk half a mile together and almost nothing does - the shops " +
                "fall away, the parked cars fall away, the other passengers get off.",
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
            "The honest output is the short list and its denominator, never a name. Two out " +
                "of two hundred after three miles is a strong claim. Forty out of two " +
                "hundred after standing in a lobby is no claim at all, and the screen shows " +
                "both numbers so you can tell which you have.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The short list as something that can be shown big, or null when it is not worth showing.
 *
 * Refused until the follow has actually run for a while, because a number taken thirty
 * seconds in is "everything in the room" dressed up as "everything following them" - and a
 * card is the one place that mistake travels furthest.
 */
private fun takeawayFrom(state: FollowState): Takeaway? {
    if (state.followStartedAtMs == null || state.poolSize == 0) return null
    if (state.runningForMs < TAKEAWAY_AFTER_MS) return null

    return Takeaway(
        experiment = "Follow Me",
        headline = "${state.stillIn.size}",
        unit = if (state.stillIn.size == 1) {
            "device stayed with them"
        } else {
            "devices stayed with them"
        },
        denominator = "out of ${state.poolSize} that were in range when it started",
        context = listOfNotNull(
            "${state.runningForMs / 60_000} minutes of following",
            "${state.watching} heard along the way",
            if (state.orbited) "${state.centred} survived the circle" else null,
            if (state.walkedBy) "${state.passed} peaked as you passed" else null,
        ),
        limit = "Co-presence is not identity. These devices were in range whenever you " +
            "were - nothing here says any of them is a phone, or whose, and none of them " +
            "was connected to.",
    )
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
    Field("Evidence", target.describe())
    Field("Signal", "${target.meanRssi.roundToInt()} dBm average")
    Field("With you for", "${target.heldForMs(state.atMs) / 60_000} min")

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
                RotationCountdown(changesAtMs = state.rotationChangesAtMs, nowMs = state.atMs)
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
    Grey("Let it go", onUnlock)
}
