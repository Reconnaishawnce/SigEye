package com.sigeye.core.analysis.identity

import com.sigeye.core.analysis.Stats
import java.util.Locale

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

/** One run of a probe. */
data class ProbeRun(
    val kind: Probe,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** For a walk-by: when the person said they drew level. */
    val midAtMs: Long? = null,
) {
    val running: Boolean get() = endedAtMs == null

    fun durationMs(nowMs: Long): Long = (endedAtMs ?: nowMs) - startedAtMs
}

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

/** How much of a new reading goes into the smoothed level. */
private const val RECENT_ALPHA = 0.3

/** Below this many readings there is no "the whole time" to speak of. */
private const val CARRIED_MIN_PACKETS = 30

/** How much of the time a device has to be pocket-loud before it is called yours. */
private const val CARRIED_FRACTION = 0.85

/** One device a follow is considering. */
data class FollowCandidate(
    val address: String,
    val label: String?,
    val vendor: String?,
    val isRandom: Boolean,
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
    val orbit: OrbitScore? = null,
    val walkBy: WalkByScore? = null,
    /** Every reading taken during the walk-by, for drawing it. */
    val walkByTrail: List<Pair<Long, Int>> = emptyList(),
) {
    val stillIn: Boolean get() = inPool && droppedAtMs == null

    /**
     * Almost certainly something you are carrying rather than something they are.
     *
     * The device that sits in the innermost ring the entire time and never moves is the one
     * in your own bag. It survives every test by construction - it goes everywhere you go -
     * so without saying so it would sit at the top of the short list for the whole follow
     * and never be eliminated by anything.
     *
     * Needs both: loud on average across the follow, and loud right now. A device that was
     * in your pocket and has since been left behind should fall out like anything else.
     */
    fun carried(tuning: FollowTuning): Boolean =
        packets >= CARRIED_MIN_PACKETS &&
            closeFraction >= CARRIED_FRACTION &&
            recentRssi >= tuning.carriedDbm

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
            (if (orbit?.centred == true) 1 else 0) +
            (if (walkBy?.passed == true) 2 else 0) +
            (if (arrived) 1 else 0)

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

    /** Still with you because it is in your own bag, rather than because it is on them. */
    val carried: List<FollowCandidate> get() = stillIn.filter { it.carried(tuning) }

    val orbited: Boolean get() = probes.any { it.kind == Probe.ORBIT && !it.running }

    val walkedBy: Boolean get() = probes.any { it.kind == Probe.WALK_BY && !it.running }

    val runningProbe: ProbeRun? get() = probes.lastOrNull { it.running }

    /** Still with you, and the circle agreed. */
    val centred: Int get() = stillIn.count { it.orbit?.centred == true }

    /** Still with you, and the walk-by picked it out. */
    val passed: Int get() = stillIn.count { it.walkBy?.passed == true }

    /** Short enough to watch each one for an address change. */
    val shortlist: List<FollowCandidate>
        get() = stillIn.takeIf { it.size in 1..tuning.shortlistMax }.orEmpty()

    val narrowed: Boolean get() = shortlist.isNotEmpty()

    /** Short enough to be worth saving somewhere, even if not yet a short list. */
    val listable: Boolean get() = stillIn.size in 1..tuning.listableAt

    val runningForMs: Long
        get() = followStartedAtMs?.let { (atMs - it).coerceAtLeast(0L) } ?: 0L

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
        val addresses: MutableList<String> = mutableListOf()

        var inPool: Boolean = false
        var droppedAtMs: Long? = null
        var returnedAtMs: Long? = null
        var arrived: Boolean = false

        /** Arcs of the circle this device was heard in, and the levels it was heard at. */
        val orbitArcs: MutableSet<Int> = mutableSetOf()
        val orbitRssi: MutableList<Int> = mutableListOf()

        /** Every reading during the walk-by, in time order. */
        val walkByTrail: MutableList<Pair<Long, Int>> = mutableListOf()

        val meanRssi: Double get() = if (packets == 0) -127.0 else rssiTotal / packets
    }

    private val tracked = LinkedHashMap<String, Tracked>()
    private val probes = mutableListOf<ProbeRun>()

    private var targetKey: String? = null
    private val targetChanges = mutableListOf<Long>()

    private var baselineStartedAtMs: Long? = null
    private var baselineEndedAtMs: Long? = null
    private var followStartedAtMs: Long? = null

    var phase: FollowPhase = FollowPhase.IDLE
        private set

    // ------------------------------------------------------------------- listening

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        label: String?,
        vendor: String?,
        isRandom: Boolean,
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

        probes.lastOrNull()?.takeIf { it.running }?.let { probe ->
            when (probe.kind) {
                Probe.ORBIT -> {
                    entry.orbitArcs.add(arcOf(probe, atMs))
                    entry.orbitRssi.add(rssi)
                }

                Probe.WALK_BY -> entry.walkByTrail.add(atMs to rssi)
            }
        }
    }

    // ---------------------------------------------------------------------- stages

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
    fun endBaseline(atMs: Long) {
        baselineEndedAtMs = atMs
    }

    /**
     * Starts following, and closes the pool.
     *
     * Everything heard up to this instant is in; everything first heard after it is not.
     * That is the whole reason the number can only fall.
     */
    fun startFollowing(atMs: Long) {
        if (baselineEndedAtMs == null) baselineEndedAtMs = atMs
        followStartedAtMs = atMs
        tracked.values.forEach { entry ->
            entry.inPool = entry.firstSeenMs <= atMs
            entry.droppedAtMs = null
            entry.returnedAtMs = null
        }
        phase = FollowPhase.FOLLOWING
    }

    /**
     * Throws the elimination away and starts again, keeping what is known about the room.
     *
     * For the case a follow cannot otherwise recover from: the target changed address
     * partway through, so the device being narrowed towards stopped existing and its
     * replacement was never in the pool. This reopens the pool to everything currently
     * audible without forgetting anything.
     */
    fun rebaseline(atMs: Long) {
        probes.clear()
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

    /** A second run of the same probe is refused; see [Probe]. */
    fun beginProbe(kind: Probe, atMs: Long) {
        if (probes.any { it.kind == kind }) return
        endProbe(atMs)
        probes.add(ProbeRun(kind, atMs))
    }

    /**
     * Marks the moment of closest approach during a walk-by.
     *
     * A tap rather than anything clever, because the person holding the phone knows exactly
     * when they drew level and no sensor on the phone does. The last tap wins - somebody who
     * taps early and corrects themselves meant the second one.
     */
    fun markClosest(atMs: Long) {
        val index = probes.indexOfLast { it.running && it.kind == Probe.WALK_BY }
        if (index < 0) return
        probes[index] = probes[index].copy(midAtMs = atMs)
    }

    fun endProbe(atMs: Long) {
        val index = probes.indexOfLast { it.running }
        if (index < 0) return
        probes[index] = probes[index].copy(endedAtMs = atMs)
    }

    // ------------------------------------------------------------------ the target

    fun lock(address: String) {
        targetKey = address.uppercase(Locale.US)
        phase = FollowPhase.HOLDING
    }

    fun unlock() {
        targetKey = null
        targetChanges.clear()
        phase = if (followStartedAtMs == null) FollowPhase.BASELINE else FollowPhase.FOLLOWING
    }

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
        entry.orbitArcs.addAll(previous.orbitArcs)
        entry.orbitRssi.addAll(previous.orbitRssi)
        entry.walkByTrail.addAll(previous.walkByTrail)
        entry.closeReadings += previous.closeReadings
        entry.arrived = previous.arrived
        // The new address inherits the old one's place in the pool. A rotation is the one
        // way a device can legitimately appear mid-follow and still be the thing you were
        // already following.
        entry.inPool = previous.inPool
        entry.droppedAtMs = null
        entry.returnedAtMs = previous.returnedAtMs

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

    fun candidates(nowMs: Long): List<FollowCandidate> {
        val orbit = probes.firstOrNull { it.kind == Probe.ORBIT }
        val arcs = orbit?.let { arcsIn(it.durationMs(nowMs)) } ?: 0
        val walk = probes.firstOrNull { it.kind == Probe.WALK_BY }
        // Scored only once the walk is over and the middle was marked. A walk-by with no
        // middle is a handful of readings and a guess, and half a walk has no far end.
        val walkMid = walk?.midAtMs
        val walkEnd = walk?.endedAtMs

        return tracked.entries
            .filter { it.value.packets >= tuning.minPackets }
            .map { (address, entry) ->
                FollowCandidate(
                    address = address,
                    label = entry.label,
                    vendor = entry.vendor,
                    isRandom = entry.isRandom,
                    packets = entry.packets,
                    meanRssi = entry.meanRssi,
                    recentRssi = entry.recentRssi,
                    closeFraction = if (entry.packets == 0) {
                        0.0
                    } else {
                        entry.closeReadings.toDouble() / entry.packets
                    },
                    firstSeenMs = entry.firstSeenMs,
                    lastSeenMs = entry.lastSeenMs,
                    addresses = entry.addresses.toList(),
                    inPool = entry.inPool,
                    droppedAtMs = entry.droppedAtMs,
                    returnedAtMs = entry.returnedAtMs,
                    arrived = entry.arrived,
                    orbit = if (orbit == null) null else scoreOrbit(entry, arcs),
                    walkBy = if (walk == null || walkMid == null || walkEnd == null) {
                        null
                    } else {
                        WalkBy.score(
                            readings = entry.walkByTrail,
                            startMs = walk.startedAtMs,
                            midMs = walkMid,
                            endMs = walkEnd,
                        )
                    },
                    walkByTrail = entry.walkByTrail.toList(),
                )
            }
            .sortedWith(
                compareByDescending<FollowCandidate> { it.weight(nowMs) }
                    .thenByDescending { it.meanRssi },
            )
    }

    fun state(nowMs: Long): FollowState {
        sweep(nowMs)

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
                if (nowMs - silentSince > tuning.dropAfterMs) {
                    entry.droppedAtMs = silentSince + tuning.dropAfterMs
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

    /** What the circle said about one device. */
    private fun scoreOrbit(entry: Tracked, arcs: Int): OrbitScore {
        val levels = entry.orbitRssi.map { it.toDouble() }.sorted()
        return OrbitScore(
            arcsHeard = entry.orbitArcs.size,
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
