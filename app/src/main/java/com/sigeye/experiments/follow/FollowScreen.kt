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
import com.sigeye.core.analysis.identity.Following
import com.sigeye.core.analysis.identity.LegKind
import com.sigeye.core.analysis.identity.LiveAddress
import com.sigeye.core.analysis.identity.RotationRhythm
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.shape
import com.sigeye.ui.CountUp
import com.sigeye.ui.CountdownBar
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.LiveBars
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.TakeawayButton
import com.sigeye.ui.barsSpoken
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "follow"
private const val TICK_MS = 1_000L

/** Long enough for a device advertising every couple of seconds to be heard repeatedly. */
private const val BASELINE_MS = 35_000L

/** A comfortable lap at five paces out, walked slowly enough not to blur the level. */
private const val CIRCLE_MS = 75_000L

/** Half a minute of bars, one a second. */
private const val BARS = 35

/** Long enough that a couple of dropped packets is not a loss. */
private const val LOST_AFTER_MS = 60_000L

/**
 * Where a follow has got to.
 *
 * A hub rather than a wizard. The first version of this was a straight line - census,
 * circle, walk, list - and a straight line is wrong here, because after the baseline the
 * person holding the phone is the one who can see what is possible. Sometimes the target is
 * sitting still and can be walked past. Sometimes they are about to leave and the only test
 * available is going with them. So the baseline leads to a hub, every test returns to it,
 * and the hub says what has been ruled out so far.
 */
private enum class Step {
    /** Past follows, targets, and the way into a new one. */
    LIBRARY,

    /** What this is, and what it needs from you. */
    BRIEF,

    /** The opening census. */
    BASELINE,

    /** The one question that decides everything after it. */
    ASK_HERE,

    /** Baseline taken, target not here yet: watching the door. */
    WAITING,

    /** The plan. Everything comes back here. */
    HUB,

    /** Walking a circle around them. */
    CIRCLE,

    /** Walking past them. */
    WALK_BY,

    /** Walking with them. */
    MOBILE,

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
    val feedback = remember { Feedback(context) }
    val live = remember { LinkedHashMap<String, LiveAddress>() }
    val packets = remember { AtomicInteger(0) }

    // Replaceable, because a follow that has to start again genuinely starts again.
    var session by remember { mutableStateOf(FollowSession()) }

    var state by remember {
        mutableStateOf(
            FollowState(FollowPhase.CENSUS, emptyList(), emptyList(), 0, null, 0, null, null),
        )
    }
    var step by remember { mutableStateOf(Step.LIBRARY) }
    var stepStartedMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var followName by remember { mutableStateOf("") }
    var startedAtMs by remember { mutableStateOf(0L) }
    var theyAreHere by remember { mutableStateOf<Boolean?>(null) }
    var levelMarked by remember { mutableStateOf(false) }
    var dismissedRebaseline by remember { mutableStateOf(false) }
    var wasLost by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val tests = remember { mutableStateListOf<String>() }
    val rates = remember { mutableStateListOf<Float>() }

    val follows by library.follows.collectAsStateWithLifecycle()
    val targets by targetStore.targets.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(state)
    val currentStep by rememberUpdatedState(step)
    val stepStarted by rememberUpdatedState(stepStartedMs)
    val hereAnswer by rememberUpdatedState(theyAreHere)

    fun goTo(next: Step) {
        step = next
        stepStartedMs = System.currentTimeMillis()
    }

    fun reset() {
        session = FollowSession()
        live.clear()
        log = emptyList()
        tests.clear()
        rates.clear()
        theyAreHere = null
        levelMarked = false
        dismissedRebaseline = false
        startedAtMs = 0L
    }

    KeepScreenOn(step != Step.LIBRARY && step != Step.TARGETS)

