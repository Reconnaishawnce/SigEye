package com.sigeye.core.analysis.identity

import com.sigeye.core.analysis.Stats
import com.sigeye.core.ble.DeviceKind
import com.sigeye.core.ble.DeviceKinds
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Where a follow has got to. */
enum class FollowPhase(val label: String) {
    /** Nothing started. */
    IDLE("Not started"),

    /** Listening to everything, before the follow proper begins. */
    BASELINE("Baseline"),

    /** Following. The pool is fixed and devices drop out of it as they go quiet. */
    FOLLOWING("Following"),

    /** One device has been chosen and is being held onto. */
    HOLDING("Holding"),

    /** The chosen device has gone quiet and is being waited for. */
    LOST("Lost"),
}

/**
 * A measurement taken during a follow, rather than a stage of one.
 *
 * The distinction matters and getting it wrong was the design mistake in the first version.
 * Following somebody is one continuous thing: you start, you walk, and the list shrinks. It
 * is not a sequence of legs, and asking somebody to press a button every few minutes while
 * they tail a person is asking them to do the one thing that would give them away.
 *
 * A circle and a walk-by are different: they genuinely have a start, a middle and an end,
 * because they are geometry rather than duration. They run *inside* a follow without
 * interrupting it, and the follow carries on underneath.
 */
enum class Probe(val label: String) {
    ORBIT("Circle them"),
    WALK_BY("Walk past them"),
}

/**
 * One run of a probe.
 *
 * Numbered, because you can do several. The first version allowed one of each on the
 * grounds that two laps at different radii are not the same measurement - which is true,
 * and the mistake was merging them rather than running them. Scored separately they are two
 * measurements, and two is better than one: a walk-by that came out ambiguous is worth
 * repeating from the other direction, and the honest thing to do with both results is show
 * both.
 */
data class ProbeRun(
    val index: Int,
    val kind: Probe,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** For a walk-by: when the person said they drew level. */
    val midAtMs: Long? = null,
) {
    val running: Boolean get() = endedAtMs == null

    /** Scored only once it is over and, for a walk-by, only once the middle was marked. */
    val complete: Boolean
        get() = endedAtMs != null && (kind != Probe.WALK_BY || midAtMs != null)

    fun durationMs(nowMs: Long): Long = (endedAtMs ?: nowMs) - startedAtMs
}

/** What one circle said about one device. */
data class CandidateOrbit(val probeIndex: Int, val score: OrbitScore)

/** What one walk-by said about one device, and the readings it said it from. */
data class CandidateWalkBy(
    val probeIndex: Int,
    val score: WalkByScore,
    val trail: List<Pair<Long, Int>>,
)

/**
 * Every number a follow is allowed to be argued with about.
 *
 * All of these are judgement calls rather than physics, so all of them are settings. The
 * defaults are what I would start from; the one that matters most is [dropAfterMs], because
 * it is the whole elimination and it trades the same thing both ways - shorter loses real
 * targets to a body shadow, longer keeps half the street in the list.
 */
data class FollowTuning(
    /** How long the opening census runs. */
    val baselineMs: Long = 35_000L,

    /**
     * How long a device may go unheard before it counts as having dropped out.
     *
     * A minute. Long enough to ride out a phone in a back pocket with a body between it and
     * you, short enough that a shop you walked past is gone before you reach the corner.
     */
    val dropAfterMs: Long = 60_000L,

    /** How long a circle takes. */
    val circleMs: Long = 75_000L,

    /** At or below this, the list is short enough to watch each one for a rotation. */
    val shortlistMax: Int = 5,

    /** At or below this, the list is short enough to be worth saving somewhere. */
    val listableAt: Int = 15,

    /**
     * At or below this many survivors, start following them through their rotations.
     *
     * Not a battery decision. The scan is already running and the stitching is arithmetic
     * on packets that have already arrived, so it would cost nothing to do this from the
     * first second. The cost is statistical: every device being watched for a rotation is
     * another chance to link two strangers, and doing it across a whole food court would
     * produce a confident tangle. Fifteen is the point where the survivors are few enough
     * that a wrong link would be visible rather than buried.
     */
    val bridgeAtOrBelow: Int = 15,

    /**
     * Mute anything heard above this level outright, or null to leave it alone.
     *
     * Off by default and it should stay off unless somebody turns it on, because it is a
     * rule about geometry rather than about ownership: it mutes whatever is closest to the
     * phone, and if you are walking beside the person you are following, that can be them.
     * What makes it worth having anyway is that it works in the first ten seconds, where
     * the patient test needs three minutes of walking before it can say anything.
     */
    val autoMuteAboveDbm: Int? = null,

    /** How long to let a follow flounder before suggesting a fresh baseline. */
    val rebaselineAfterMs: Long = 5 * 60_000L,

    /** Below this many packets, a device has not been heard from enough to count. */
    val minPackets: Int = 3,

    /** How long a target may be silent before it is called lost rather than quiet. */
    val lostAfterMs: Long = 45_000L,

    /**
     * Above this, a device is close enough to be on you rather than on them.
     *
     * Deliberately strict. Something that sits this loud for the whole of a walk is almost
     * certainly in your own pocket or bag - the earbuds you forgot about, a watch, a tag -
     * and calling it out saves it occupying a place on the short list forever. Strict
     * because the bad failure is the other way round: telling somebody their actual target
     * is their own kit would end the follow.
     */
    val carriedDbm: Int = -55,

    /**
     * How steady a device has to be across a whole follow to be called yours.
     *
     * This is the better half of the test and the absolute level is the weaker one. Walk
     * four blocks with something in your own bag and the geometry between it and the phone
     * never changes, so its level barely moves - a handful of decibels, all of it fading
     * rather than distance. Two people walking together do not manage that: you drift apart
     * and back, you turn corners at slightly different radii, your own arm passes between
     * the two of you, and that is fifteen or twenty decibels over a few minutes.
     *
     * So a device that has been with you for minutes *and* has barely moved is almost
     * certainly on you rather than on them.
     */
    val carriedSpreadDb: Double = 9.0,

    /** How long a device has to have been around before its steadiness means anything. */
    val carriedAfterMs: Long = 3 * 60_000L,
) {
    companion object {
        val DEFAULT = FollowTuning()
    }
}

/**
 * What a circle around somebody said about one device.
 *
 * Walk a slow circle a few paces out and the geometry does the work. A device on the person
 * at the centre stays the same distance from you the whole way round, so it is heard in
 * every arc and its level barely moves. A device across the room is near you on one side of
 * the circle and far on the other, so its level swings and it often drops out entirely for
 * an arc or two.
 *
 * Three separate things have to be true, and each one rules out a different impostor:
 *
 * - **Heard in every arc.** Anything that misses an arc was shadowed or out of range from
 *   somewhere on the circle, and nothing on the person can be.
 * - **Loud.** A device forty metres away is also flat, because walking five paces across a
 *   forty-metre baseline changes nothing. Loudness is what separates flat-and-close from
 *   flat-and-far.
 * - **Flat.** The level itself, spread between the tenth and ninetieth percentile.
 *
 * **What this does not rule out, and the screen has to say so.** Anything else the person
 * is carrying passes too - their watch, their earbuds, their card. That is not a failure,
 * those are also them. A fixed object at the exact centre of the circle passes as well,
 * which is why the instruction is to circle a person rather than a table. And the person's
 * own body absorbs a few dB of their phone's signal from whichever side it is on, so a
 * genuine target is never perfectly flat - the threshold is loose on purpose.
 */
data class OrbitScore(
    val arcsHeard: Int,
    val arcsTotal: Int,
    val packets: Int,
    val meanRssi: Double,
    /** Tenth to ninetieth percentile of the level during the circle, in dB. */
    val spreadDb: Double,
) {
    val continuous: Boolean get() = arcsTotal > 0 && arcsHeard >= arcsTotal

    val near: Boolean get() = meanRssi >= NEAR_DBM

    /**
     * Too few packets to have a shape at all.
     *
     * Two readings seven seconds apart are not flat, they are sparse, and a spread of zero
     * computed from them would put a device that was barely there at the top of the list.
     */
    val sparse: Boolean get() = packets < MIN_PACKETS

    val flat: Boolean get() = !sparse && spreadDb <= FLAT_DB

    val centred: Boolean get() = continuous && near && flat

    fun describe(): String = when {
        !continuous -> "dropped out for ${arcsTotal - arcsHeard} of $arcsTotal arcs"
        sparse -> "only $packets packets round the whole circle"
        !near -> "heard all the way round but faint, ${meanRssi.toInt()} dBm"
        !flat -> "swung ${spreadDb.toInt()} dB round the circle, so it is off to one side"
        else -> "stayed within ${spreadDb.toInt()} dB all the way round"
    }

    companion object {
        /**
         * Loud enough to be on the person you are walking round rather than across the room.
         *
         * Five paces is roughly four metres, and a phone advertising at its usual power
         * lands well above this from there. Set low rather than tight: an RPA in a back
         * pocket with a body in the way is a genuinely weak signal, and losing the real
         * target is a worse failure here than keeping an extra candidate.
         */
        const val NEAR_DBM = -78.0

        /**
         * How much the level may swing and still count as the same distance.
         *
         * Generous, because the person turning puts their own torso between the phone and
         * you for part of the circle, and that alone is worth the better part of ten dB.
         */
        const val FLAT_DB = 14.0

        /**
         * How long an arc is.
         *
         * A comfortable circle at five paces out takes something like a minute, so this
         * cuts it into about eight pieces - enough that missing one is a real gap rather
         * than a dropped packet, and few enough that a device advertising once a second
         * has several chances in each.
         */
        const val ARC_MS = 7_500L

        /** Below this, a device is sparse rather than flat. */
        const val MIN_PACKETS = 6
    }
}

