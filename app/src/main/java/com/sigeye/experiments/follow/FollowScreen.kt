package com.sigeye.experiments.follow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.sigeye.core.CsvExport
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.FollowLead
import com.sigeye.core.FollowLibrary
import com.sigeye.core.FollowRunner
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import com.sigeye.core.SavedFollow
import com.sigeye.core.ScanService
import com.sigeye.core.SweepExport
import com.sigeye.core.Takeaway
import com.sigeye.core.TargetDevice
import com.sigeye.core.TargetStore
import com.sigeye.core.analysis.identity.CandidateWalkBy
import com.sigeye.core.analysis.identity.CaseFile
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowPhase
import com.sigeye.core.analysis.identity.FollowSession
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.core.analysis.identity.CandidateScore
import com.sigeye.core.analysis.identity.Handoff
import com.sigeye.core.analysis.identity.Journal
import com.sigeye.core.analysis.identity.Mark
import com.sigeye.core.analysis.identity.Odds
import com.sigeye.core.analysis.identity.Scoring
import com.sigeye.core.analysis.identity.Stitch
import com.sigeye.core.analysis.identity.Probe
import com.sigeye.core.analysis.identity.ProbeRun
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.DeviceKind
import com.sigeye.core.ble.shape
import com.sigeye.ui.CountUp
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.GeigerBar
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.LiveBars
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.RotationCountdown
import com.sigeye.ui.Section
import com.sigeye.ui.TakeawayButton
import com.sigeye.ui.radar.RadarPanel
import com.sigeye.ui.radar.RadarTarget
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

/** How often the in-progress follow is written down, in seconds of wall clock. */
private const val SAVE_EVERY_S = 15L

/**
 * Where a follow has got to on screen.
 *
 * Deliberately few. Following somebody is one continuous thing, and the app's job during it
 * is to stay out of the way: one screen, one falling number, nothing to press while you
 * walk. The circle and the walk-by are the only interruptions, and they are things you
 * chose to do.
 */
/**
 * How the person has answered the one question worth asking in the first minute.
 *
 * Your own earbuds survive every test in this app by construction: they go where you go, so
 * they orbit when you orbit and pass when you pass. Left in, they sit at the top of the
 * short list forever and the follow looks like it worked.
 *
 * Three ways out, because they trade against each other and only the operator knows which
 * trade they want. Nothing here can work out which devices are yours on its own.
 */
private enum class OwnKit {
    /** Not yet offered. */
    UNASKED,