    BackHandler(enabled = step != Step.LIBRARY) {
        step = if (step == Step.TARGETS && startedAtMs == 0L) Step.LIBRARY else Step.HUB
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
            packets.incrementAndGet()
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
            rates.add(packets.getAndSet(0).toFloat())
            while (rates.size > BARS) rates.removeAt(0)

            watchForRotations(
                state = latest,
                session = session,
                live = live,
                nowMs = now,
                onMoved = { message ->
                    log = listOf(message) + log
                    feedback.alert(AlertStyle.BOTH, urgent = true)
                },
            )

            // The two timed steps end themselves and buzz when they do, because somebody
            // walking a circle round their friend is not looking at the phone.
            if (currentStep == Step.BASELINE && now - stepStarted >= BASELINE_MS) {
                session.endBaseline(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                step = when (hereAnswer) {
                    null -> Step.ASK_HERE
                    true -> Step.HUB
                    else -> Step.WAITING
                }
                stepStartedMs = now
            }

            if (currentStep == Step.CIRCLE && now - stepStarted >= CIRCLE_MS) {
                session.endLeg(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                step = Step.HUB
                stepStartedMs = now
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

    when (step) {
        Step.LIBRARY -> Library(
            follows = follows,
            targetCount = targets.size,
            onNew = {
                reset()
                goTo(Step.BRIEF)
            },
            onTargets = { goTo(Step.TARGETS) },
            onForget = { library.delete(it) },
        )

        Step.BRIEF -> Brief(
            name = followName,
            onName = { followName = it },
            onStart = {
                val now = System.currentTimeMillis()
                startedAtMs = now
                session.beginLeg("Baseline", LegKind.BASELINE, now)
                tests += "baseline"
                goTo(Step.BASELINE)
            },
            onCancel = { goTo(Step.LIBRARY) },
        )

        Step.BASELINE -> Baseline(
            state = state,
            rates = rates.toList(),
            elapsedMs = nowMs - stepStartedMs,
            theyAreHere = theyAreHere,
            onAnswer = { theyAreHere = it },
        )

        Step.ASK_HERE -> AskHere(
            heard = state.watching,
            onAnswer = {
                theyAreHere = it
                goTo(if (it) Step.HUB else Step.WAITING)
            },
        )

        Step.WAITING -> Waiting(
            state = state,
            onArrived = {
                theyAreHere = true
                goTo(Step.HUB)
            },
        )

        Step.HUB -> Hub(
            state = state,
            targets = targets,
            nowMs = nowMs,
            rebaselinePrompt = state.shouldRebaseline && !dismissedRebaseline,
            onDismissRebaseline = { dismissedRebaseline = true },
            onRebaseline = {
                val now = System.currentTimeMillis()
                session.rebaseline(now)
                session.beginLeg("Baseline", LegKind.BASELINE, now)
                tests += "re-baseline"
                dismissedRebaseline = false
                goTo(Step.BASELINE)
            },
            onStandStill = {
                session.beginLeg(LegKind.STILL.label, LegKind.STILL, System.currentTimeMillis())
                tests += "stood still"
            },
            onCircle = {
                session.beginLeg(LegKind.ORBIT.label, LegKind.ORBIT, System.currentTimeMillis())
                tests += "circled them"
                goTo(Step.CIRCLE)
            },
            onWalkBy = {
                levelMarked = false
                session.beginLeg(
                    LegKind.WALK_BY.label,
                    LegKind.WALK_BY,
                    System.currentTimeMillis(),
                )
                tests += "walked past them"
                goTo(Step.WALK_BY)
            },
            onMobile = {
                session.beginLeg(
                    LegKind.TOGETHER.label,
                    LegKind.TOGETHER,
                    System.currentTimeMillis(),
                )
                tests += "went mobile"
                goTo(Step.MOBILE)
            },
            onEndLeg = { session.endLeg(System.currentTimeMillis()) },
            onHold = {
                session.lock(it.address)
                goTo(Step.HOLD)
            },
            onPromote = { candidate ->
                targetStore.add(
                    TargetDevice(
                        address = candidate.address,
                        label = candidate.label,
                        vendor = candidate.vendor,
                        evidence = candidate.describe(),
                        fromFollow = followName.ifBlank { "Unnamed follow" },
                        addedAtMs = System.currentTimeMillis(),
                        addresses = candidate.addresses,
                    ),
                )
            },
            onTargets = { goTo(Step.TARGETS) },
            onFinish = {
                val now = System.currentTimeMillis()
                library.save(
                    SavedFollow(
                        id = startedAtMs.toString(),
                        name = followName.ifBlank { "Unnamed follow" },
                        startedAtMs = startedAtMs,
                        endedAtMs = now,
                        watched = state.watching,
                        leads = state.stillIn.take(FollowSession.SHORTLIST_MAX).map {
                            FollowLead(it.address, it.label, it.vendor, it.describe())
                        },
                        tests = tests.toList(),
                    ),
                )
                goTo(Step.LIBRARY)
            },
        )

        Step.CIRCLE -> Timed(
            elapsedMs = nowMs - stepStartedMs,
            totalMs = CIRCLE_MS,
            label = "Circling",
            caption = "${state.centred} of ${state.watching} at the same distance",
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
                session.endLeg(System.currentTimeMillis())
                goTo(Step.HUB)
            },
        )

        Step.MOBILE -> Mobile(
            state = state,
            nowMs = nowMs,
            onStop = {
                session.endLeg(System.currentTimeMillis())
                goTo(Step.HUB)
            },
        )

        Step.HOLD -> Holding(
            state = state,
            log = log,
            onUnlock = {
                session.unlock()
                goTo(Step.HUB)
            },
            onLocate = { state.target?.let { onLocate(it.address) } },
        )

        Step.TARGETS -> Targets(
            targets = targets,
            nowMs = nowMs,
            onLocate = onLocate,
            onForget = { targetStore.remove(it) },
            onBack = { goTo(if (startedAtMs == 0L) Step.LIBRARY else Step.HUB) },
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
            enabled = state.legs.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export the session") }

        Spacer(Modifier.height(12.dp))
        DiagnosticsPanel(
            title = "What this is seeing",
            diagnostics = listOf(
                Diagnostic("In range", "${state.watching}", "addresses heard"),
                Diagnostic("Still in", "${state.survivors}", "survived every test"),
                Diagnostic(
                    "Tests",
                    "${state.legs.count { it.kind != LegKind.BASELINE }}",
                    "run",
                ),
                Diagnostic(
                    "Arrived",
                    "${state.candidates.count { it.arrived }}",
                    "after the baseline",
                ),
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
                Diagnostic(
                    "Short list",
                    if (state.narrowed) "${state.shortlist.size}" else "-",
                    "of ${FollowSession.SHORTLIST_MAX}",
                ),
            ),
            footnote = "Only a device heard during a test counts as having survived it, so " +
                "something that dropped out and came back has not. That is what makes the " +
                "elimination work, and it is also why walking through a tunnel will cost " +
                "you the target.",
        )
    }
}

/**
 * Keeps the short list attached to its devices across address changes.
 *
 * Watched for every candidate on the short list rather than only for a locked target, which
 * is where the first version of this was wrong. Five devices being watched is five chances
 * to keep the trail; watching only the one already committed to means the rotation that
 * loses you the target is the one nobody was looking at.
 *
 * The decision stays [Following]'s, refusals and all. This acts on a yes and does nothing
 * else - a candidate that cannot be followed unambiguously is dropped rather than guessed
 * at, and the log says which.
 */
private fun watchForRotations(
    state: FollowState,
    session: FollowSession,
    live: Map<String, LiveAddress>,
    nowMs: Long,
    onMoved: (String) -> Unit,
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
                    "${candidate.label ?: candidate.address} rotated to ${decision.address}, " +
                        "${decision.score.points} points of evidence",
                )
            }
        }
    }
}