/** Reads a nullable long, treating JSON null and a missing key the same way. */
private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

/** How much of a new reading goes into the smoothed level. */
private const val RECENT_ALPHA = 0.3

/** Below this many readings there is no "the whole time" to speak of. */
private const val CARRIED_MIN_PACKETS = 30

/** How much of the time a device has to be pocket-loud before it is called yours. */
private const val CARRIED_FRACTION = 0.85

/**
 * What walking in after the baseline is worth.
 *
 * Large, because when the baseline was taken without the person it is the strongest single
 * thing the follow knows - much stronger than having survived a few more minutes than
 * something else. It was one point, which put an arrival level with the lamp posts.
 */
private const val ARRIVAL_WEIGHT = 10

/** How many levels are kept per device before the list is halved. */
private const val LEVELS_CAP = 512

/**
 * How many recent readings to keep per device, in time order.
 *
 * Only the closing twenty seconds are ever read, and packets arrive as often as six a
 * second, so a couple of hundred covers it several times over without holding a whole
 * half-hour walk in memory for every device on a concourse.
 */
private const val RECENT_CAP = 200

/** Gaps kept per device, which is plenty for a low-percentile interval estimate. */
private const val GAPS_CAP = 120

/**
 * Gaps longer than this are not one advertising interval.
 *
 * Everything advertises faster than two seconds when it is advertising at all. A longer gap
 * is a packet the scanner missed, or a corner, and letting those into the estimate measures
 * the scanner rather than the device.
 */
private const val GAP_CAP_MS = 2_000L

/**
 * Packets a device must have sent before the close-range mute will fire on it.
 *
 * One loud reading is somebody walking past with a phone in their hand. Muting on that
 * removes a stranger for the rest of the follow, silently.
 */
private const val AUTO_MUTE_PACKETS = 12

/**
 * How soon after the baseline a follow has to start for the target to have been present.
 *
 * Anything longer and the operator spent time waiting at a door, which is the other case
 * and the one where an arrival is the strongest evidence there is.
 */
private const val TARGET_PRESENT_MS = 10_000L

/**
 * How long a follow runs before rotations are chased at all.
 *
 * Long enough for the pool to be real. In the first seconds there is nothing in it, which
 * makes the survivor count trivially small and every device in the street an unexplained
 * arrival - so the rotation logic opened at the one moment it had least to go on.
 */
private const val BRIDGE_AFTER_MS = 90_000L

/** Silence past which a device counts as on its way out rather than merely between packets. */
private const val QUIET_AFTER_MS = 12_000L

/**
 * Drop-off for a device that was already fading when it went quiet.
 *
 * Under half the ordinary one. There is nothing to wait for: the signal was receding, the
 * silence is explained, and the only thing the remaining thirty-five seconds buys is a
 * screen that appears not to be working.
 */
private const val FADED_DROP_MS = 25_000L

/**
 * How many readings of each trail survive being saved.
 *
 * Four hundred is well over a minute at one a second, which is longer than any probe, so in
 * practice nothing is lost. The cap exists so that a circle walked in a busy station with
 * forty devices in range cannot turn one saved follow into a file worth megabytes.
 */
private const val TRAIL_CAP = 400

/** One device a follow is considering. */
/** One rotation followed, and whether the app or a person decided it. */
data class Stitch(
    val fromAddress: String,
    val toAddress: String,
    val atMs: Long,
    /** True when somebody picked it out of the options rather than the app taking it. */
    val byHand: Boolean,
)

data class FollowCandidate(
    val address: String,
    val label: String?,
    val vendor: String?,
    val isRandom: Boolean,
    /**
     * What sort of thing this is, worked out from the whole advertisement rather than one
     * packet. Only ever a guess, and [kindCertain] says how much of one.
     */
    val kind: DeviceKind = DeviceKind.UNKNOWN,
    /** True only when the device declared its own kind in the field made for it. */
    val kindCertain: Boolean = false,
    val packets: Int,
    val meanRssi: Double,
    /**
     * Level now rather than on average, so a blip on a radar moves as somebody approaches.
     *
     * Exponentially weighted, because an unsmoothed reading twitches several dB between
     * consecutive packets with nothing moving, and a radar blip that jitters is a radar
     * blip nobody can read.
     */
    val recentRssi: Double,
    /** What fraction of its readings were loud enough to be in your own pocket. */
    val closeFraction: Double,
    /**
     * Tenth to ninetieth percentile of every reading of this device, in dB.
     *
     * The one number that separates something in your own bag from something in somebody
     * else's pocket. Yours cannot move relative to you; theirs cannot help it.
     */
    val spreadDb: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Addresses this device has worn during the follow, oldest first. */
    val addresses: List<String>,
    /**
     * True when this device was in range at the moment the follow started.
     *
     * Fixed then and never added to. The target was present when you started following, so
     * the pool is closed at that instant; a shop beacon met half a mile later did not come
     * with you and letting it in is how a falling number starts climbing again.
     */
    val inPool: Boolean,
    /** When it went quiet for longer than the drop-off, or null while it is still in. */
    val droppedAtMs: Long? = null,
    /** When it was heard again after being dropped. Notable rather than a reinstatement. */
    val returnedAtMs: Long? = null,
    /** True when it was not audible during the baseline and turned up afterwards. */
    val arrived: Boolean = false,
    /** Every circle walked, oldest first. */
    val orbits: List<CandidateOrbit> = emptyList(),
    /** Every walk-by done, oldest first. */
    val walkBys: List<CandidateWalkBy> = emptyList(),
) {
    val stillIn: Boolean get() = inPool && droppedAtMs == null

    /**
     * The most recent result of each kind, for everywhere that wants one number.
     *
     * The newest rather than the best, deliberately. A second walk-by is usually done
     * because the first was unconvincing, and quietly reporting whichever came out better
     * would turn "try it again" into "keep trying until it passes".
     */
    val orbit: OrbitScore? get() = orbits.lastOrNull()?.score

    val walkBy: WalkByScore? get() = walkBys.lastOrNull()?.score

    /** Passed at least one of however many were run. */
    val passedAnyWalkBy: Boolean get() = walkBys.any { it.score.passed }

    val passedAnyOrbit: Boolean get() = orbits.any { it.score.centred }

    /**
     * Almost certainly something you are carrying rather than something they are.
     *
     * This is the biggest single source of wrong answers in a real follow, and it shows up
     * hardest in the case that ought to be easiest: walk four empty blocks alone and five
     * devices come with you, because five of the things in your own pockets came with you.
     * They survive every test by construction - they go everywhere you go - so nothing in
     * the elimination can ever remove them.
     *
     * Two ways in, because they catch different things.
     *
     * **It never moved.** The strong one. Something in your own bag keeps a fixed geometry
     * with the phone, so its level barely wanders across a whole walk. Somebody walking
     * beside you cannot manage that: you drift apart and back, you round corners at
     * different radii, an arm passes between you. That is worth fifteen or twenty decibels
     * over a few minutes against a handful for your own kit.
     *
     * **It is in your pocket.** The weaker one, kept because it is fast - a device sitting
     * at pocket level from the first minute does not need three minutes of steadiness to be
     * obvious, and waiting for them would leave it at the top of the list meanwhile.
     *
     * Neither is proof, which is why this flags rather than removes. The one thing that is
     * proof is you saying so, and that is what the ignore list is for.
     */
    fun carried(tuning: FollowTuning): Boolean =
        neverMoved(tuning) || inYourPocket(tuning)

    /** Steady enough, for long enough, to have a fixed geometry with this phone. */
    fun neverMoved(tuning: FollowTuning): Boolean =
        packets >= CARRIED_MIN_PACKETS &&
            heldForMs(lastSeenMs) >= tuning.carriedAfterMs &&
            spreadDb <= tuning.carriedSpreadDb

    /** Loud enough, often enough, to be on your person right now. */
    fun inYourPocket(tuning: FollowTuning): Boolean =
        packets >= CARRIED_MIN_PACKETS &&
            closeFraction >= CARRIED_FRACTION &&
            recentRssi >= tuning.carriedDbm

    /** Why it was flagged, so the screen can say rather than assert. */
    fun carriedReason(tuning: FollowTuning): String? = when {
        neverMoved(tuning) -> "moved ${spreadDb.toInt()} dB in " +
            "${heldForMs(lastSeenMs) / 60_000} minutes, which is a fixed distance from you"
        inYourPocket(tuning) -> "pocket-loud for " +
            "${(closeFraction * 100).toInt()}% of the follow"
        else -> null
    }

    val rotations: Int get() = (addresses.size - 1).coerceAtLeast(0)

    /** How long it stayed with you before dropping, or how long it has stayed so far. */
    fun heldForMs(nowMs: Long): Long = ((droppedAtMs ?: nowMs) - firstSeenMs).coerceAtLeast(0L)

    /**
     * How much this candidate is worth, in one number.
     *
     * Time is the base of it, because in a continuous follow the only thing that separates
     * a device from the rest of a street is that it kept answering while the street did
     * not. The probes add to that: passing a walk-by is worth more than passing a circle,
     * because a circle says a device is somewhere near the middle of a lap while a walk-by
     * says you drew level with it at a moment you chose.
     */
    fun weight(nowMs: Long): Int =
        (heldForMs(nowMs) / 60_000L).toInt() +
            (if (stillIn) 3 else 0) +
            (if (passedAnyOrbit) 1 else 0) +
            (if (passedAnyWalkBy) 2 else 0) +
            // Heavy, and it used to be worth one point. When the baseline was taken before
            // the person arrived, walking in *is* the finding - the whole reason for taking
            // a baseline without them is that whoever turns up afterwards is a far shorter
            // list than the building. Ranking an arrival level with the furniture threw
            // that away at the last step.
            (if (arrived) ARRIVAL_WEIGHT else 0)

    fun describe(): String = buildString {
        if (arrived) append("arrived after the baseline, ")
        orbit?.let { append("${it.describe()}, ") }
        walkBy?.let { append("${it.describe()}, ") }
        if (rotations > 0) {
            append("followed through $rotations rotation")
            if (rotations > 1) append("s")
            append(", ")
        }
        append(if (stillIn) "still with you" else "dropped out")
    }
}

