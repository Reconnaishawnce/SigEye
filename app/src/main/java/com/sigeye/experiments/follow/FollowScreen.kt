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
import androidx.compose.ui.unit.sp
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.SweepExport
import com.sigeye.core.Takeaway
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
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.TakeawayButton
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HUB_TAG = "follow"
private const val TICK_MS = 1_000L

/** Long enough to hear a device advertising once every couple of seconds, several times. */
private const val CENSUS_MS = 30_000L

/** A comfortable lap at five paces out, walked slowly enough that the level is not blurred. */
private const val CIRCLE_MS = 75_000L

/**
 * Where the guided follow has got to.
 *
 * The old screen put every control on one page and left the person holding it to work out
 * the method from the button labels. The method is the whole thing here - get close, see
 * what is centred on them, then see what comes with you - and it is a sequence, so the
 * screen is a sequence. One instruction at a time, in the order you would actually do them.
 */
private enum class Step {
    /** What this is and what it needs from you, before anything starts. */
    BRIEF,

    /** A census of everything audible from where you are standing. */
    LISTEN,

    /** Are they actually here? Nothing that follows works if they are not. */
    NEAR_CHECK,

    /** The instruction for the lap, before the clock starts. */
    CIRCLE_READY,

    /** Walking the lap. */
    CIRCLE,

    /** What the lap said. */
    CIRCLE_DONE,

    /** Walking together, one leg at a time. */
    WALK,

    /** The short list, and the denominator that says what it is worth. */
    PICK,

    /** One device chosen, and held onto. */
    HOLD,
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
    val feedback = remember { Feedback(context) }
    val live = remember { LinkedHashMap<String, LiveAddress>() }

    // Replaceable, because a lap walked badly cannot be walked again inside one session -
    // two circles at different radii are not the same measurement - so starting over has
    // to genuinely start over.
    var session by remember { mutableStateOf(FollowSession()) }

    var state by remember {
        mutableStateOf(
            FollowState(FollowPhase.CENSUS, emptyList(), emptyList(), 0, null, 0, null, null),
        )
    }
    var step by remember { mutableStateOf(Step.BRIEF) }
    var stepStartedMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var waitingForThem by remember { mutableStateOf(false) }
    var wasLost by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    fun goTo(next: Step) {
        step = next
        stepStartedMs = System.currentTimeMillis()
    }

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
            nowMs = now
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