// -------------------------------------------------------------------------- the steps

@Composable
private fun Library(
    follows: List<SavedFollow>,
    targetCount: Int,
    onNew: () -> Unit,
    onTargets: () -> Unit,
    onForget: (String) -> Unit,
) {
    Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Start a new follow") }

    if (targetCount > 0) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTargets, modifier = Modifier.fillMaxWidth()) {
            Text("Targets ($targetCount)")
        }
    }

    Spacer(Modifier.height(18.dp))
    Text(
        "Past follows",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))

    if (follows.isEmpty()) {
        Text(
            "None yet. A follow is twenty minutes of walking about, and it used to " +
                "evaporate the moment you left the screen - which was wrong for the longest " +
                "single thing anybody does in here. What gets kept is the conclusion and " +
                "the conditions, not the packets.",
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
                follow.leads.forEach { lead ->
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
    onName: (String) -> Unit,
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
        "It starts with a baseline: about half a minute of listening to everything audible " +
            "from where you are standing. That number is what every later claim gets " +
            "divided by, and taking it before anything else is what makes the rest mean " +
            "anything.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Then you pick tests. Stand still, walk a circle around them, walk past them, or go " +
            "with them. Each rules out a different kind of thing, and you choose as you go " +
            "rather than following a script - because you are the one who can see whether " +
            "they are sitting still or about to leave.",
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
    Grey("Not now", onCancel)
}

@Composable
private fun Baseline(
    state: FollowState,
    rates: List<Float>,
    elapsedMs: Long,
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
            elapsedMs = elapsedMs.coerceIn(0L, BASELINE_MS),
            totalMs = BASELINE_MS,
            label = "baseline",
            caption = "stand still",
        )
    }

    Spacer(Modifier.height(14.dp))
    LiveBars(values = rates, spoken = barsSpoken(rates, "advertisements a second"))
    Spacer(Modifier.height(4.dp))
    Text(
        "Every bar is a second of what the radio is actually hearing. This is the " +
            "denominator being measured, not a loading bar.",
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
        "Answer while this runs. It changes what happens next rather than what is being " +
            "recorded. If they are here, the baseline is the room and you start ruling " +
            "devices out of it. If they are not, the baseline is everything that was here " +
            "before them, and whoever walks in afterwards is a much shorter list.",
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
    val arrivals = state.candidates.filter { it.arrived }

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
        CountUp(value = arrivals.size, fontSize = 72.sp)
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

    if (arrivals.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        arrivals.take(8).forEach { CandidateCard(it, onClick = null) }
    }

    Spacer(Modifier.height(12.dp))
    Button(onClick = onArrived, modifier = Modifier.fillMaxWidth()) {
        Text("They are here now")
    }
}

/**
 * The plan, and everything ruled out so far.
 *
 * This is the screen the experiment lives on. It has to answer three questions without
 * being asked: how far has this got, what can I do next, and is it worth carrying on. The
 * four tests are laid out as four things you might be able to do rather than as a sequence,
 * because which of them is possible depends on what the person you are following is doing,
 * and only the person holding the phone can see that.
 */
@Composable
private fun Hub(
    state: FollowState,
    targets: List<TargetDevice>,
    nowMs: Long,
    rebaselinePrompt: Boolean,
    onDismissRebaseline: () -> Unit,
    onRebaseline: () -> Unit,
    onStandStill: () -> Unit,
    onCircle: () -> Unit,
    onWalkBy: () -> Unit,
    onMobile: () -> Unit,
    onEndLeg: () -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onPromote: (FollowCandidate) -> Unit,
    onTargets: () -> Unit,
    onFinish: () -> Unit,
) {
    val running = state.legs.lastOrNull()?.takeIf { it.running && it.kind != LegKind.BASELINE }
    val tested = state.legs.any { it.kind != LegKind.BASELINE }

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
            CountUp(value = if (tested) state.survivors else state.watching, fontSize = 64.sp)
            Text(
                if (tested) {
                    "still in the running, out of ${state.watching}"
                } else {
                    "audible from here, and nothing ruled out yet"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.narrowed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Short list. Every one of these is being watched for an address change.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

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
                    "Five minutes in and still ${state.survivors} in the running. Almost " +
                        "always one thing: their phone changed address partway through, so " +
                        "the device you were converging on stopped existing and its " +
                        "replacement has missed every test since. Starting again puts " +
                        "everything back on equal terms without forgetting the room.",
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

    Spacer(Modifier.height(14.dp))

    if (running != null) {
        Text(
            running.kind.label + " · " + (running.durationMs(nowMs) / 1000) + " s",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onEndLeg, modifier = Modifier.fillMaxWidth()) {
            Text("End this test")
        }
    } else {
        Text(
            "What can you do right now?",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        Test(
            title = "Go with them",
            detail = "The strongest test there is. Walk a few minutes together and almost " +
                "nothing else comes along - the shops fall away, the parked cars fall " +
                "away, the other passengers get off.",
            onClick = onMobile,
            emphasis = true,
        )
        Test(
            title = "Walk past them",
            detail = if (state.walkedBy) {
                "Already done. ${state.passed} peaked as you passed."
            } else {
                "They stand still; you walk past and stop the same distance away on the " +
                    "far side. Whatever is on them rises as you draw level and comes back " +
                    "down. This one picks devices out rather than ruling them out."
            },
            onClick = onWalkBy,
            enabled = !state.walkedBy,
        )
        Test(
            title = "Circle them",
            detail = if (state.orbited) {
                "Already done. ${state.centred} stayed at the same distance."
            } else {
                "One slow lap about five paces out. Anything on them stays the same " +
                    "distance from you the whole way round; anything across the room does " +
                    "not."
            },
            onClick = onCircle,
            enabled = !state.orbited,
        )
        Test(
            title = "Stand still a while",
            detail = "Cuts whatever walks past and very little else. Worth it when they " +
                "are not going anywhere and neither are you.",
            onClick = onStandStill,
        )
    }

    if (state.narrowed) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Short list",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Tap one to hold onto it, or add it to your targets to use it in the other " +
                "experiments.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        state.shortlist.forEach { candidate ->
            val already = targets.any { it.address.equals(candidate.address, true) }
            CandidateCard(
                candidate = candidate,
                onClick = { onHold(candidate) },
                action = if (already) null else "Add to targets",
                onAction = { onPromote(candidate) },
            )
        }
    } else if (tested) {
        Spacer(Modifier.height(14.dp))
        Text(
            if (state.survivors == 0) {
                "Nothing has survived every test. If they were with you the whole time, " +
                    "the likeliest reason is that their phone changed address partway " +
                    "through - which reads as two devices that each missed a test."
            } else {
                "${state.survivors} still in. Five or fewer is where this becomes a short " +
                    "list worth acting on, and going with them is what gets you there."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TakeawayButton(takeawayFrom(state))

    Spacer(Modifier.height(10.dp))
    if (targets.isNotEmpty()) {
        OutlinedButton(onClick = onTargets, modifier = Modifier.fillMaxWidth()) {
            Text("Targets (${targets.size})")
        }
        Spacer(Modifier.height(6.dp))
    }
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
            "${state.watching} in range",
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

@Composable
private fun Mobile(state: FollowState, nowMs: Long, onStop: () -> Unit) {
    val running = state.legs.lastOrNull()?.takeIf { it.running }

    Text(
        "Going with them",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Keep walking. Every corner you turn together costs whatever did not come with you.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(value = state.survivors, fontSize = 96.sp)
        Text(
            "still with you, out of ${state.watching}",
            style = MaterialTheme.typography.bodyMedium,
        )
        running?.let {
            Text(
                "${it.durationMs(nowMs) / 1000} s into this leg",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.narrowed) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Short list reached. Watching all of them for an address change.",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
        Text("Stop here, end the leg")
    }
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
        val lost = silentFor > LOST_AFTER_MS
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Lost. It may have changed address, in which case it is in range " +
                            "under a name nothing here recognises. Persistent Tracking is " +
                            "the experiment that looks for it, and it will only take it " +
                            "back on unambiguous evidence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
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
private fun Test(
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
                Text(
                    "${candidate.meanRssi.roundToInt()} dBm",
                    style = MaterialTheme.typography.labelMedium,
                )
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
            "This narrows a room down to the device travelling with somebody, without " +
                "knowing anything about it in advance. It works by elimination and by " +
                "geometry: stand together and most of what is in range stays in range, " +
                "which proves nothing. Walk a mile together and almost nothing does.",
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
}

/**
 * The short list as something that can be shown big, or null when it is not worth showing.
 *
 * Refused while nothing has been ruled out, because the number would be "everything in the
 * room" dressed up as "everything following you" - and a card is the one place that mistake
 * travels furthest.
 */
private fun takeawayFrom(state: FollowState): Takeaway? {
    if (state.watching == 0) return null
    val tests = state.legs.count { it.kind != LegKind.BASELINE && !it.running }
    if (tests == 0) return null

    return Takeaway(
        experiment = "Follow Me",
        headline = "${state.survivors}",
        unit = if (state.survivors == 1) {
            "device stayed with them"
        } else {
            "devices stayed with them"
        },
        denominator = "out of ${state.watching} heard along the way",
        context = listOfNotNull(
            "$tests test" + if (tests == 1) "" else "s",
            state.legs.count { it.moving }.takeIf { it > 0 }?.let { "$it walked together" },
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
    Field("Survived", target.describe())
    Field("Signal", "${target.meanRssi.roundToInt()} dBm average")

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
    Grey("Let it go", onUnlock)
}