/**
 * What the follow can honestly say right now.
 *
 * [stillIn] is the whole finding, and it is a numerator with [watching] as its denominator.
 * Forty still in after standing in a lobby means nothing at all - everybody in the lobby is
 * still in. Two still in after a mile of walking is a different statement, and the pair of
 * numbers is what lets a reader tell the two apart.
 */
data class FollowState(
    val phase: FollowPhase = FollowPhase.IDLE,
    val atMs: Long = 0L,
    val candidates: List<FollowCandidate> = emptyList(),
    /** Everything heard at any point. Climbs forever, and is meant to. */
    val watching: Int = 0,
    val baselineEndedAtMs: Long? = null,
    val followStartedAtMs: Long? = null,
    val probes: List<ProbeRun> = emptyList(),
    val target: FollowCandidate? = null,
    val silentForMs: Long = 0L,
    val rhythm: Rhythm? = null,
    val expectedReturnMs: Long? = null,
    val rotationChangesAtMs: List<Long> = emptyList(),
    val tuning: FollowTuning = FollowTuning.DEFAULT,
    /** How long this follow spent not listening, so the screen can admit to the gap. */
    val blindMs: Long = 0L,

    /**
     * Devices that went quiet in a way that looks like a rotation, where the app is not
     * willing to pick the successor on its own.
     *
     * These are the moments a follow is won or lost, and the operator is standing there
     * watching the person while the app is looking at packets. Asking is not a failure.
     */
    val questions: List<Handoff.Ask> = emptyList(),

    /** Whether the survivors are few enough to be followed through their rotations. */
    val bridging: Boolean = false,

    /** How many rotations have been followed so far this session. */
    val stitches: Int = 0,

    /**
     * Devices that have gone quiet but are not out yet.
     *
     * The drop-off is the whole elimination and it is invisible: a device stops being heard
     * and then nothing happens on screen for a minute. Somebody watching a still number
     * assumes it is stuck.
     */
    val goingQuiet: Int = 0,

    /** How long until the next one is retired, or null when nothing is on its way out. */
    val nextDropInMs: Long? = null,

    /** The level above which devices are being muted outright, or null when they are not. */
    val autoMuting: Int? = null,
) {
    /** How many were in range when the follow started. The number that falls from here. */
    val poolSize: Int get() = candidates.count { it.inPool }

    /** Still with you. The big number, and it can only go down. */
    val stillIn: List<FollowCandidate> get() = candidates.filter { it.stillIn }

    /** Dropped out, newest first, so the last thing to go is at the top. */
    val dropped: List<FollowCandidate>
        get() = candidates.filter { it.inPool && it.droppedAtMs != null }
            .sortedByDescending { it.droppedAtMs }

    /**
     * Dropped and then heard again.
     *
     * Worth saying out loud rather than quietly reinstating. A device that vanished for
     * two minutes and came back was not with you in between - but it is also not nothing,
     * because the commonest way that happens is walking a loop past the same fixed thing
     * twice, and the second commonest is the real target having been round a corner.
     */
    val returned: List<FollowCandidate> get() = candidates.filter { it.returnedAtMs != null }

    val arrivals: List<FollowCandidate> get() = candidates.filter { it.arrived }

    /**
     * Still with you, split by whether they walked in after the baseline.
     *
     * Two different strengths of claim and they should not be one list. If the baseline was
     * taken before the person arrived, everything in the first group was in range at a
     * moment they were not, and everything in the second was already part of the furniture.
     */
    val stillInArrived: List<FollowCandidate> get() = stillIn.filter { it.arrived }

    val stillInAlreadyHere: List<FollowCandidate> get() = stillIn.filterNot { it.arrived }

    /** True when the baseline was taken with the target out of the room. */
    val waitedForArrival: Boolean
        get() = baselineEndedAtMs != null &&
            followStartedAtMs != null &&
            followStartedAtMs > baselineEndedAtMs + 1_000L

    /** Still with you because it is in your own bag, rather than because it is on them. */
    val carried: List<FollowCandidate> get() = stillIn.filter { it.carried(tuning) }

    val orbits: List<ProbeRun> get() = probes.filter { it.kind == Probe.ORBIT && it.complete }

    val walkBys: List<ProbeRun> get() = probes.filter { it.kind == Probe.WALK_BY && it.complete }

    val orbited: Boolean get() = orbits.isNotEmpty()

    val walkedBy: Boolean get() = walkBys.isNotEmpty()

    val runningProbe: ProbeRun? get() = probes.lastOrNull { it.running }

    /** Still with you, and the latest circle agreed. */
    val centred: Int get() = stillIn.count { it.orbit?.centred == true }

    /** Still with you, and the latest walk-by picked it out. */
    val passed: Int get() = stillIn.count { it.walkBy?.passed == true }

    /** What one particular walk-by said, ranked, for reviewing it. */
    fun walkByResults(probeIndex: Int): List<Pair<FollowCandidate, CandidateWalkBy>> =
        candidates
            .mapNotNull { candidate ->
                candidate.walkBys.firstOrNull { it.probeIndex == probeIndex }
                    ?.let { candidate to it }
            }
            .sortedWith(
                compareByDescending<Pair<FollowCandidate, CandidateWalkBy>> {
                    it.second.score.passed
                }.thenByDescending { it.second.score.riseDb },
            )

    /** Short enough to watch each one for an address change. */
    val shortlist: List<FollowCandidate>
        get() = stillIn.takeIf { it.size in 1..tuning.shortlistMax }.orEmpty()

    val narrowed: Boolean get() = shortlist.isNotEmpty()

    /** Short enough to be worth saving somewhere, even if not yet a short list. */
    val listable: Boolean get() = stillIn.size in 1..tuning.listableAt

    val runningForMs: Long
        get() = followStartedAtMs?.let { (atMs - it).coerceAtLeast(0L) } ?: 0L

    /** Time actually spent listening, which is what the elimination is measured in. */
    val listeningForMs: Long get() = (runningForMs - blindMs).coerceAtLeast(0L)

    /**
     * True when this has been running a while and has not got anywhere.
     *
     * Almost always one thing: the target changed address partway through, so the device
     * you were narrowing towards stopped existing and its replacement was never in the
     * pool. Starting the baseline again is the fix, and nobody works that out on their own
     * while staring at a list that will not shrink.
     */
    val shouldRebaseline: Boolean
        get() = followStartedAtMs != null &&
            runningForMs >= tuning.rebaselineAfterMs &&
            !listable

    /** How much the follow has actually narrowed things down. */
    fun narrowing(): String = when {
        watching == 0 -> "Nothing heard yet."
        followStartedAtMs == null -> "$watching devices in range. The follow has not started."
        stillIn.size == poolSize -> "${stillIn.size} of $poolSize still with you - which is " +
            "all of them, so this has not narrowed anything yet."
        else -> "${stillIn.size} of $poolSize still with you."
    }
}