            // The two timed steps end themselves. A person walking a circle round somebody
            // is not looking at the phone, and a step that waits to be tapped would still
            // be running when they stopped.
            when (step) {
                // The census leg is deliberately left running. Whoever is waiting for
                // their friend to walk over is still standing in the same place, and
                // anything that arrives while they wait belongs in the count. The next
                // leg closes it.
                Step.LISTEN -> if (now - stepStartedMs >= CENSUS_MS) {
                    feedback.alert(AlertStyle.BOTH, urgent = false)
                    step = Step.NEAR_CHECK
                    stepStartedMs = now
                }

                Step.CIRCLE -> if (now - stepStartedMs >= CIRCLE_MS) {
                    session.endLeg(now)
                    feedback.alert(AlertStyle.BOTH, urgent = false)
                    step = Step.CIRCLE_DONE
                    stepStartedMs = now
                }

                else -> Unit
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

    if (step >= Step.WALK) {
        Headline(state)
        Spacer(Modifier.height(12.dp))
    }

    when (step) {
        Step.BRIEF -> Brief(
            onStart = {
                session.beginLeg("Standing where you started", LegKind.STILL, System.currentTimeMillis())
                goTo(Step.LISTEN)
            },
        )

        Step.LISTEN -> Timed(
            elapsedMs = nowMs - stepStartedMs,
            totalMs = CENSUS_MS,
            label = "Listening",
            caption = "${state.watching} devices heard so far",
            instruction = "Stand still. This is a census of everything audible from where " +
                "you are, and it is the number every later claim gets divided by.",
        )

        Step.NEAR_CHECK -> NearCheck(
            heard = state.watching,
            waiting = waitingForThem,
            onWait = { waitingForThem = true },
            onReady = {
                waitingForThem = false
                goTo(Step.CIRCLE_READY)
            },
            onSkip = { goTo(Step.WALK) },
        )

        Step.CIRCLE_READY -> CircleReady(
            onStart = {
                session.beginLeg("The circle", LegKind.ORBIT, System.currentTimeMillis())
                goTo(Step.CIRCLE)
            },
            onSkip = { goTo(Step.WALK) },
        )

        Step.CIRCLE -> Timed(
            elapsedMs = nowMs - stepStartedMs,
            totalMs = CIRCLE_MS,
            label = "Circling",
            caption = "${state.centred} of ${state.watching} still at the same distance",
            instruction = "Keep walking. One slow lap, about five paces out, phone in your " +
                "hand in front of you. Do not double back.",
        )

        Step.CIRCLE_DONE -> CircleDone(
            state = state,
            onWalk = { goTo(Step.WALK) },
            onStartOver = {
                session = FollowSession()
                live.clear()
                log = emptyList()
                goTo(Step.BRIEF)
            },
        )

        Step.WALK -> Walk(
            state = state,
            nowMs = nowMs,
            onBeginLeg = { kind ->
                session.beginLeg(kind.label, kind, System.currentTimeMillis())
            },
            onEndLeg = { session.endLeg(System.currentTimeMillis()) },
            onShowList = { goTo(Step.PICK) },
        )

        Step.PICK -> Pick(
            state = state,
            onLock = {
                session.lock(it.address)
                goTo(Step.HOLD)
            },
            onWalkMore = { goTo(Step.WALK) },
        )

        Step.HOLD -> Holding(
            state = state,
            log = log,
            onUnlock = {
                session.unlock()
                goTo(Step.PICK)
            },
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
            Diagnostic("Survivors", "${state.survivors}", "in every leg"),
            Diagnostic(
                "Centred",
                if (state.orbited) "${state.centred}" else "no circle",
                "survived the circle",
            ),
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

// ------------------------------------------------------------------------- the steps

@Composable
private fun Brief(onStart: () -> Unit) {
    Text(
        "Following somebody, step by step",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Three tests, in order. First a census of everything audible from where you are " +
            "standing, which is the number everything else gets divided by. Then a slow " +
            "circle around the person, which cuts out everything not centred on them. Then " +
            "walking together, which cuts out everything that stayed behind.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "You will need the person with you, and about five minutes. They should know you " +
            "are doing it.",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start a follow") }
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
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

@Composable
private fun NearCheck(
    heard: Int,
    waiting: Boolean,
    onWait: () -> Unit,
    onReady: () -> Unit,
    onSkip: () -> Unit,
) {
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
        "Is the person you are following within a few steps of you?",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Nothing after this works if they are across the room. The circle needs you close " +
            "enough that walking round them actually changes where everything else is.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (waiting) {
        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                "No hurry. The census keeps running, so whatever arrives while you wait is " +
                    "counted too. Tap below when they are with you.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onReady, modifier = Modifier.weight(1f)) {
            Text("They are right here")
        }
        OutlinedButton(onClick = onWait, modifier = Modifier.weight(1f)) {
            Text("Not yet")
        }
    }
    Spacer(Modifier.height(4.dp))
    SkipLink("Skip the circle, go straight to walking", onSkip)
}

@Composable
private fun CircleReady(onStart: () -> Unit, onSkip: () -> Unit) {
    Text(
        "Walk one slow circle around them",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "About five paces out. One lap, taking a bit over a minute, phone in your hand in " +
            "front of you. Do not double back - the test is that you keep going round.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Anything on them stays the same distance from you the whole way round, so it is " +
            "heard in every arc and its level barely moves. Anything across the room comes " +
            "nearer and then goes further, and usually drops out for part of the lap.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start the circle") }
    Spacer(Modifier.height(4.dp))
    SkipLink("Skip it", onSkip)
}

@Composable
private fun CircleDone(state: FollowState, onWalk: () -> Unit, onStartOver: () -> Unit) {
    val centred = state.candidates.filter { it.orbit?.centred == true }

    Text(
        "${centred.size} of ${state.watching} stayed the same distance away",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))

    if (centred.isEmpty()) {
        Text(
            "Nothing passed. Usually that means the lap was too quick, or too tight, or " +
                "their phone was not advertising while you walked it. A circle cannot be " +
                "walked twice in one session - two laps at different radii are not the " +
                "same measurement - so this is a start-over or a carry-on.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "These are centred on the middle of the circle. Everything else they are " +
                "carrying passes this too - a watch, earbuds, a card - and so does anything " +
                "sitting on a table at the middle of the lap. It narrows; it does not name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        centred.take(8).forEach { CandidateCard(it, onClick = null) }
    }

    Spacer(Modifier.height(12.dp))
    Button(onClick = onWalk, modifier = Modifier.fillMaxWidth()) {
        Text("Now walk together")
    }
    Spacer(Modifier.height(4.dp))
    SkipLink("Start over", onStartOver)
}

@Composable
private fun Walk(
    state: FollowState,
    nowMs: Long,
    onBeginLeg: (LegKind) -> Unit,
    onEndLeg: () -> Unit,
    onShowList: () -> Unit,
) {
    val running = state.legs.lastOrNull()?.takeIf { it.running }
    val movingLegs = state.legs.count { it.moving && !it.running }

    Text(
        when {
            running != null -> "Keep walking. End the leg when you stop."
            movingLegs == 0 -> "Now walk together for two or three minutes, then stop. This " +
                "is the test that does the real work: almost nothing in a street comes with " +
                "you when you leave it."
            movingLegs == 1 -> "One leg down. A second one, ideally somewhere different, " +
                "turns a short list into a claim."
            else -> "$movingLegs legs walked. Each one cuts whatever did not come along."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    if (running != null) {
        // The survivor count is the big number here, not the stopwatch. Watching it fall
        // from two hundred to three as you walk is the whole demonstration, and it was
        // previously the small grey line under a timer nobody needed.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CountUp(value = state.survivors, fontSize = 88.sp)
            Text(
                "still with you, out of ${state.watching}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${running.durationMs(nowMs) / 1000} s into this leg",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onEndLeg, modifier = Modifier.fillMaxWidth()) {
            Text("Stop here, end the leg")
        }
    } else {
        Button(
            onClick = { onBeginLeg(LegKind.TOGETHER) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (movingLegs == 0) "Start walking" else "Walk another leg") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onBeginLeg(LegKind.STILL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stand somewhere for a while instead") }

        if (state.legs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onShowList, modifier = Modifier.fillMaxWidth()) {
                Text("Show me who is left")
            }
        }
    }

    if (state.legs.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        state.legs.forEach { leg ->
            Field(
                leg.label,
                "${leg.durationMs(nowMs) / 1000} s" + if (leg.running) " · running" else "",
            )
        }
    }
}

@Composable
private fun Pick(
    state: FollowState,
    onLock: (FollowCandidate) -> Unit,
    onWalkMore: () -> Unit,
) {
    val survivors = state.candidates.filter { it.survivedAll }

    Text(state.narrowing(), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))

    if (state.legs.none { it.moving }) {
        Text(
            "None of these legs involved walking, so this is everything that was in the " +
                "room - not everything that is following you.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
    }

    if (survivors.isEmpty()) {
        Text(
            "Nothing has survived every leg. If the target was with you the whole time, it " +
                "probably changed address partway through, which counts as two devices that " +
                "each missed a leg. Walk another one and see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Best first. Tap one to hold onto it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        survivors.take(12).forEach { candidate ->
            CandidateCard(candidate, onClick = { onLock(candidate) })
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onWalkMore, modifier = Modifier.fillMaxWidth()) {
        Text("Walk another leg")
    }

    TakeawayButton(takeawayFrom(state))
}

/**
 * The short list as something that can be shown big, or null when it is not worth showing.
 *
 * Refused while no leg involved walking, because the number would be "everything that was
 * in the room" dressed up as "everything following you" - and a card is the one place that
 * mistake travels furthest. The limit line is the same sentence the screen has always said
 * and is the reason this experiment is defensible at all: co-presence is not identity.
 */
private fun takeawayFrom(state: FollowState): Takeaway? {
    if (state.watching == 0) return null
    val movingLegs = state.legs.count { it.moving && !it.running }
    if (movingLegs == 0) return null

    return Takeaway(
        experiment = "Follow Me",
        headline = "${state.survivors}",
        unit = if (state.survivors == 1) "device stayed with you" else "devices stayed with you",
        denominator = "out of ${state.watching} heard along the way",
        context = listOfNotNull(
            "$movingLegs walking leg" + if (movingLegs == 1) "" else "s",
            "${state.legs.size} legs in total",
            if (state.orbited) "${state.centred} survived the circle" else null,
        ),
        limit = "Co-presence is not identity. These devices were in range whenever you " +
            "were - nothing here says any of them is a phone, or whose, and none of them " +
            "was connected to.",
    )
}

// ------------------------------------------------------------------------ small parts

/** A way past a step, in grey, for somebody who has done this before. */
@Composable
private fun SkipLink(label: String, onClick: () -> Unit) {
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
private fun CandidateCard(candidate: FollowCandidate, onClick: (() -> Unit)?) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
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
    TextButton(onClick = onUnlock) { Text("Let it go") }
}