    /** Offered and settled, one way or another. */
    DECIDED,
}

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
    /** Opens the radar with this device already filtered for. */
    onRadar: (String) -> Unit = {},
    /** Opens Defeating Randomization already tracking it. */
    onRotation: (String) -> Unit = {},
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
            Live(onLocate, onRadar, onRotation)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live(
    onLocate: (String) -> Unit,
    onRadar: (String) -> Unit,
    onRotation: (String) -> Unit,
) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val library = remember { FollowLibrary.get(context) }
    val targetStore = remember { TargetStore.get(context) }
    val settings = remember { FollowSettings(context) }
    val ignoreList = remember { IgnoreList.get(context) }
    val feedback = remember { Feedback(context) }

    var tuning by remember { mutableStateOf(settings.load()) }

    // The session lives in FollowRunner rather than in this composition, because a follow
    // is half an hour of walking and the phone is in a pocket for most of it. Holding a lit
    // screen for a mile is conspicuous, costs the battery, and is the opposite of what
    // anybody demonstrating this would do. The service keeps feeding it while the screen is
    // away; the screen reads what it says.
    val state by FollowRunner.state.collectAsStateWithLifecycle()

    // A function rather than a value, because the runner hands out a different session
    // after a reset and anything holding the old one would be writing into a follow nobody
    // is reading. A local val cannot have a getter, which is what I reached for first.
    fun session(): FollowSession = FollowRunner.session()

    var step by remember { mutableStateOf(Step.LIBRARY) }
    var stepStartedMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var followName by remember { mutableStateOf("") }
    var startedAtMs by remember { mutableStateOf(0L) }
    var theyAreHere by remember { mutableStateOf<Boolean?>(null) }
    var levelMarked by remember { mutableStateOf(false) }
    var dismissedRebaseline by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf<FollowCandidate?>(null) }
    var acting by remember { mutableStateOf<FollowCandidate?>(null) }
    var expandedWalkBy by remember { mutableStateOf<Int?>(null) }
    var announcedShortlist by remember { mutableStateOf(false) }
    var resumable by remember { mutableStateOf(library.loadInProgress() != null) }
    var wasLost by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    /** How many rotations have already been announced, so each is announced once. */
    var seenStitches by remember { mutableStateOf(0) }

    /** Every candidate's case, recomputed on the tick rather than during composition. */
    var scores by remember { mutableStateOf<Map<String, CandidateScore>>(emptyMap()) }

    /** Rotations followed so far, mirrored out of the session for the screen to show. */
    var stitchLog by remember { mutableStateOf<List<Stitch>>(emptyList()) }

    /** Which rotation question is on screen, and which have been put off. */
    var asking by remember { mutableStateOf<String?>(null) }
    val deferred = remember { mutableStateListOf<String>() }

    /** How many marks have been made, so the section can say so without reading the journal. */
    var marked by remember { mutableStateOf(0) }

    /** Kinds of device the list is narrowed to, or empty for all of them. */
    val kindFilter = remember { mutableStateListOf<DeviceKind>() }

    /** Whether the pocket is pulsing, and at what. */
    var geiger by remember { mutableStateOf(false) }

    /** Where the person is with the question of which devices are their own. */
    var ownKit by remember { mutableStateOf(OwnKit.UNASKED) }
    val tests = remember { mutableStateListOf<String>() }

    // Two different quantities, both measured here rather than read from somewhere that
    // updates on its own schedule. During the baseline the useful one is how fast the room
    // is filling up; during a follow it is how many are left. The first version of these
    // bars sampled a rate the radio republishes every ten seconds, so thirty identical
    // readings were drawn as a chart - which is exactly as informative as it sounds.
    val bars = remember { mutableStateListOf<Float>() }
    var lastWatched by remember { mutableStateOf(0) }

    val ignored by ignoreList.addresses.collectAsStateWithLifecycle()
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

    /**
     * Starts the census running.
     *
     * The session is fed by [ScanService] and by nothing else, so until the service is in
     * FOLLOW mode the baseline is a countdown in front of an empty list. This used to be
     * called only once the baseline had finished, which meant the census counted nothing
     * and every device in the room then looked like an arrival.
     */
    fun beginBaseline() {
        ScanService.start(context, ScanService.Mode.FOLLOW)
        bars.clear()
        lastWatched = 0
        goTo(Step.BASELINE)
    }

    fun resume() {
        val saved = library.loadInProgress() ?: return
        val restored = FollowSession(settings.load())
        runCatching { restored.restore(org.json.JSONObject(saved)) }
            .onFailure {
                library.clearInProgress()
                resumable = false
                return
            }
        tuning = restored.tuning
        FollowRunner.adopt(restored)
        ScanService.start(context, ScanService.Mode.FOLLOW)
        bars.clear()
        lastWatched = 0
        startedAtMs = restored.state(System.currentTimeMillis()).followStartedAtMs ?: 0L
        theyAreHere = true
        // Whatever gap there was while nothing was listening does not count as silence.
        // Without this, coming back to a follow would find every device dropped at once.
        restored.resume(System.currentTimeMillis())
        step = when (restored.phase) {
            FollowPhase.HOLDING, FollowPhase.LOST -> Step.HOLD
            FollowPhase.FOLLOWING -> Step.FOLLOWING
            else -> Step.BRIEF
        }
        stepStartedMs = System.currentTimeMillis()
    }

    fun reset() {
        library.clearInProgress()
        resumable = false
        tuning = settings.load()
        FollowRunner.begin(tuning)
        log = emptyList()
        tests.clear()
        bars.clear()
        lastWatched = 0
        theyAreHere = null
        levelMarked = false
        dismissedRebaseline = false
        startedAtMs = 0L
    }

    // Only while something on screen is worth looking at. A follow proper runs in the
    // service now, so the phone can be in a pocket for the walk - which is the difference
    // between a demonstration and somebody holding a lit screen down a street.
    KeepScreenOn(step == Step.BASELINE || step == Step.CIRCLE || step == Step.WALK_BY)

    BackHandler(enabled = step != Step.LIBRARY) {
        step = if (startedAtMs == 0L) Step.LIBRARY else Step.FOLLOWING
    }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            val now = System.currentTimeMillis()
            if (session().state(now).followStartedAtMs != null) {
                // The service is still listening, so the clock keeps running - only the
                // screen has gone. Written down anyway, because a killed process would
                // otherwise take half an hour of walking with it.
                library.saveInProgress(session().snapshot().toString())
            } else {
                // Nothing worth keeping the radio open for.
                ScanService.stop(context, ScanService.Mode.FOLLOW)
            }
            feedback.release()
            BleScanHub.release(HUB_TAG)
        }
    }

    // The session itself is fed by the service. This keeps the second, parallel record that
    // re-acquisition needs, because identities want interval and signal history and the
    // session does not carry either.
    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            // The session does its own fingerprinting now, from the same packets. This
            // kept a second parallel copy of every address in range purely to feed a
            // rotation watcher the session has taken over.
            targetStore.heard(advert.address.uppercase(Locale.US), advert.atMs)
        }
    }

    // Kept on the session rather than filtered in the screen, so a device you have said is
    // yours never reaches the pool, the radar, the short list or the export.
    LaunchedEffect(ignored, state.atMs) {
        session().ignored = ignored
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            nowMs = now

            // Ticked here as well as on the service, so the screen is live even in the
            // moment before the service has attached. Recomputing the state twice is
            // harmless: it is derived from the session rather than accumulated.
            FollowRunner.tick(now, ignoreList)
            val next = FollowRunner.state.value

            // Scored here rather than in the session, because it is a reading of the
            // session rather than part of it, and because correlating every pair of trails
            // twice a second has no business happening inside a recomposition.
            scores = Scoring.score(
                candidates = next.candidates,
                tuning = next.tuning,
                nowMs = now,
                trails = session().trails(),
            ).associateBy { it.address }

            // Rotations the session followed on its own. The screen's job is to say so and
            // to keep anything holding an address in step - it is not the screen's decision
            // any more, which is the point of having moved it into the session.
            val stitches = session().stitches()
            if (stitches.size > seenStitches) {
                stitches.drop(seenStitches).forEach { stitch ->
                    targetStore.reacquire(stitch.fromAddress, stitch.toAddress, now)
                    log = listOf(
                        "${stitch.fromAddress} became ${stitch.toAddress}" +
                            if (stitch.byHand) ", you picked it" else ", followed automatically",
                    ) + log
                }
                seenStitches = stitches.size
                stitchLog = stitches
                feedback.alert(AlertStyle.BOTH, urgent = false)
            }

            // One question at a time. A second dialog stacking on the first during a walk
            // is how somebody ends up tapping through both without reading either.
            if (asking == null) {
                next.questions.firstOrNull { it.departure.address !in deferred }
                    ?.let { question ->
                        asking = question.departure.address
                        feedback.alert(AlertStyle.BOTH, urgent = true)
                    }
            }

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
                session().endBaseline(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                when (hereAnswer) {
                    null -> step = Step.ASK_HERE

                    true -> {
                        session().startFollowing(now)
                        ScanService.start(context, ScanService.Mode.FOLLOW)
                        bars.clear()
                        step = Step.FOLLOWING
                    }

                    else -> step = Step.WAITING
                }
                stepStartedMs = now
            }

            if (currentStep == Step.CIRCLE && now - stepStarted >= currentTuning.circleMs) {
                session().endProbe(now)
                feedback.alert(AlertStyle.BOTH, urgent = false)
                step = Step.FOLLOWING
                stepStartedMs = now
            }

            if (wasLost && next.phase == FollowPhase.HOLDING) {
                feedback.alert(AlertStyle.BOTH, urgent = true)
                log = listOf("Back in range") + log
            }
            // A buzz when the list first gets short enough to act on. Whoever is walking is
            // not looking at the phone, and this is the moment worth looking up for.
            if (next.narrowed && !announcedShortlist) {
                announcedShortlist = true
                feedback.alert(AlertStyle.BOTH, urgent = true)
            } else if (!next.narrowed) {
                announcedShortlist = false
            }

            wasLost = next.phase == FollowPhase.LOST

            // Kept up to date rather than only written on the way out, because the way out
            // is not always graceful - a killed process would otherwise take the follow.
            if (next.followStartedAtMs != null && next.atMs / 1000 % SAVE_EVERY_S == 0L) {
                library.saveInProgress(session().snapshot().toString())
            }
        }
    }

    acting?.let { candidate ->
        DeviceActionsDialog(
            candidate = candidate,
            tuning = tuning,
            onDismiss = { acting = null },
            onKeep = {
                acting = null
                keeping = candidate
            },
            onMine = {
                acting = null
                ignoreList.add(candidate.address)
            },
            onRadar = {
                acting = null
                onRadar(candidate.address)
            },
            onRotation = {
                acting = null
                onRotation(candidate.address)
            },
            onLocate = {
                acting = null
                onLocate(candidate.address)
            },
            onHold = {
                acting = null
                session().lock(candidate.address)
                goTo(Step.HOLD)
            },
        )
    }

    keeping?.let { candidate ->
        DeviceListDialog(
            address = candidate.address,
            suggestedName = candidate.vendor,
            onDismiss = { keeping = null },
        )
    }

    // The rotation question. Shown over whatever step the follow is on, because the
    // answer is time-limited: the successor has to still be audible when it is given.
    asking?.let { address ->
        state.questions.firstOrNull { it.departure.address == address }?.let { question ->
            val name = state.candidates.firstOrNull { it.address == address }
                ?.let { it.label ?: it.vendor }
                ?: address
            RotationDialog(
                ask = question,
                label = name,
                onPick = { to ->
                    session().answer(address, to, System.currentTimeMillis())
                    asking = null
                },
                onNone = {
                    session().answer(address, null, System.currentTimeMillis())
                    log = listOf("$name was none of the options, dropped") + log
                    asking = null
                },
                onLater = {
                    deferred.add(address)
                    asking = null
                },
            )
        } ?: run { asking = null }
    }

    if (showSettings) {
        FollowSettingsDialog(
            initial = tuning,
            onDismiss = { showSettings = false },
            onSave = {
                settings.save(it)
                tuning = it
                session().tuning = it
                showSettings = false
            },
        )
    }

    when (step) {
        Step.LIBRARY -> Library(
            follows = follows,
            targetCount = targets.size,
            resumable = resumable,
            onResume = { resume() },
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
                session().startBaseline(now)
                tests += "baseline"
                beginBaseline()
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
                    session().startFollowing(System.currentTimeMillis())
                    ScanService.start(context, ScanService.Mode.FOLLOW)
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
                session().startFollowing(System.currentTimeMillis())
                ScanService.start(context, ScanService.Mode.FOLLOW)
                bars.clear()
                goTo(Step.FOLLOWING)
            },
        )

        Step.FOLLOWING -> Following(
            state = state,
            scores = scores,
            kindFilter = kindFilter.toList(),
            journal = session().journal,
            marks = marked,
            onMark = { mark ->
                session().mark(mark, System.currentTimeMillis())
                marked++
                feedback.alert(AlertStyle.BUZZ)
            },
            onCaseFile = {
                CsvExport.shareText(
                    context = context,
                    folder = "follow",
                    prefix = "case",
                    content = CaseFile.write(
                        name = followName,
                        state = state,
                        journal = session().journal,
                        scores = scores.values.toList(),
                        stitches = stitchLog,
                    ),
                )
            },
            onKindFilter = { kind ->
                if (kind in kindFilter) kindFilter.remove(kind) else kindFilter.add(kind)
            },
            ownKit = ownKit,
            onOwnKit = { ownKit = it },
            onAutoMute = { level ->
                val updated = tuning.copy(autoMuteAboveDbm = level)
                settings.save(updated)
                tuning = updated
                session().tuning = updated
                ownKit = OwnKit.DECIDED
            },
            stitchLog = stitchLog,
            bars = bars.toList(),
            targets = targets,
            nowMs = nowMs,
            rebaselinePrompt = state.shouldRebaseline && !dismissedRebaseline,
            onDismissRebaseline = { dismissedRebaseline = true },
            onRebaseline = {
                val now = System.currentTimeMillis()
                session().rebaseline(now)
                bars.clear()
                tests += "re-baseline"
                dismissedRebaseline = false
                beginBaseline()
            },
            onCircle = {
                session().beginProbe(Probe.ORBIT, System.currentTimeMillis())
                tests += "circled them"
                goTo(Step.CIRCLE)
            },
            onWalkBy = {
                levelMarked = false
                session().beginProbe(Probe.WALK_BY, System.currentTimeMillis())
                tests += "walked past them"
                goTo(Step.WALK_BY)
            },
            onReview = { goTo(Step.REVIEW) },
            onHold = { acting = it },
            onPromote = { candidate -> targetStore.add(candidate.asTarget(followName)) },
            onKeep = { keeping = it },
            onMine = { ignoreList.add(it.address) },
            onTargets = { goTo(Step.TARGETS) },
            onSettings = { showSettings = true },
            onFinish = {
                library.save(state.asSavedFollow(startedAtMs, followName, tests.toList()))
                library.clearInProgress()
                ScanService.stop(context, ScanService.Mode.FOLLOW)
                FollowRunner.end()
                resumable = false
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
                session().markClosest(System.currentTimeMillis())
                levelMarked = true
                feedback.alert(AlertStyle.BOTH, urgent = false)
            },
            onDone = {
                session().endProbe(System.currentTimeMillis())
                goTo(if (levelMarked) Step.REVIEW else Step.FOLLOWING)
            },
        )

        Step.REVIEW -> Review(
            state = state,
            targets = targets,
            expanded = expandedWalkBy,
            onExpand = { expandedWalkBy = it },
            onPromote = { candidate -> targetStore.add(candidate.asTarget(followName)) },
            onKeep = { keeping = it },
            onHold = { acting = it },
            onNewWalkBy = {
                levelMarked = false
                session().beginProbe(Probe.WALK_BY, System.currentTimeMillis())
                tests += "walked past them"
                goTo(Step.WALK_BY)
            },
            onBack = { goTo(Step.FOLLOWING) },
        )

        Step.HOLD -> Holding(
            state = state,
            log = log,
            geiger = geiger,
            onGeiger = { geiger = it },
            feedback = feedback,
            onUnlock = {
                session().unlock()
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
                runCatching { file.writeText(session().csv()) }
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
    resumable: Boolean,
    onResume: () -> Unit,
    onNew: () -> Unit,
    onTargets: () -> Unit,
    onSettings: () -> Unit,
    onForget: (String) -> Unit,
) {
    if (resumable) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "You left one running",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Picking it up carries on where it stopped. The time the app was away " +
                        "does not count against anybody - nothing was listening, so nobody " +
                        "went quiet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text("Carry on with it")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
        Text(if (resumable) "Start a different one" else "Start a new follow")
    }

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
    scores: Map<String, CandidateScore>,
    kindFilter: List<DeviceKind>,
    onKindFilter: (DeviceKind) -> Unit,
    journal: Journal,
    marks: Int,
    onMark: (Mark) -> Unit,
    onCaseFile: () -> Unit,
    ownKit: OwnKit,
    onOwnKit: (OwnKit) -> Unit,
    onAutoMute: (Int) -> Unit,
    stitchLog: List<Stitch>,
    rebaselinePrompt: Boolean,
    onDismissRebaseline: () -> Unit,
    onRebaseline: () -> Unit,
    onCircle: () -> Unit,
    onWalkBy: () -> Unit,
    onReview: () -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onPromote: (FollowCandidate) -> Unit,
    onKeep: (FollowCandidate) -> Unit,
    onMine: (FollowCandidate) -> Unit,
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
                "${state.watching} heard in total · " +
                    "${state.listeningForMs / 60_000} min of listening" +
                    if (state.blindMs > 30_000L) {
                        " · ${state.blindMs / 60_000} min away"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.bridging) {
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("Following every one of these through its address changes")
                        if (state.stitches > 0) {
                            append(" · ${state.stitches} followed so far")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Rotation following starts at ${state.tuning.bridgeAtOrBelow} left. " +
                        "Below that a wrong link would still be visible; above it, " +
                        "watching a whole crowd for rotations would tangle strangers " +
                        "together.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    // Only what is still in. A dropped device leaves the radar and does not come back,
    // which is what makes this readable: every blip on it is a live candidate, and the ring
    // it sits in is how close it is right now rather than how close it has been on average.
    // Somebody drifting to the back of a carriage moves outward while you watch.
    Spacer(Modifier.height(14.dp))
    RadarPanel(
        targets = state.stillIn.map { candidate ->
            RadarTarget(
                address = candidate.address,
                label = candidate.label ?: candidate.vendor ?: candidate.address.takeLast(8),
                smoothedRssi = candidate.recentRssi,
                flagged = candidate.carried(state.tuning),
                watched = targets.any { it.address.equals(candidate.address, true) },
            )
        },
        selected = null,
        onSelect = {},
        showSelectionCard = false,
        footnote = "Only devices still with them. Something that drops out leaves the " +
            "radar for good, so every blip here is live - and the ring is where it is now, " +
            "not where it has been on average.",
    )

    if (ownKit == OwnKit.UNASKED && state.autoMuting == null) {
        Spacer(Modifier.height(12.dp))
        OwnKitChooser(
            tuning = state.tuning,
            onSkip = { onOwnKit(OwnKit.DECIDED) },
            onAutoMute = onAutoMute,
        )
    }

    state.autoMuting?.let { level ->
        Spacer(Modifier.height(10.dp))
        Text(
            "Muting anything heard above $level dBm. That is a rule about distance rather " +
                "than about ownership - if you end up walking beside them, it can mute " +
                "them. Turn it off in settings once your own kit has been ruled out.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    Spacer(Modifier.height(12.dp))
    MarkRow(onMark = onMark, marks = marks)

    Spacer(Modifier.height(12.dp))
    Section(
        title = "Replay",
        summary = "Scrub back through the walk and see when it narrowed.",
    ) {
        Replay(journal)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCaseFile, modifier = Modifier.fillMaxWidth()) {
            Text("Export the case file")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "One document: what happened, what was found, what argues against it, and the " +
                "count every five seconds. Written so somebody who was not there can " +
                "disagree with it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (stitchLog.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Section(
            title = "Rotations followed",
            summary = "${stitchLog.size} address " +
                (if (stitchLog.size == 1) "change" else "changes") + " carried across.",
        ) {
            Text(
                "Each of these is a device that changed address and was followed to the new " +
                    "one. Every one is also a chance to have been wrong, which is why the " +
                    "ones the app was not sure about were put to you instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            stitchLog.asReversed().forEach { stitch ->
                Field(
                    "${stitch.fromAddress.takeLast(8)} to ${stitch.toAddress.takeLast(8)}",
                    if (stitch.byHand) "you picked it" else "followed automatically",
                )
            }
        }
    }

    if (state.carried.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (state.carried.size == 1) {
                        "One of these is probably yours"
                    } else {
                        "${state.carried.size} of these are probably yours"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sitting in the innermost ring the whole way and never moving. That is " +
                        "what something in your own pocket looks like - earbuds, a watch, a " +
                        "tag - and it survives every test by construction, because it goes " +
                        "everywhere you go. Worth ruling out before you read anything into " +
                        "the rest of the list.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                state.carried.forEach { candidate ->
                    Field(
                        candidate.label ?: candidate.vendor ?: candidate.address,
                        candidate.carriedReason(state.tuning) ?: "",
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Say so once and they are gone from every follow after this, not just " +
                        "this one. Nothing here can work out which devices are yours, and " +
                        "every guess at it either leaves your earbuds at the top of the " +
                        "list forever or quietly removes the target.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { state.carried.forEach(onMine) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.carried.size == 1) {
                            "That one is mine, ignore it"
                        } else {
                            "Those ${state.carried.size} are mine, ignore them"
                        },
                    )
                }
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
        // Only the kinds actually in front of you. Offering "Speaker or TV" when nothing
        // here is one produces an empty list that reads as a broken radio.
        val present = state.stillIn.map { it.kind }.toSet()
            .filter { it in DeviceKind.FILTERABLE }
        if (present.size > 1) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Narrow by what they are",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                present.forEach { kind ->
                    val on = kind in kindFilter
                    FilterChip(
                        selected = on,
                        onClick = { onKindFilter(kind) },
                        label = {
                            Text(
                                "${kind.emoji} ${kind.label} " +
                                    "${state.stillIn.count { it.kind == kind }}",
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "What a device is comes from what it broadcasts, and most of it is a guess. " +
                    "An iPhone and an Apple Watch in a pocket send the same messages under " +
                    "the same company id with the bodies randomized, so both read as " +
                    "\"Apple device\" rather than as one or the other - calling either a " +
                    "phone would invent the fact you are here to establish.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        fun narrow(list: List<FollowCandidate>) =
            if (kindFilter.isEmpty()) list else list.filter { it.kind in kindFilter }

        val arrivedFirst = narrow(state.stillInArrived)
        val wasAlreadyHere = narrow(state.stillInAlreadyHere)

        @Composable
        fun list(candidates: List<FollowCandidate>) {
            candidates.forEach { candidate ->
                val already = targets.any { it.address.equals(candidate.address, true) }
                CandidateCard(
                    candidate = candidate,
                    nowMs = nowMs,
                    tuning = state.tuning,
                    score = scores[candidate.address],
                    // Only the ones actually in the pool and still audible are being
                    // followed through rotations, and only once the field is small enough.
                    watchedForRotation = state.bridging && candidate.stillIn,
                    onClick = { onHold(candidate) },
                    action = "Name and list",
                    onAction = { onKeep(candidate) },
                    secondary = if (already) null else "Target",
                    onSecondary = { onPromote(candidate) },
                    tertiary = "Mine",
                    onTertiary = { onMine(candidate) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Two strengths of claim, and they were one list. When the baseline was taken
        // before the person arrived, everything in the first group was in range at a
        // moment they were not - which is the whole reason for taking a baseline that way,
        // and it was being thrown away by showing them all together.
        if (state.waitedForArrival && arrivedFirst.isNotEmpty()) {
            Text(
                "Arrived after the baseline",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "None of these were here before them. That is the strongest thing this " +
                    "follow knows, so they are first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            list(arrivedFirst)

            if (wasAlreadyHere.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Was already here",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Part of the furniture when you took the baseline, and still with you. " +
                        "Possible, but a weaker claim than the ones above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                list(wasAlreadyHere)
            }
        } else {
            Text(
                if (state.narrowed) "Short list" else "Still with them",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Tap one to hold onto it. Name it and put it on a list to use it in the " +
                    "other experiments, or say it is yours and it leaves for good.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            list(state.stillIn)
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
        title = if (state.walkedBy) {
            "Walk-bys (${state.walkBys.size})"
        } else {
            "Walk past them"
        },
        detail = if (state.walkedBy) {
            "Latest: ${state.passed} of the ones still with them peaked as you passed. Tap " +
                "to see every one, and to do another."
        } else {
            "They stand still; you walk past and stop the same distance away on the far " +
                "side. Whatever is on them rises as you draw level and comes back down. " +
                "This one picks devices out rather than ruling them out."
        },
        onClick = if (state.walkedBy) onReview else onWalkBy,
        emphasis = state.walkedBy,
    )
    ProbeCard(
        title = if (state.orbited) "Circle them again" else "Circle them",
        detail = if (state.orbited) {
            "${state.orbits.size} walked. Latest: ${state.centred} stayed at the same " +
                "distance all the way round. Another lap is scored on its own."
        } else {
            "One slow lap about five paces out. Anything on them stays the same distance " +
                "from you the whole way round; anything across the room does not."
        },
        onClick = onCircle,
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
/**
 * Every walk-by that has been done, newest first, with the traces to check each against.
 *
 * A list rather than one result, because you can do several and the reason for doing a
 * second is almost always that the first came out ambiguous. Showing only the latest would
 * hide the thing you did the second one to compare against.
 *
 * Each run expands to its own ranked table. A verdict you cannot check is an assertion, and
 * a person can tell a clean hill from a mess the thresholds happened to let through in
 * about a second - faster and more reliably than any amount of tuning from me.
 */
@Composable
private fun Review(
    state: FollowState,
    targets: List<TargetDevice>,
    expanded: Int?,
    onExpand: (Int?) -> Unit,
    onPromote: (FollowCandidate) -> Unit,
    onKeep: (FollowCandidate) -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onNewWalkBy: () -> Unit,
    onBack: () -> Unit,
) {
    val runs = state.walkBys.sortedByDescending { it.startedAtMs }

    Text(
        "Walk-bys",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (runs.isEmpty()) {
            "None yet."
        } else {
            "${runs.size} done. The vertical line on each chart is where you tapped, the " +
                "dashed line is the level the rise is measured against, and the shaded ends " +
                "are the two windows it came from."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Button(onClick = onNewWalkBy, modifier = Modifier.fillMaxWidth()) {
        Text(if (runs.isEmpty()) "Do a walk-by" else "Do another walk-by")
    }

    runs.forEach { run ->
        val results = state.walkByResults(run.index)
        val passed = results.count { it.second.score.passed }
        val open = expanded == run.index

        Spacer(Modifier.height(10.dp))
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Walk-by at ${clock(run.startedAtMs)}") {
                    onExpand(if (open) null else run.index)
                },
            colors = CardDefaults.cardColors(
                containerColor = if (passed > 0) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            clock(run.startedAtMs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${run.durationMs(run.endedAtMs ?: run.startedAtMs) / 1000} s " +
                                "walk · ${results.size} devices scored",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (passed == 0) "none passed" else "$passed passed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (!open) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to see the traces",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                if (results.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nothing was heard often enough during this one to have a shape.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    return@Column
                }

                results.take(8).forEach { (candidate, result) ->
                    Spacer(Modifier.height(12.dp))
                    WalkByResultCard(
                        candidate = candidate,
                        result = result,
                        run = run,
                        alreadyTarget = targets.any {
                            it.address.equals(candidate.address, true)
                        },
                        onPromote = { onPromote(candidate) },
                        onKeep = { onKeep(candidate) },
                        onHold = { onHold(candidate) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Grey("Back to the follow", onBack)
}

@Composable
private fun WalkByResultCard(
    candidate: FollowCandidate,
    result: CandidateWalkBy,
    run: ProbeRun,
    alreadyTarget: Boolean,
    onPromote: () -> Unit,
    onKeep: () -> Unit,
    onHold: () -> Unit,
) {
    val score = result.score
    Column {
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
            trail = result.trail,
            score = score,
            startMs = run.startedAtMs,
            endMs = run.endedAtMs ?: score.midAtMs,
        )

        Spacer(Modifier.height(6.dp))
        Field("Rise as you passed", "${score.riseDb.roundToInt()} dB")
        Field("Ends differ by", "${score.symmetryDb.roundToInt()} dB")
        Field("Peak off the mark by", "${score.offsetMs / 1000} s")
        Field("Readings", "${score.packets}")
        Spacer(Modifier.height(4.dp))
        Text(score.describe(), style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Name and list") }
            if (!alreadyTarget) {
                OutlinedButton(onClick = onPromote, modifier = Modifier.weight(1f)) {
                    Text("Target")
                }
            }
            OutlinedButton(onClick = onHold, modifier = Modifier.weight(1f)) { Text("Hold") }
        }
    }
}

/** A wall-clock time, for telling one walk-by from another. */
private fun clock(atMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(atMs))

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
    tuning: FollowTuning = FollowTuning.DEFAULT,
    score: CandidateScore? = null,
    /** True while this device is one the session is following through its address changes. */
    watchedForRotation: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    tertiary: String? = null,
    onTertiary: () -> Unit = {},
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            candidate.label ?: candidate.vendor ?: candidate.address,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (watchedForRotation) {
                            Spacer(Modifier.width(6.dp))
                            RotationTag(candidate.rotations)
                        }
                    }
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
                    candidate.carriedReason(tuning)?.let {
                        Text(
                            "probably yours: $it",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    score?.let {
                        Text(
                            it.headline(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = oddsColor(it.odds),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    score?.let {
                        Text(
                            "${it.points.roundToInt()}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = oddsColor(it.odds),
                        )
                        Text(
                            it.odds.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = oddsColor(it.odds),
                        )
                    }
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
            // Address type and a steadiness figure, because "AA:BB:.." and a percentage is
            // not enough to recognise anything by. A random address that has never changed
            // is a different thing from a fixed one, and a device that has not moved a
            // decibel in ten minutes is a different thing again.
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (candidate.kind != DeviceKind.UNKNOWN) {
                        append(candidate.kind.emoji)
                        append(" ")
                        append(candidate.kind.label)
                        // A guess and a declaration look identical unless one of them says
                        // so, and this one is about to be used to pick a person.
                        if (!candidate.kindCertain) append("?")
                        append(" · ")
                    }
                    append(if (candidate.isRandom) "random address" else "fixed address")
                    append(" · ${candidate.packets} packets")
                    if (candidate.spreadDb < 1_000) {
                        append(" · moved ${candidate.spreadDb.roundToInt()} dB")
                    }
                    if (candidate.rotations > 0) append(" · ${candidate.rotations} rotations")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (action != null || secondary != null || tertiary != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    action?.let { TextButton(onClick = onAction) { Text(it) } }
                    secondary?.let { TextButton(onClick = onSecondary) { Text(it) } }
                    tertiary?.let { TextButton(onClick = onTertiary) { Text(it) } }
                }
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
    geiger: Boolean,
    onGeiger: (Boolean) -> Unit,
    feedback: Feedback,
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

    // The whole point of having committed to one device: you can stop looking at this.
    Spacer(Modifier.height(12.dp))
    GeigerBar(
        recentRssi = target.recentRssi.takeIf { it > -127 },
        silentForMs = state.silentForMs,
        label = target.label ?: target.vendor,
        running = geiger,
        onToggle = onGeiger,
        feedback = feedback,
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

/**
 * The three ways out of the problem that your own pocket is always the best candidate.
 *
 * Offered once, near the start, because every minute it goes unanswered is a minute of
 * evidence built on a list with your earbuds at the top of it.
 */
@Composable
private fun OwnKitChooser(
    tuning: FollowTuning,
    onSkip: () -> Unit,
    onAutoMute: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "What about your own devices?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Whatever is in your own pockets survives every test here by construction. " +
                    "It orbits when you orbit and passes when you pass, because it goes " +
                    "where you go. Left in, it sits at the top of the short list forever " +
                    "and the follow looks like it worked.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Find them as I walk") }
            Text(
                "The patient one. After about ${tuning.carriedAfterMs / 60_000} minutes of " +
                    "walking, anything that has barely moved relative to you is offered up " +
                    "as probably yours, and you say whether it is. Costs nothing and can " +
                    "tell the difference between your pocket and theirs, which the quick " +
                    "way cannot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onAutoMute(tuning.carriedDbm) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mute anything above ${tuning.carriedDbm} dBm now") }
            Text(
                "The quick one, working from the first ten seconds. It is a rule about " +
                    "distance rather than ownership, so it will mute them too if you end " +
                    "up walking beside them. The level is editable in settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Nothing on me, move on")
            }
        }
    }
}

/**
 * The badge on a device the session is following through its address changes.
 *
 * Worth saying out loud on the row rather than only in the headline. Being watched for a
 * rotation is the difference between a device that will be lost the next time its phone
 * changes address and one that will not, and which of those you are looking at changes
 * what a falling count means.
 */
@Composable
private fun RotationTag(rotations: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            if (rotations > 0) "ROTATION ×$rotations" else "ROTATION WATCH",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun oddsColor(odds: Odds) = when (odds) {
    Odds.RULED_OUT -> MaterialTheme.colorScheme.error
    Odds.STILL_HERE -> MaterialTheme.colorScheme.onSurfaceVariant
    Odds.PROBABLE -> MaterialTheme.colorScheme.tertiary
    Odds.STANDOUT -> MaterialTheme.colorScheme.primary
}