/**
 * Narrowing a street down to the one device that is travelling with somebody.
 *
 * The method is elimination, and it is continuous. Take a baseline where you are, start
 * following, and every device that stops answering for longer than the drop-off falls out
 * of the list and stays out. Stand still together and nothing falls out, because nothing has
 * gone anywhere - useless, and the screen says so. Walk half a mile and almost everything
 * does: the shops fall away, the parked cars fall away, the other passengers get off. What
 * is left is a very short list, and it contains the phone in the other person's pocket.
 *
 * **Dropping is permanent.** A device that goes quiet for a minute while you cover a quarter
 * of a mile did not come with you, and letting it back in when it reappears would undo the
 * only claim this makes. Reappearances are recorded and reported rather than acted on,
 * because they mean something in their own right - usually a loop walked past the same fixed
 * thing twice.
 *
 * **The pool closes when the follow starts.** Something first heard halfway down the street
 * is not something that came with you. It counts in the denominator and nowhere else.
 *
 * The honest output is that short list and its denominator, never a single name. Two out of
 * two hundred after three miles is a strong claim; forty out of two hundred after standing
 * in a lobby is no claim at all, and the difference has to be visible or the screen is just
 * asserting things.
 *
 * Rotation is handled by [Following], with its refusals intact - a candidate whose address
 * changes is followed across only when the evidence is unambiguous, and dropped otherwise
 * rather than guessed at.
 *
 * Pure and Android-free.
 */
class FollowSession(var tuning: FollowTuning = FollowTuning.DEFAULT) {

    private class Tracked(
        var label: String?,
        var vendor: String?,
        val isRandom: Boolean,
        val firstSeenMs: Long,
    ) {
        var lastSeenMs: Long = firstSeenMs
        var packets: Int = 0
        var rssiTotal: Double = 0.0
        var recentRssi: Double = 0.0
        var closeReadings: Int = 0
        var bestRssi: Int = -127

        /**
         * The structure of what it broadcasts, which is what survives a rotation.
         *
         * The most distinctive one ever seen rather than the latest. Advertisements
         * alternate: the same device sends a full packet carrying a name and services, and
         * then a bare one carrying almost nothing, and fingerprinting against whichever
         * arrived last would compare a device's rich packet to another's empty one.
         */
        var shape: AdvertShape = AdvertShape()

        /**
         * Gaps between consecutive packets, for the advertising interval.
         *
         * The interval is a firmware constant that no privacy scheme touches, which makes
         * it one of the few things that reads the same either side of a rotation.
         */
        val gaps: MutableList<Long> = mutableListOf()

        /**
         * Time and level over the last little while, for reading how it ended.
         *
         * Separate from [levels], which is the whole follow thinned for a spread. This one
         * has to stay dense and in order, because the question it answers is whether the
         * last twenty seconds were flat or falling.
         */
        val recent: MutableList<Pair<Long, Int>> = mutableListOf()

        /**
         * Every level this device has been heard at, thinned once it gets long.
         *
         * Kept because the spread across the whole follow is the best single thing that
         * separates your own kit from theirs, and a spread needs the distribution rather
         * than a running mean. Thinned by halving rather than by dropping the oldest, so
         * what survives still covers the whole walk instead of only the end of it.
         */
        val levels: MutableList<Int> = mutableListOf()

        fun record(atMs: Long, rssi: Int) {
            recent.add(atMs to rssi)
            // Only the closing window is ever read, and a follow is thirty minutes long.
            while (recent.size > RECENT_CAP) recent.removeAt(0)
            levels.add(rssi)
            if (levels.size > LEVELS_CAP) {
                val thinned = levels.filterIndexed { index, _ -> index % 2 == 0 }
                levels.clear()
                levels.addAll(thinned)
            }
        }

        fun spreadDb(): Double {
            if (levels.size < CARRIED_MIN_PACKETS) return Double.MAX_VALUE
            val sorted = levels.map { it.toDouble() }.sorted()
            return Stats.percentile(sorted, 0.9) - Stats.percentile(sorted, 0.1)
        }
        val addresses: MutableList<String> = mutableListOf()

        var inPool: Boolean = false
        var droppedAtMs: Long? = null
        var returnedAtMs: Long? = null
        var arrived: Boolean = false

        /** Per circle: which arcs this device was heard in, and at what level. */
        val orbitArcs: MutableMap<Int, MutableSet<Int>> = mutableMapOf()
        val orbitRssi: MutableMap<Int, MutableList<Int>> = mutableMapOf()

        /** Per walk-by: every reading, in time order. */
        val walkByTrail: MutableMap<Int, MutableList<Pair<Long, Int>>> = mutableMapOf()

        val meanRssi: Double get() = if (packets == 0) -127.0 else rssiTotal / packets

        /** What this device looks like to the fingerprinter, right now. */
        fun identity(address: String): Identity {
            val base = Fingerprint.baseIntervalMs(gaps)
            return Identity(
                address = address,
                shape = shape,
                isRandom = isRandom,
                firstSeenMs = firstSeenMs,
                lastSeenMs = lastSeenMs,
                packets = packets,
                medianGapMs = base,
                recentRssi = recentRssi,
                bestRssi = bestRssi,
                intervalJitter = Fingerprint.intervalJitter(gaps, base),
                rssiSpread = spreadDb().takeIf { it != Double.MAX_VALUE } ?: 0.0,
            )
        }
    }

    private val tracked = LinkedHashMap<String, Tracked>()
    private val probes = mutableListOf<ProbeRun>()

    private var targetKey: String? = null
    private val targetChanges = mutableListOf<Long>()

    /**
     * Addresses the person running this has said are their own.
     *
     * The app cannot work out which devices you own and should stop trying past a certain
     * point: every heuristic for it is a guess, and a wrong guess either leaves your earbuds
     * at the top of the short list forever or quietly removes the target. You saying so is
     * the only thing here that is not a guess, so once you have said it the device is gone
     * from the pool - in this follow and every one after it.
     */
    var ignored: Set<String> = emptySet()

    /** Open questions, keyed by the address that went quiet. */
    private val pending = mutableMapOf<String, Handoff.Ask>()

    /** Devices somebody has said were not any of the options. Not asked about again. */
    private val refused = mutableSetOf<String>()

    /** Every rotation followed during this session, in order. */
    private val stitched = mutableListOf<Stitch>()

    /**
     * What happened, in order, so a follow can be replayed, marked and exported.
     *
     * On the session rather than the screen for the same reason everything else is: a
     * follow is half an hour of walking with the phone in a pocket, and a record that only
     * existed while somebody was looking at it would be a record of the parts that did not
     * matter.
     */
    val journal = Journal()

    /**
     * Addresses this session has decided should be muted, waiting to be applied.
     *
     * Two things end up here. One is a mute inheriting across a rotation: muting your own
     * earbuds is worthless if it lasts fifteen minutes, so the mute has to travel with the
     * device - and it travels only along a link this session actually made, never to
     * anything that merely looks similar, which would mute a stranger's identical earbuds
     * and quietly delete them from the evidence. The other is [FollowTuning.autoMuteAboveDbm]
     * firing on something in the innermost ring.
     *
     * Drained by the caller, because the shared ignore list is Android and this is not.
     */
    private val newMutes = mutableListOf<String>()

    private var baselineStartedAtMs: Long? = null
    private var baselineEndedAtMs: Long? = null
    private var followStartedAtMs: Long? = null

    /**
     * Stretches where nothing was being listened to, so they do not count against anybody.
     *
     * The elimination is "has not been heard for a minute", and that is only a statement
     * about a device if the radio was on. Put the phone away for five minutes and every
     * device in the pool goes silent at once - not because they left but because nobody was
     * listening - and coming back to an empty list would be the most confidently wrong this
     * app could be.
     *
     * So silence is measured in listening time. A gap is recorded when the session is put
     * down and closed when it is picked up, and the sweep subtracts whatever part of a
     * device's silence overlaps one.
     */
    private val blind = mutableListOf<LongRange>()
    private var blindSince: Long? = null

    var phase: FollowPhase = FollowPhase.IDLE
        private set

    // ------------------------------------------------------------------- listening

