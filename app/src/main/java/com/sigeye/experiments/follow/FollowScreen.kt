package com.sigeye.experiments.follow

import com.sigeye.core.Clock
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.sigeye.core.CurrentTarget
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.FollowLead
import com.sigeye.core.FollowLibrary
import com.sigeye.core.FollowRunner
import com.sigeye.core.IgnoreList
import com.sigeye.core.MyDevices
import com.sigeye.core.Permissions
import com.sigeye.core.SavedFollow
import com.sigeye.core.ScanService
import com.sigeye.core.SweepExport
import com.sigeye.core.Takeaway
import com.sigeye.core.TargetDevice
import com.sigeye.core.TargetStore
import com.sigeye.core.analysis.identity.AskPolicy
import com.sigeye.core.analysis.identity.AskUrgency
import com.sigeye.core.analysis.identity.CandidateWalkBy
import com.sigeye.core.analysis.identity.CaseFile
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowPhase
import com.sigeye.core.analysis.identity.FollowSession
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.core.analysis.identity.CandidateScore
import com.sigeye.core.analysis.identity.Guidance
import com.sigeye.core.analysis.identity.Guide
import com.sigeye.core.analysis.identity.Handoff
import com.sigeye.core.analysis.identity.Journal
import com.sigeye.core.analysis.identity.Mark
import com.sigeye.core.analysis.identity.Move
import com.sigeye.core.analysis.identity.Odds
import com.sigeye.core.analysis.identity.Scoring
import com.sigeye.core.analysis.identity.Stage
import com.sigeye.core.analysis.identity.Stitch
import com.sigeye.core.analysis.identity.Probe
import com.sigeye.core.analysis.identity.ProbeRun
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.DeviceKind
import com.sigeye.core.ble.shape
import com.sigeye.core.sensors.WalkSensor
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

/**
 * Lines of the running log kept on screen.
 *
 * It grew for the length of a follow, one entry per drop, return and rotation. Nobody
 * scrolls back half an hour, and a list nothing ever trims is the shape of every other
 * problem found in this sweep.
 */
private const val LOG_LINES = 60

private const val HUB_TAG = "follow"

/**
 * How many devices the radar will draw.
 *
 * The loudest, because the radar is for watching something approach rather than for
 * counting. Above this it stops being readable and starts being expensive: every blip is a
 * text measure and a trail redraw on every frame.
 */
private const val RADAR_BLIPS = 24
private const val TICK_MS = 1_000L

/** A minute of bars, one a second. */
private const val BARS = 60

/** Long enough that a couple of dropped packets is not a loss. */
internal const val TARGET_LOST_MS = 60_000L