    @Synchronized
    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        label: String?,
        vendor: String?,
        isRandom: Boolean,
        /** The advertisement's structure, without which nothing can be followed through a
         *  rotation. Defaulted so older callers still compile, but a caller that does not
         *  supply it has turned the rotation bridge off for that device. */
        shape: AdvertShape = AdvertShape(),
    ) {
        val key = address.uppercase(Locale.US)
        val entry = tracked.getOrPut(key) {
            Tracked(label, vendor, isRandom, atMs).also {
                it.addresses.add(key)
                // Decided once, when the device is first heard. A device that arrives and
                // then goes quiet is still an arrival.
                it.arrived = baselineEndedAtMs?.let { ended -> atMs > ended } ?: false
                // The pool closes the moment the follow starts.
                it.inPool = followStartedAtMs?.let { started -> atMs <= started } ?: true
            }
        }
        label?.takeIf { it.isNotBlank() }?.let { entry.label = it }
        vendor?.let { entry.vendor = it }
        entry.lastSeenMs = atMs
        entry.packets++
        entry.rssiTotal += rssi
        entry.recentRssi = if (entry.packets == 1) {
            rssi.toDouble()
        } else {
            entry.recentRssi * (1 - RECENT_ALPHA) + rssi * RECENT_ALPHA
        }
        if (rssi >= tuning.carriedDbm) entry.closeReadings++
        if (rssi > entry.bestRssi) entry.bestRssi = rssi
        entry.shape = entry.shape.merge(shape)
        // Gaps only from packets that arrived in order and close together. A gap spanning
        // a walk round a corner is a measure of the scanner, not of the device.
        entry.recent.lastOrNull()?.let { (previousMs, _) ->
            val gap = atMs - previousMs
            if (gap in 1L..GAP_CAP_MS) {
                entry.gaps.add(gap)
                while (entry.gaps.size > GAPS_CAP) entry.gaps.removeAt(0)
            }
        }
        entry.record(atMs, rssi)

        probes.lastOrNull()?.takeIf { it.running }?.let { probe ->
            when (probe.kind) {
                Probe.ORBIT -> {
                    entry.orbitArcs.getOrPut(probe.index) { mutableSetOf() }
                        .add(arcOf(probe, atMs))
                    entry.orbitRssi.getOrPut(probe.index) { mutableListOf() }.add(rssi)
                }

                Probe.WALK_BY -> entry.walkByTrail
                    .getOrPut(probe.index) { mutableListOf() }
                    .add(atMs to rssi)
            }
        }
    }

    /**
     * Stops the clock. Call when the radio is no longer feeding this session.
     *
     * Idempotent: pausing an already-paused session does nothing, because the gap started
     * when it was first put down and has not ended.
     */
    @Synchronized
    fun pause(atMs: Long) {
        if (blindSince == null) blindSince = atMs
    }

    /** Starts the clock again, and remembers how long it was stopped for. */
    @Synchronized
    fun resume(atMs: Long) {
        val since = blindSince ?: return
        blindSince = null
        if (atMs > since) blind.add(since..atMs)
    }

    val paused: Boolean get() = blindSince != null

    /** How long nothing was being listened to, between two moments. */
    fun blindMsBetween(fromMs: Long, toMs: Long): Long {
        if (toMs <= fromMs) return 0L
        val open = blindSince?.let { listOf(it..maxOf(it, toMs)) }.orEmpty()
        return (blind + open).sumOf { gap ->
            val from = maxOf(gap.first, fromMs)
            val to = minOf(gap.last, toMs)
            (to - from).coerceAtLeast(0L)
        }
    }

    /** How long a device has been silent, not counting time nobody was listening. */
    fun silenceMs(lastSeenMs: Long, nowMs: Long): Long =
        (nowMs - lastSeenMs - blindMsBetween(lastSeenMs, nowMs)).coerceAtLeast(0L)

    // ---------------------------------------------------------------------- stages

    @Synchronized
    fun startBaseline(atMs: Long) {
        baselineStartedAtMs = atMs
        baselineEndedAtMs = null
        phase = FollowPhase.BASELINE
    }

    /**
     * Closes the baseline. Everything first heard after this counts as having arrived.
     *
     * Separate from starting the follow, because the two are not always the same moment:
     * when the baseline is taken before the person you are waiting for turns up, the follow
     * starts when they do.
     */
    @Synchronized
    fun endBaseline(atMs: Long) {
        baselineEndedAtMs = atMs
        journal.add(Moment.BaselineDone(atMs, tracked.size))
    }

    /**
     * Starts following, and closes the pool.
     *
     * Everything heard up to this instant is in; everything first heard after it is not.
     * That is the whole reason the number can only fall.
     */
    @Synchronized
    fun startFollowing(atMs: Long) {
        if (baselineEndedAtMs == null) baselineEndedAtMs = atMs
        followStartedAtMs = atMs
        tracked.values.forEach { entry ->
            // Heard enough to be a device rather than a blip, and heard before now. Both
            // halves are decided at this instant and never revisited, which is what makes
            // the count able only to fall. Without the packet test the pool was fixed here
            // but the visible count was not: devices already in the pool kept crossing the
            // minimum packet threshold for the next minute and appearing one by one, so
            // the number climbed after the baseline and looked like the opposite of what
            // this experiment does.
            entry.inPool = entry.firstSeenMs <= atMs && entry.packets >= tuning.minPackets
            entry.droppedAtMs = null
            entry.returnedAtMs = null
        }
        phase = FollowPhase.FOLLOWING
        journal.add(
            Moment.Following(
                atMs = atMs,
                pool = tracked.values.count { it.inPool },
                targetPresent = atMs - (baselineEndedAtMs ?: atMs) < TARGET_PRESENT_MS,
            ),
        )
    }

    /**
     * Throws the elimination away and starts again, keeping what is known about the room.
     *
     * For the case a follow cannot otherwise recover from: the target changed address
     * partway through, so the device being narrowed towards stopped existing and its
     * replacement was never in the pool. This reopens the pool to everything currently
     * audible without forgetting anything.
     */
    @Synchronized
    fun rebaseline(atMs: Long) {
        probes.clear()
        blind.clear()
        blindSince = null
        targetKey = null
        targetChanges.clear()
        baselineStartedAtMs = atMs
        baselineEndedAtMs = null
        followStartedAtMs = null
        tracked.values.forEach { entry ->
            entry.inPool = false
            entry.droppedAtMs = null
            entry.returnedAtMs = null
            entry.arrived = false
            entry.orbitArcs.clear()
            entry.orbitRssi.clear()
            entry.walkByTrail.clear()
        }
        phase = FollowPhase.BASELINE
    }

    // ---------------------------------------------------------------------- probes

    /**
     * Starts another probe. As many as you like, each scored on its own.
     *
     * Only one runs at a time - starting one ends whatever was running - because the two
     * are different walks and a device cannot be doing both.
     */
    @Synchronized
    fun beginProbe(kind: Probe, atMs: Long) {
        endProbe(atMs)
        journal.add(Moment.ProbeRan(atMs, kind, probes.size))
        probes.add(ProbeRun(probes.size, kind, atMs))
    }

    /**
     * Marks the moment of closest approach during a walk-by.
     *
     * A tap rather than anything clever, because the person holding the phone knows exactly
     * when they drew level and no sensor on the phone does. The last tap wins - somebody who
     * taps early and corrects themselves meant the second one.
     */
    @Synchronized
    fun markClosest(atMs: Long) {
        val index = probes.indexOfLast { it.running && it.kind == Probe.WALK_BY }
        if (index < 0) return
        probes[index] = probes[index].copy(midAtMs = atMs)
    }

    @Synchronized
    fun endProbe(atMs: Long) {
        val index = probes.indexOfLast { it.running }
        if (index < 0) return
        probes[index] = probes[index].copy(endedAtMs = atMs)
    }

    // ------------------------------------------------------------------ the target

    @Synchronized
    fun lock(address: String) {
        val key = address.uppercase(Locale.US)
        targetKey = key
        phase = FollowPhase.HOLDING
        journal.add(Moment.Held(System.currentTimeMillis(), key, tracked[key]?.label))
    }

    /** Records something the operator saw and the radio could not. */
    @Synchronized
    fun mark(mark: Mark, atMs: Long, note: String? = null) {
        journal.add(Moment.Marked(atMs, mark, note?.takeIf { it.isNotBlank() }))
    }

    @Synchronized
    fun unlock() {
        targetKey = null
        targetChanges.clear()
        phase = if (followStartedAtMs == null) FollowPhase.BASELINE else FollowPhase.FOLLOWING
    }

    @Synchronized
    fun reacquire(newAddress: String, atMs: Long) {
        val old = targetKey ?: return
        reacquire(old, newAddress, atMs)
    }

    /**
     * Carries one candidate's history onto the address it has just put on.
     *
     * Written for any candidate rather than only the locked target, because the short list
     * is where this matters most. Five devices being watched for a rotation is five chances
     * to keep the trail; only watching the one you already committed to means the rotation
     * that loses you the target is the one nobody was looking at.
     *
     * The decision to call this is [Following]'s, refusals intact. This only records what it
     * was told - including when, which is what makes a rotation rhythm measurable and
     * therefore what makes it possible to say when the next one is due.
     *
     * @return true when the move was made. False means the new address is not one this
     *   session has heard, which is a caller bug rather than a refusal.
     */
    @Synchronized
    fun reacquire(oldAddress: String, newAddress: String, atMs: Long): Boolean {
        val old = oldAddress.uppercase(Locale.US)
        val key = newAddress.uppercase(Locale.US)
        if (old == key) return false
        val entry = tracked[key] ?: return false
        val previous = tracked[old] ?: return false

        entry.addresses.clear()
        entry.addresses.addAll(previous.addresses)
        entry.addresses.add(key)
        entry.label = entry.label ?: previous.label
        entry.vendor = entry.vendor ?: previous.vendor
        previous.orbitArcs.forEach { (index, arcs) ->
            entry.orbitArcs.getOrPut(index) { mutableSetOf() }.addAll(arcs)
        }
        previous.orbitRssi.forEach { (index, levels) ->
            entry.orbitRssi.getOrPut(index) { mutableListOf() }.addAll(levels)
        }
        previous.walkByTrail.forEach { (index, trail) ->
            entry.walkByTrail.getOrPut(index) { mutableListOf() }.addAll(trail)
        }
        entry.closeReadings += previous.closeReadings
        entry.arrived = previous.arrived
        // The new address inherits the old one's place in the pool. A rotation is the one
        // way a device can legitimately appear mid-follow and still be the thing you were
        // already following.
        entry.inPool = previous.inPool
        entry.droppedAtMs = null
        entry.returnedAtMs = previous.returnedAtMs

        // A mute follows the device, not the address it happened to be wearing.
        if (ignored.contains(old)) newMutes.add(key)

        // The old address is dropped rather than left behind. Leaving it would put the same
        // device on the list twice, once as a ghost that stopped answering, and a short list
        // with a ghost on it is one shorter than it looks.
        tracked.remove(old)

        if (targetKey == old) {
            targetChanges.add(atMs)
            targetKey = key
            phase = FollowPhase.HOLDING
        }
        return true
    }

    // ----------------------------------------------------------------- the finding

    @Synchronized
    fun candidates(nowMs: Long): List<FollowCandidate> {
        // A running circle is scored live, because the screen shows a count during the lap.
        // A running walk-by is not, because half a walk has no far end to compare against.
        val orbitRuns = probes.filter { it.kind == Probe.ORBIT }
        val walkRuns = probes.filter { it.kind == Probe.WALK_BY && it.complete }

        return tracked.entries
            // Pool membership was settled when the follow started, so a device in it is
            // never re-tested - only new arrivals have to earn their way onto the list.
            .filter { it.value.inPool || it.value.packets >= tuning.minPackets }
            .filterNot { ignored.contains(it.key) }
            .map { (address, entry) ->
                // From the shape accumulated over the whole follow rather than one packet:
                // a device does not send every Continuity message in every advertisement,
                // and the one that identifies it may be the one that arrived last.
                val guess = DeviceKinds.of(entry.shape)
                FollowCandidate(
                    address = address,
                    label = entry.label,
                    vendor = entry.vendor,
                    isRandom = entry.isRandom,
                    packets = entry.packets,
                    meanRssi = entry.meanRssi,
                    kind = guess.kind,
                    kindCertain = guess.certain,
                    recentRssi = entry.recentRssi,
                    closeFraction = if (entry.packets == 0) {
                        0.0
                    } else {
                        entry.closeReadings.toDouble() / entry.packets
                    },
                    spreadDb = entry.spreadDb(),
                    firstSeenMs = entry.firstSeenMs,
                    lastSeenMs = entry.lastSeenMs,
                    addresses = entry.addresses.toList(),
                    inPool = entry.inPool,
                    droppedAtMs = entry.droppedAtMs,
                    returnedAtMs = entry.returnedAtMs,
                    arrived = entry.arrived,
                    orbits = orbitRuns.map { run ->
                        CandidateOrbit(
                            probeIndex = run.index,
                            score = scoreOrbit(entry, run, arcsIn(run.durationMs(nowMs))),
                        )
                    },
                    walkBys = walkRuns.map { run ->
                        val trail = entry.walkByTrail[run.index].orEmpty().toList()
                        CandidateWalkBy(
                            probeIndex = run.index,
                            score = WalkBy.score(
                                readings = trail,
                                startMs = run.startedAtMs,
                                midMs = run.midAtMs ?: run.startedAtMs,
                                endMs = run.endedAtMs ?: nowMs,
                            ),
                            trail = trail,
                        )
                    },
                )
            }
            .sortedWith(
                compareByDescending<FollowCandidate> { it.weight(nowMs) }
                    .thenByDescending { it.meanRssi },
            )
    }

    /**
     * Every device that has gone quiet and needs somebody to say where it went.
     *
     * Drained rather than read: an answered question does not come back, and one the caller
     * has already been shown is not shown again until the situation changes.
     */
    /**
     * Pool devices that have stopped being heard but have not been retired yet.
     *
     * Counted so the screen can say the elimination is still happening. A number that sits
     * still for a minute while the app is working reads as a number that has stopped.
     */
    private fun goingQuiet(nowMs: Long): Int {
        if (followStartedAtMs == null) return 0
        return tracked.count { (key, entry) ->
            entry.inPool &&
                entry.droppedAtMs == null &&
                !ignored.contains(key) &&
                silenceMs(entry.lastSeenMs, nowMs) > QUIET_AFTER_MS
        }
    }

    /** How long until the next pool device is retired. */
    private fun nextDropInMs(nowMs: Long): Long? {
        if (followStartedAtMs == null) return null
        return tracked.entries
            .filter { (key, entry) ->
                entry.inPool && entry.droppedAtMs == null && !ignored.contains(key)
            }
            .map { (_, entry) -> tuning.dropAfterMs - silenceMs(entry.lastSeenMs, nowMs) }
            .filter { it in 0..tuning.dropAfterMs }
            .minOrNull()
    }

    @Synchronized
    fun questions(): List<Handoff.Ask> = pending.values.toList()

    /** Rotations taken without asking, newest last, for a screen that wants to show them. */
    @Synchronized
    fun stitches(): List<Stitch> = stitched.toList()

    /**
     * A frozen copy of the record.
     *
     * The live [journal] is written from the scanning service's thread. Handing it to a
     * screen to iterate is how a follow crashes halfway through a walk, which is exactly
     * what it did.
     */
    @Synchronized
    fun journalCopy(): Journal = Journal().also { it.restore(journal.snapshot()) }

    /**
     * Recent levels per address, for working out which devices share a pocket.
     *
     * Handed out rather than scored here, because who looks like the target is a reading of
     * a follow rather than part of one - and the session has no business deciding it.
     */
    @Synchronized
    fun trails(): Map<String, List<Pair<Long, Int>>> =
        tracked.mapValues { (_, entry) -> entry.recent.toList() }

    /** Takes the pending mutes away, so the caller can apply them to the shared list. */
    @Synchronized
    fun drainNewMutes(): List<String> {
        if (newMutes.isEmpty()) return emptyList()
        val taken = newMutes.toList()
        newMutes.clear()
        return taken
    }

    /**
     * Mutes whatever is sitting in the innermost ring, when that has been asked for.
     *
     * Deliberately requires a few packets rather than firing on one loud reading. A single
     * close packet happens when somebody walks past you with a phone in their hand, and
     * muting on that would remove a stranger for the rest of the follow with no way to
     * notice it had happened.
     */
    private fun autoMute(nowMs: Long) {
        val threshold = tuning.autoMuteAboveDbm ?: return
        if (followStartedAtMs == null) return
        tracked.forEach { (key, entry) ->
            if (ignored.contains(key) || newMutes.contains(key)) return@forEach
            if (entry.packets < AUTO_MUTE_PACKETS) return@forEach
            if (nowMs - entry.lastSeenMs > tuning.dropAfterMs) return@forEach
            if (entry.recentRssi >= threshold) newMutes.add(key)
        }
    }

    /**
     * Answers one of the open questions, or dismisses it.
     *
     * @param toAddress null to say none of the options were it, which drops the device
     *   rather than leaving the question open forever.
     */
    @Synchronized
    fun answer(oldAddress: String, toAddress: String?, nowMs: Long): Boolean {
        val key = oldAddress.uppercase(Locale.US)
        pending.remove(key) ?: return false
        if (toAddress == null) {
            refused.add(key)
            return false
        }
        val moved = reacquire(key, toAddress, nowMs)
        if (moved) {
            val to = toAddress.uppercase(Locale.US)
            stitched.add(Stitch(key, to, nowMs, byHand = true))
            journal.add(Moment.Rotated(nowMs, key, to, byHand = true))
        }
        return moved
    }

    /**
     * Follows the survivors through their address changes.
     *
     * This is what makes a half-hour follow possible. Without it a phone rotating every
     * fifteen minutes is lost twice on the way from a food court to an office, and each
     * loss looks exactly like the target having walked off - the count drops by one and
     * nothing says why.
     *
     * Run from [state] so nothing has to remember to call it, and gated on the survivor
     * count so a whole concourse is never being stitched at once.
     */
    private fun bridge(nowMs: Long) {
        val started = followStartedAtMs ?: return

        // Not in the opening seconds. A follow that has just started has almost nothing
        // in its pool yet, so the count is trivially under the threshold and every device
        // in the street is an unexplained arrival. That is how a rotation question ended
        // up being asked about a crowd.
        if (nowMs - started < BRIDGE_AFTER_MS) return

        val living = tracked.entries.count { (key, entry) ->
            entry.inPool && entry.droppedAtMs == null && !ignored.contains(key)
        }
        if (living > tuning.bridgeAtOrBelow) return

        // Successors are by definition addresses that did not exist when the follow began,
        // so the pool members are the ones being followed and everything else is a
        // possible destination.
        val arrivals = tracked.entries
            .filter { (key, entry) ->
                !entry.inPool &&
                    entry.isRandom &&
                    entry.firstSeenMs > started &&
                    entry.packets >= Handoffs.MIN_TRAIL &&
                    nowMs - entry.lastSeenMs <= Handoffs.SILENCE_MS
            }
            .map { (key, entry) -> entry.identity(key) }

        val claimed = mutableSetOf<String>()

        tracked.entries
            .filter { (key, entry) ->
                entry.inPool &&
                    entry.droppedAtMs == null &&
                    !ignored.contains(key) &&
                    !refused.contains(key) &&
                    !pending.containsKey(key) &&
                    entry.packets >= Handoffs.MIN_TRAIL &&
                    nowMs - entry.lastSeenMs >= Handoffs.SILENCE_MS
            }
            // Oldest silence first, so the device that went quiet earliest gets first claim
            // on a successor rather than whichever the map happened to iterate to.
            .sortedBy { it.value.lastSeenMs }
            .forEach { (key, entry) ->
                val departure = Handoffs.classify(key, entry.recent.toList(), entry.bestRssi)
                when (
                    val handoff = Handoffs.decide(
                        previous = entry.identity(key),
                        departure = departure,
                        candidates = arrivals,
                        nowMs = nowMs,
                        taken = claimed,
                    )
                ) {
                    is Handoff.Rotated -> {
                        claimed.add(handoff.to.address)
                        if (reacquire(key, handoff.to.address, nowMs)) {
                            stitched.add(
                                Stitch(key, handoff.to.address, nowMs, byHand = false),
                            )
                            journal.add(
                                Moment.Rotated(nowMs, key, handoff.to.address, byHand = false),
                            )
                        }
                    }

                    is Handoff.Ask -> {
                        handoff.options.forEach { claimed.add(it.address) }
                        pending[key] = handoff
                    }

                    // Nothing to do. The ordinary drop-off will retire it, which is the
                    // right outcome for a device that walked away.
                    is Handoff.Gone -> Unit
                    Handoff.Waiting -> Unit
                }
            }
    }

    @Synchronized
    fun state(nowMs: Long): FollowState {
        sweep(nowMs)
        autoMute(nowMs)
        bridge(nowMs)
        if (followStartedAtMs != null) {
            journal.sample(
                atMs = nowMs,
                stillIn = tracked.values.count { it.inPool && it.droppedAtMs == null },
                pool = tracked.values.count { it.inPool },
            )
        }

        val candidates = candidates(nowMs)
        val target = targetKey?.let { key -> candidates.firstOrNull { it.address == key } }
        val silent = target?.let { nowMs - it.lastSeenMs } ?: 0L

        if (targetKey != null) {
            phase = if (silent > tuning.lostAfterMs) FollowPhase.LOST else FollowPhase.HOLDING
        }

        val rhythm = if (targetChanges.size >= 2) RotationRhythm.analyze(targetChanges) else null

        return FollowState(
            phase = phase,
            atMs = nowMs,
            candidates = candidates,
            watching = tracked.size,
            baselineEndedAtMs = baselineEndedAtMs,
            followStartedAtMs = followStartedAtMs,
            probes = probes.toList(),
            target = target,
            silentForMs = silent,
            rhythm = rhythm,
            expectedReturnMs = expectedReturn(rhythm),
            rotationChangesAtMs = targetChanges.toList(),
            tuning = tuning,
            blindMs = followStartedAtMs?.let { blindMsBetween(it, nowMs) } ?: 0L,
            questions = pending.values.toList(),
            goingQuiet = goingQuiet(nowMs),
            nextDropInMs = nextDropInMs(nowMs),
            autoMuting = tuning.autoMuteAboveDbm,
            bridging = followStartedAtMs != null && candidates.count { it.stillIn } <=
                tuning.bridgeAtOrBelow,
            stitches = stitched.size,
        )
    }

    /**
     * Drops whatever has gone quiet for too long, and notes whatever has come back.
     *
     * Only once the follow has started. Nothing drops during a baseline, because a baseline
     * is a census rather than a test and eliminating from it would be eliminating on the
     * strength of having stood still for half a minute.
     */
    private fun sweep(nowMs: Long) {
        val started = followStartedAtMs ?: return
        tracked.values.forEach { entry ->
            if (!entry.inPool) return@forEach
            val dropped = entry.droppedAtMs
            if (dropped == null) {
                val silentSince = maxOf(entry.lastSeenMs, started)
                val silence = silenceMs(silentSince, nowMs)
                // A device that was fading before it went quiet was walking out of range,
                // and waiting the full minute for it is a minute of a screen that looks
                // stuck. The same reading that stops a rotation being chased after a
                // genuine walk-off pays for itself again here: it is exactly the case
                // where the silence is already explained.
                //
                // Never the other way round. Something that was loud and steady and then
                // stopped gets the full drop-off, because that is what a rotation looks
                // like and retiring it early would lose the target at the moment it
                // changed address.
                val limit = if (
                    silence > FADED_DROP_MS &&
                    entry.recent.size >= Handoffs.MIN_TRAIL &&
                    Handoffs.classify(entry.addresses.last(), entry.recent.toList(), entry.bestRssi)
                        .exit == Exit.FADED
                ) {
                    FADED_DROP_MS
                } else {
                    tuning.dropAfterMs
                }
                if (silence > limit) {
                    entry.droppedAtMs = silentSince + limit + blindMsBetween(silentSince, nowMs)
                }
            } else if (entry.lastSeenMs > dropped && entry.returnedAtMs == null) {
                entry.returnedAtMs = entry.lastSeenMs
            }
        }
    }

    /**
     * Which slice of the circle a packet landed in.
     *
     * Counted from the start of the probe in fixed-length arcs rather than as a fraction of
     * the whole, because the whole is not known until it ends and the count has to be right
     * while it is still running.
     */
    private fun arcOf(probe: ProbeRun, atMs: Long): Int =
        ((atMs - probe.startedAtMs) / OrbitScore.ARC_MS).toInt().coerceAtLeast(0)

    /**
     * How many arcs a circle of this length has, rounded up.
     *
     * Rounded up rather than down so a lap that runs a few seconds past a boundary is not
     * scored as if the last sliver were a whole arc nobody could fill. A lap of exactly
     * eight arcs has eight, not nine: the instant it ends belongs to the next one.
     */
    private fun arcsIn(durationMs: Long): Int =
        ((durationMs + OrbitScore.ARC_MS - 1) / OrbitScore.ARC_MS).toInt().coerceAtLeast(0)

    /** What one circle said about one device. */
    private fun scoreOrbit(entry: Tracked, run: ProbeRun, arcs: Int): OrbitScore {
        val levels = entry.orbitRssi[run.index].orEmpty().map { it.toDouble() }.sorted()
        return OrbitScore(
            arcsHeard = entry.orbitArcs[run.index].orEmpty().size,
            arcsTotal = arcs,
            packets = levels.size,
            meanRssi = if (levels.isEmpty()) -127.0 else levels.average(),
            spreadDb = if (levels.size < 2) {
                0.0
            } else {
                Stats.percentile(levels, 0.9) - Stats.percentile(levels, 0.1)
            },
        )
    }

    /**
     * When the target's next address is due, if anything is known about its rhythm.
     *
     * Measured where the session has watched two changes; otherwise the specification's
     * nine hundred second default, which is what most stacks inherit. Null when there is no
     * last change to count from at all - a target that has never rotated while being watched
     * could put on a new address at any moment, and a countdown to a made-up deadline would
     * be worse than no countdown.
     */
    private fun expectedReturn(rhythm: Rhythm?): Long? {
        val lastChange = targetChanges.lastOrNull() ?: return null
        val period = rhythm?.takeIf { it.measurable && it.regular }?.medianPeriodMs
            ?: RotationRhythm.SPEC_DEFAULT_MS
        return lastChange + period
    }

    // -------------------------------------------------------------------- keeping it

    /**
     * The whole follow as JSON, so leaving the screen is not the same as giving up.
     *
     * A follow is half an hour of walking. Losing it because somebody checked a message was
     * the single worst thing about this experiment, and it was not a small bug - it made
     * the app unusable for the thing it exists to do.
     *
     * Trails are capped rather than dropped. A walk-by's readings are what its chart is
     * drawn from and losing them would make a saved follow unreviewable, but a long circle
     * with forty devices in range is thousands of pairs, so each keeps its most recent
     * [TRAIL_CAP] - which is more than any of them needs at one reading a second.
     */
    @Synchronized
    fun snapshot(): JSONObject {
        val devices = JSONArray()
        tracked.forEach { (address, entry) ->
            devices.put(
                JSONObject()
                    .put("a", address)
                    .put("label", entry.label ?: JSONObject.NULL)
                    .put("vendor", entry.vendor ?: JSONObject.NULL)
                    .put("random", entry.isRandom)
                    .put("first", entry.firstSeenMs)
                    .put("last", entry.lastSeenMs)
                    .put("packets", entry.packets)
                    .put("rssiTotal", entry.rssiTotal)
                    .put("recent", entry.recentRssi)
                    .put("close", entry.closeReadings)
                    .put("levels", JSONArray(entry.levels.takeLast(TRAIL_CAP)))
                    .put("pool", entry.inPool)
                    .put("dropped", entry.droppedAtMs ?: JSONObject.NULL)
                    .put("returned", entry.returnedAtMs ?: JSONObject.NULL)
                    .put("arrived", entry.arrived)
                    .put("addresses", JSONArray(entry.addresses))
                    .put("orbitArcs", encodeIntSets(entry.orbitArcs))
                    .put("orbitRssi", encodeIntLists(entry.orbitRssi))
                    .put("trails", encodeTrails(entry.walkByTrail)),
            )
        }

        val probeArray = JSONArray()
        probes.forEach { probe ->
            probeArray.put(
                JSONObject()
                    .put("i", probe.index)
                    .put("kind", probe.kind.name)
                    .put("start", probe.startedAtMs)
                    .put("end", probe.endedAtMs ?: JSONObject.NULL)
                    .put("mid", probe.midAtMs ?: JSONObject.NULL),
            )
        }

        val gaps = JSONArray()
        blind.forEach { gaps.put(JSONArray(listOf(it.first, it.last))) }

        return JSONObject()
            .put("phase", phase.name)
            .put("baselineStarted", baselineStartedAtMs ?: JSONObject.NULL)
            .put("baselineEnded", baselineEndedAtMs ?: JSONObject.NULL)
            .put("followStarted", followStartedAtMs ?: JSONObject.NULL)
            .put("target", targetKey ?: JSONObject.NULL)
            .put("changes", JSONArray(targetChanges))
            .put("blind", gaps)
            .put("blindSince", blindSince ?: JSONObject.NULL)
            .put("probes", probeArray)
            .put("journal", journal.snapshot())
            .put("devices", devices)
    }

    /** Puts a snapshot back. Anything unreadable is skipped rather than failing the load. */
    @Synchronized
    fun restore(json: JSONObject) {
        tracked.clear()
        probes.clear()
        blind.clear()
        targetChanges.clear()

        phase = runCatching { FollowPhase.valueOf(json.optString("phase")) }
            .getOrDefault(FollowPhase.IDLE)
        baselineStartedAtMs = json.optLongOrNull("baselineStarted")
        baselineEndedAtMs = json.optLongOrNull("baselineEnded")
        followStartedAtMs = json.optLongOrNull("followStarted")
        targetKey = json.optString("target").takeIf { it.isNotBlank() && it != "null" }
        blindSince = json.optLongOrNull("blindSince")

        val changes = json.optJSONArray("changes") ?: JSONArray()
        (0 until changes.length()).forEach { targetChanges.add(changes.getLong(it)) }

        journal.restore(json.optJSONArray("journal"))

        val gaps = json.optJSONArray("blind") ?: JSONArray()
        (0 until gaps.length()).forEach { index ->
            val gap = gaps.optJSONArray(index) ?: return@forEach
            if (gap.length() == 2) blind.add(gap.getLong(0)..gap.getLong(1))
        }

        val probeArray = json.optJSONArray("probes") ?: JSONArray()
        (0 until probeArray.length()).forEach { index ->
            val probe = probeArray.getJSONObject(index)
            val kind = runCatching { Probe.valueOf(probe.optString("kind")) }.getOrNull()
                ?: return@forEach
            probes.add(
                ProbeRun(
                    index = probe.optInt("i", index),
                    kind = kind,
                    startedAtMs = probe.optLong("start"),
                    endedAtMs = probe.optLongOrNull("end"),
                    midAtMs = probe.optLongOrNull("mid"),
                ),
            )
        }

        val devices = json.optJSONArray("devices") ?: JSONArray()
        (0 until devices.length()).forEach { index ->
            val device = devices.getJSONObject(index)
            val address = device.optString("a").takeIf { it.isNotBlank() } ?: return@forEach
            val entry = Tracked(
                label = device.optString("label").takeIf { it.isNotBlank() && it != "null" },
                vendor = device.optString("vendor").takeIf { it.isNotBlank() && it != "null" },
                isRandom = device.optBoolean("random", true),
                firstSeenMs = device.optLong("first"),
            )
            entry.lastSeenMs = device.optLong("last", entry.firstSeenMs)
            entry.packets = device.optInt("packets")
            entry.rssiTotal = device.optDouble("rssiTotal", 0.0)
            entry.recentRssi = device.optDouble("recent", -127.0)
            entry.closeReadings = device.optInt("close")
            val levels = device.optJSONArray("levels") ?: JSONArray()
            (0 until levels.length()).forEach { entry.levels.add(levels.getInt(it)) }
            entry.inPool = device.optBoolean("pool")
            entry.droppedAtMs = device.optLongOrNull("dropped")
            entry.returnedAtMs = device.optLongOrNull("returned")
            entry.arrived = device.optBoolean("arrived")

            val worn = device.optJSONArray("addresses") ?: JSONArray()
            (0 until worn.length()).forEach { entry.addresses.add(worn.getString(it)) }
            if (entry.addresses.isEmpty()) entry.addresses.add(address)

            decodeIntSets(device.optJSONObject("orbitArcs"), entry.orbitArcs)
            decodeIntLists(device.optJSONObject("orbitRssi"), entry.orbitRssi)
            decodeTrails(device.optJSONObject("trails"), entry.walkByTrail)

            tracked[address] = entry
        }
    }

    private fun encodeIntSets(source: Map<Int, MutableSet<Int>>): JSONObject {
        val json = JSONObject()
        source.forEach { (index, values) -> json.put(index.toString(), JSONArray(values.toList())) }
        return json
    }

    private fun encodeIntLists(source: Map<Int, MutableList<Int>>): JSONObject {
        val json = JSONObject()
        source.forEach { (index, values) ->
            json.put(index.toString(), JSONArray(values.takeLast(TRAIL_CAP)))
        }
        return json
    }

    private fun encodeTrails(source: Map<Int, MutableList<Pair<Long, Int>>>): JSONObject {
        val json = JSONObject()
        source.forEach { (index, trail) ->
            val flat = JSONArray()
            trail.takeLast(TRAIL_CAP).forEach { (atMs, rssi) ->
                flat.put(atMs)
                flat.put(rssi)
            }
            json.put(index.toString(), flat)
        }
        return json
    }

    private fun decodeIntSets(json: JSONObject?, into: MutableMap<Int, MutableSet<Int>>) {
        json ?: return
        json.keys().forEach { key ->
            val index = key.toIntOrNull() ?: return@forEach
            val values = json.optJSONArray(key) ?: return@forEach
            into[index] = (0 until values.length()).map { values.getInt(it) }.toMutableSet()
        }
    }

    private fun decodeIntLists(json: JSONObject?, into: MutableMap<Int, MutableList<Int>>) {
        json ?: return
        json.keys().forEach { key ->
            val index = key.toIntOrNull() ?: return@forEach
            val values = json.optJSONArray(key) ?: return@forEach
            into[index] = (0 until values.length()).map { values.getInt(it) }.toMutableList()
        }
    }

    private fun decodeTrails(
        json: JSONObject?,
        into: MutableMap<Int, MutableList<Pair<Long, Int>>>,
    ) {
        json ?: return
        json.keys().forEach { key ->
            val index = key.toIntOrNull() ?: return@forEach
            val flat = json.optJSONArray(key) ?: return@forEach
            val trail = mutableListOf<Pair<Long, Int>>()
            var at = 0
            while (at + 1 < flat.length()) {
                trail.add(flat.getLong(at) to flat.getInt(at + 1))
                at += 2
            }
            into[index] = trail
        }
    }

    @Synchronized
    fun csv(): String = buildString {
        appendLine("# SigEye follow")
        appendLine("baseline_ended_ms,${baselineEndedAtMs ?: ""}")
        appendLine("follow_started_ms,${followStartedAtMs ?: ""}")
        appendLine("drop_after_ms,${tuning.dropAfterMs}")
        appendLine("probe,kind,started_ms,ended_ms,mid_ms")
        probes.forEachIndexed { index, probe ->
            appendLine(
                "$index,${probe.kind.name},${probe.startedAtMs}," +
                    "${probe.endedAtMs ?: ""},${probe.midAtMs ?: ""}",
            )
        }
        appendLine(
            "address,label,vendor,random,in_pool,still_in,first_seen_ms,last_seen_ms," +
                "dropped_at_ms,returned_at_ms,packets,mean_rssi,orbit_arcs,orbit_arcs_total," +
                "orbit_spread_db,walkby_rise_db,walkby_passed,addresses",
        )
        candidates(System.currentTimeMillis()).forEach { candidate ->
            appendLine(
                listOf(
                    candidate.address,
                    candidate.label?.replace(',', ' ') ?: "",
                    candidate.vendor?.replace(',', ' ') ?: "",
                    candidate.isRandom.toString(),
                    candidate.inPool.toString(),
                    candidate.stillIn.toString(),
                    candidate.firstSeenMs.toString(),
                    candidate.lastSeenMs.toString(),
                    candidate.droppedAtMs?.toString() ?: "",
                    candidate.returnedAtMs?.toString() ?: "",
                    candidate.packets.toString(),
                    String.format(Locale.US, "%.1f", candidate.meanRssi),
                    candidate.orbit?.arcsHeard?.toString() ?: "",
                    candidate.orbit?.arcsTotal?.toString() ?: "",
                    candidate.orbit?.takeIf { !it.sparse }
                        ?.let { String.format(Locale.US, "%.1f", it.spreadDb) } ?: "",
                    candidate.walkBy?.takeIf { !it.sparse }
                        ?.let { String.format(Locale.US, "%.1f", it.riseDb) } ?: "",
                    candidate.walkBy?.passed?.toString() ?: "",
                    candidate.addresses.joinToString(" "),
                ).joinToString(","),
            )
        }
    }
}