/** Long enough that the number means something before it can be shown big. */
internal const val TAKEAWAY_AFTER_MS = 2 * 60_000L

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
    /**
     * The suite's mode switcher, drawn under this screen's own header.
     *
     * A slot rather than a bar the container draws above everything, so the switcher lands
     * below the title it belongs to instead of above the back button. Empty by default,
     * which is what keeps this screen openable on its own.
     */
    modes: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.FOLLOW, onBack)
        modes()
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
    val myDevices = remember { MyDevices.get(context) }
    val currentTarget = remember { CurrentTarget.get(context) }
    val feedback = remember { Feedback(context) }

    // No permission, works indoors, and only has to answer a coarse question: is somebody
    // carrying this. Standing still is the one case where a still count means the method
    // has stopped rather than the app.
    val walkSensor = remember { WalkSensor(context) }
    val walk by walkSensor.walk.collectAsStateWithLifecycle()

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
    var nowMs by remember { mutableStateOf(Clock.nowMs()) }
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

    /**
     * A frozen copy of the record, taken on the tick.
     *
     * Never the live journal. It is written from the scanning service's thread, and a
     * screen iterating it while that happens is how a follow died halfway through a walk.
     */
    var journal by remember { mutableStateOf(Journal()) }

    /** Which rotation question is on screen, and which have been put off. */
    var asking by remember { mutableStateOf<String?>(null) }
    val deferred = remember { mutableStateListOf<String>() }

    /** When somebody was last stopped by a dialog, so they are not stopped twice over. */
    var lastInterruptMs by remember { mutableLongStateOf(0L) }

    /** Set when they have asked to be left alone for the rest of this run. */
    var askingMuted by remember { mutableStateOf(false) }

    /**
     * Whether everything below the instruction is showing.
     *
     * Closed by default, and this is the whole of the redesign. On a street somebody needs
     * the number, the thing to do, and one button. What was there instead was the count, a
     * chart, a radar, filter chips, an own-kit card, a marks section, a replay, a stitch
     * log and two lists, all at once, at the moment there is least attention to spare.
     */
    var details by remember { mutableStateOf(false) }

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

    val mutedAddresses by ignoreList.addresses.collectAsStateWithLifecycle()
    val myOwn by myDevices.devices.collectAsStateWithLifecycle()

    // Your own kit walks with you by definition, so elimination by walking can never
    // remove it. Left in, it survives every round and ends up looking like the answer.
    // Muting is a preference about a screen; this is a standing fact, and it persists
    // between follows so the same question is not asked every single walk.
    val ignored = mutedAddresses + myOwn.map { it.address }
    val follows by library.follows.collectAsStateWithLifecycle()
    val targets by targetStore.targets.collectAsStateWithLifecycle()
    val pinned by currentTarget.pinned.collectAsStateWithLifecycle()
    val pinnedAddress = pinned?.address ?: state.target?.address
    val latest by rememberUpdatedState(state)
    val currentStep by rememberUpdatedState(step)
    val stepStarted by rememberUpdatedState(stepStartedMs)
    val hereAnswer by rememberUpdatedState(theyAreHere)
    val currentTuning by rememberUpdatedState(tuning)

    fun goTo(next: Step) {
        step = next
        stepStartedMs = Clock.nowMs()
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
        startedAtMs = restored.state(Clock.nowMs()).followStartedAtMs ?: 0L
        theyAreHere = true
        // Whatever gap there was while nothing was listening does not count as silence.
        // Without this, coming back to a follow would find every device dropped at once.
        restored.resume(Clock.nowMs())
        step = when (restored.phase) {
            FollowPhase.HOLDING, FollowPhase.LOST -> Step.HOLD
            FollowPhase.FOLLOWING -> Step.FOLLOWING
            else -> Step.BRIEF
        }
        stepStartedMs = Clock.nowMs()
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
        walkSensor.start()
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            val now = Clock.nowMs()
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
            walkSensor.stop()
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
            val now = Clock.nowMs()
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
                // Only when there are few enough for the pocket test to run at all.
                // Copying two hundred readings for each of three hundred devices twice a
                // second, to feed a function that returns early above twenty, is most of a
                // megabyte of garbage a second for nothing.
                trails = if (next.stillIn.size in 2..Scoring.COMPANION_LIMIT) {
                    session().trails()
                } else {
                    emptyMap()
                },
            ).associateBy { it.address }
            journal = session().journalCopy()

            // Rotations the session followed on its own. The screen's job is to say so and
            // to keep anything holding an address in step - it is not the screen's decision
            // any more, which is the point of having moved it into the session.
            val stitches = session().stitches()
            if (stitches.size > seenStitches) {
                stitches.drop(seenStitches).forEach { stitch ->
                    targetStore.reacquire(stitch.fromAddress, stitch.toAddress, now)
                    log = (listOf(
                        "${stitch.fromAddress} became ${stitch.toAddress}" +
                            if (stitch.byHand) ", you picked it" else ", followed automatically",
                    ) + log).take(LOG_LINES)
                }
                seenStitches = stitches.size
                stitchLog = stitches
                feedback.alert(AlertStyle.BOTH, urgent = false)
            }

            // Only questions somebody stands a chance of answering are allowed to stop
            // them. The rest wait in the tray, where they are exactly as answerable later.
            // See AskPolicy for why this is not a threshold that wanted tuning.
            if (asking == null) {
                next.questions.firstOrNull { question ->
                    question.departure.address !in deferred &&
                        AskPolicy.urgencyOf(
                            departureAddress = question.departure.address,
                            pinnedAddress = pinnedAddress,
                            poolSize = next.stillIn.size,
                            lastInterruptMs = lastInterruptMs,
                            nowMs = now,
                            muted = askingMuted,
                        ) == AskUrgency.INTERRUPT
                }?.let { question ->
                    asking = question.departure.address
                    lastInterruptMs = now
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
                log = (listOf("Back in range") + log).take(LOG_LINES)
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
                myDevices.add(candidate.address, candidate.label ?: candidate.vendor
                    ?: candidate.address)
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
                // Committing to a device in a follow is the moment the rest of the app
                // becomes useful for it, so it pins itself rather than making somebody
                // copy an address out of here and into a filter box.
                currentTarget.pin(
                    address = candidate.address,
                    label = candidate.label,
                    vendor = candidate.vendor,
                    source = followName.ifBlank { "a follow" },
                )
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
                    session().answer(address, to, Clock.nowMs())
                    asking = null
                },
                onNone = {
                    session().answer(address, null, Clock.nowMs())
                    log = (listOf("$name was none of the options, dropped") + log).take(LOG_LINES)
                    asking = null
                },
                onLater = {
                    deferred.add(address)
                    asking = null
                },
                onQuiet = {
                    askingMuted = true
                    asking = null
                    log = (listOf("Interruptions off. Rotations still queue in the tray.") +
                        log).take(LOG_LINES)
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

    // Everything the policy held back, with a count and a reason. Nothing is discarded;
    // this is the difference between a question deferred and a question suppressed.
    val waiting = state.questions.filter { it.departure.address !in deferred }
    if (waiting.isNotEmpty() && asking == null) {
        RotationTray(
            waiting = waiting.size,
            why = AskPolicy.whyWaiting(state.stillIn.size, askingMuted),
            muted = askingMuted,
            onOpen = {
                asking = waiting.first().departure.address
                lastInterruptMs = Clock.nowMs()
            },
            onMute = { askingMuted = it },
        )
        Spacer(Modifier.height(10.dp))
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
                val now = Clock.nowMs()
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
                    session().startFollowing(Clock.nowMs())
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
                session().startFollowing(Clock.nowMs())
                ScanService.start(context, ScanService.Mode.FOLLOW)
                bars.clear()
                goTo(Step.FOLLOWING)
            },
        )

        Step.FOLLOWING -> Following(
            state = state,
            scores = scores,
            kindFilter = kindFilter.toList(),
            journal = journal,
            geiger = geiger,
            onGeiger = { geiger = it },
            feedback = feedback,
            guidance = remember(state.atMs, journal.size, walk.stillForMs) {
                Guide.of(state, journal.counts(), walk.stillForMs.takeIf { walk.available })
            },
            details = details,
            onDetails = { details = it },
            marks = marked,
            onMark = { mark ->
                session().mark(mark, Clock.nowMs())
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
                        journal = journal,
                        scores = scores.values.toList(),
                        stitches = stitchLog,
                    ),
                )
            },
            onKindFilter = { kind ->
                if (kind in kindFilter) kindFilter.remove(kind) else kindFilter.add(kind)
            },
            ownKit = ownKit,
            savedOwnKit = myOwn.size,
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
                val now = Clock.nowMs()
                session().rebaseline(now)
                bars.clear()
                tests += "re-baseline"
                dismissedRebaseline = false
                beginBaseline()
            },
            onCircle = {
                session().beginProbe(Probe.ORBIT, Clock.nowMs())
                tests += "circled them"
                goTo(Step.CIRCLE)
            },
            onWalkBy = {
                levelMarked = false
                session().beginProbe(Probe.WALK_BY, Clock.nowMs())
                tests += "walked past them"
                goTo(Step.WALK_BY)
            },
            onReview = { goTo(Step.REVIEW) },
            onHold = { acting = it },
            onPromote = { candidate -> targetStore.add(candidate.asTarget(followName)) },
            onKeep = { keeping = it },
            onMine = { myDevices.add(it.address, it.label ?: it.vendor ?: it.address) },
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
                session().markClosest(Clock.nowMs())
                levelMarked = true
                feedback.alert(AlertStyle.BOTH, urgent = false)
            },
            onDone = {
                session().endProbe(Clock.nowMs())
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
                session().beginProbe(Probe.WALK_BY, Clock.nowMs())
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
                val file = File(directory, "follow-${Clock.nowMs()}.csv")
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
    addedAtMs = Clock.nowMs(),
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
    guidance: Guidance?,
    geiger: Boolean,
    onGeiger: (Boolean) -> Unit,
    feedback: Feedback,
    details: Boolean,
    onDetails: (Boolean) -> Unit,
    marks: Int,
    onMark: (Mark) -> Unit,
    onCaseFile: () -> Unit,
    ownKit: OwnKit,
    /** How many devices are already on the standing list, so the question is not re-asked. */
    savedOwnKit: Int,
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

            // The elimination is a minute of silence per device, and a minute is a long
            // time to watch a number not move. Saying what is on its way out turns a
            // stalled-looking screen into one that is visibly working.
            Spacer(Modifier.height(8.dp))
            if (state.goingQuiet > 0) {
                Text(
                    "${state.goingQuiet} going quiet" +
                        (state.nextDropInMs?.let { " · next drops in ${it / 1000}s" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    "Every one of these has been heard within the last " +
                        "${state.tuning.dropAfterMs / 1000} seconds. A device has to go " +
                        "that long without a packet before it is out.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

    // The instruction, and the one thing to do about it. This is what somebody standing
    // on a street actually needs, and it used to be nowhere: a number with no context, a
    // still count that reads as a broken app, and eleven cards to work it out from.
    // Following by feel does not have to wait for a commitment. Once the list is short
    // the strongest candidate is worth carrying in a pocket, and waiting until somebody
    // had picked one meant the mode only appeared after the part it would have helped
    // with was over.
    val leader = if (state.stillIn.size <= state.tuning.listableAt) {
        state.target ?: scores.values
            .filter { it.points > 0 }
            .maxByOrNull { it.points }
            ?.let { best -> state.stillIn.firstOrNull { it.address == best.address } }
    } else {
        null
    }
    if (leader != null) {
        Spacer(Modifier.height(12.dp))
        GeigerBar(
            recentRssi = leader.recentRssi.takeIf { it > -127 },
            silentForMs = (state.atMs - leader.lastSeenMs).coerceAtLeast(0L),
            label = leader.label ?: leader.vendor ?: "strongest case",
            running = geiger,
            onToggle = onGeiger,
            feedback = feedback,
        )
    }

    guidance?.let { advice ->
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    advice.urgent -> MaterialTheme.colorScheme.errorContainer
                    advice.stage == Stage.IDENTIFY -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    advice.stage.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    advice.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(advice.expect, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    advice.why,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                // One button, and only when there is something to press. "Keep walking" is
                // an instruction rather than an action, and a button that does nothing is
                // worse than no button.
                val action: (() -> Unit)? = when (advice.move) {
                    Move.WALK_BY -> onWalkBy
                    Move.ORBIT -> onCircle
                    Move.OWN_KIT -> ({ state.carried.forEach(onMine) })
                    Move.HOLD -> ({ onDetails(true) })
                    Move.WAIT, Move.WALK, Move.GET_MOVING -> null
                }
                action?.let {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (advice.move) {
                                Move.OWN_KIT -> "Those ${state.carried.size} are mine"
                                Move.HOLD -> "Show the list"
                                else -> advice.move.label
                            },
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Grey(if (details) "Hide the detail" else "Show the detail", { onDetails(!details) })

    if (details) {
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
        // The loudest only. Three hundred blips is not a radar, it is a grey disc, and it
        // costs a text measure and a trail redraw each every frame - which on a phone already
        // holding a scan is where the stutter comes from.
        RadarPanel(
            targets = state.stillIn.sortedByDescending { it.recentRssi }.take(RADAR_BLIPS)
                .map { candidate ->
                RadarTarget(
                    address = candidate.address,
                    label = candidate.label ?: candidate.vendor ?: candidate.address.takeLast(8),
                    smoothedRssi = candidate.recentRssi,
                    // How far through the drop-off this one is. The elimination is the
                    // measurement here, and it used to happen entirely off screen.
                    fading = (
                        (state.atMs - candidate.lastSeenMs).toFloat() /
                            state.tuning.dropAfterMs
                        ).coerceIn(0f, 1f),
                    flagged = candidate.carried(state.tuning),
                    watched = targets.any { it.address.equals(candidate.address, true) },
                )
            },
            selected = null,
            onSelect = {},
            showSelectionCard = false,
            footnote = "The ring around each blip is its drop-off running down: a device has " +
            "to go ${state.tuning.dropAfterMs / 1000} seconds without a packet before it " +
            "is out, and it dims as it gets there. " +
            "Only devices still with them. Something that drops out leaves the " +
                "radar for good, so every blip here is live - and the ring is where it is now, " +
                "not where it has been on average.",
        )

        if (ownKit == OwnKit.UNASKED && state.autoMuting == null && savedOwnKit == 0) {
            Spacer(Modifier.height(12.dp))
            OwnKitChooser(
                tuning = state.tuning,
                onSkip = { onOwnKit(OwnKit.DECIDED) },
                onAutoMute = onAutoMute,
            )
        }
        if (savedOwnKit > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "$savedOwnKit of your own devices are on the saved list and were left out " +
                    "of this follow before it started.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
