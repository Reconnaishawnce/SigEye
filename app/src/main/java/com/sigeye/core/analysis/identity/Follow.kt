package com.sigeye.core.analysis.identity

import com.sigeye.core.analysis.Stats
import java.util.Locale

/** Where a follow session has got to. */
enum class FollowPhase(val label: String) {
    /** Listening to everything, before any test has been applied. */
    CENSUS("Census"),

    /** Legs are being recorded and candidates eliminated between them. */
    NARROWING("Narrowing"),

    /** One device has been chosen and is being held onto. */
    HOLDING("Holding"),

    /** The chosen device has gone quiet and is being waited for. */
    LOST("Lost"),
}

/**
 * What kind of test a leg is.
 *
 * The kind is not decoration. Standing in a room with somebody proves nothing about which
 * device is theirs, circling them tests whether a device stays at the same distance from
 * you, and walking together tests whether it comes along. Three different questions, and a
 * screen that treats them the same is telling the person holding it that a minute in a
 * lobby is worth a mile of travel.
 */
enum class LegKind(val label: String, val weight: Int) {
    /** The opening census. Everything audible from where you are standing. */
    BASELINE("Baseline", 0),

    /** Standing about afterwards. Cuts whatever walks past, and very little else. */
    STILL("Standing still", 1),

    /** A slow circle around the person. Cuts what is not centred on them. */
    ORBIT("Circling them", 2),

    /** Walking past a person standing still. Selects rather than eliminates. */
    WALK_BY("Walking past them", 2),

    /** Walking together. Cuts everything that stayed behind, which is nearly everything. */
    TOGETHER("Walking together", 3),
    ;

    /** True when the phone travels during the leg, which is what makes it discriminating. */
    val moving: Boolean get() = this == TOGETHER
}

/** One stretch of the session: a place stood in, a circle walked, or a distance travelled. */
data class FollowLeg(
    val index: Int,
    val label: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val kind: LegKind,
) {
    val running: Boolean get() = endedAtMs == null

    val moving: Boolean get() = kind.moving

    fun durationMs(nowMs: Long): Long = (endedAtMs ?: nowMs) - startedAtMs
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

/** One device that has survived the tests so far. */
data class FollowCandidate(
    val address: String,
    val label: String?,
    val vendor: String?,
    val isRandom: Boolean,
    val legsSeen: Int,
    val legsPossible: Int,
    val movingLegsSeen: Int,
    val packets: Int,
    val meanRssi: Double,
    val lastSeenMs: Long,
    /** Addresses this device has worn during the session, oldest first. */
    val addresses: List<String>,
    /** Null until a circle has been walked. */
    val orbit: OrbitScore? = null,
    /** Null until a walk-by has been done. */
    val walkBy: WalkByScore? = null,
    /**
     * True when this device was not audible during the baseline and turned up afterwards.
     *
     * The whole point of taking a baseline with the target out of the room: whoever walks
     * in afterwards is a much shorter list than whoever is in the building.
     */
    val arrived: Boolean = false,
    val firstSeenMs: Long = 0L,
) {
    val survivedAll: Boolean get() = legsPossible > 0 && legsSeen == legsPossible

    val rotations: Int get() = addresses.size - 1

    /**
     * How much this candidate is worth, in one number.
     *
     * Moving legs count for far more than standing ones. Staying in range while two people
     * stand in a room together says only that a device is in that room; staying in range
     * across a mile of city says it is traveling with them, and there are very few things
     * that can be.
     *
     * Passing the circle is worth less than a single moving leg, deliberately. A circle is
     * one room and one minute, and a device on a shelf at the middle of it passes; a mile
     * of travel is a claim almost nothing survives by accident. The ordering that has to
     * come out of this is: came with you and centred, then came with you, then centred
     * only.
     */
    val weight: Int
        get() = legsSeen +
            movingLegsSeen * 2 +
            (if (orbit?.centred == true) 1 else 0) +
            (if (walkBy?.passed == true) 2 else 0) +
            (if (arrived) 1 else 0)

    fun describe(): String = buildString {
        append("$legsSeen of $legsPossible legs")
        if (movingLegsSeen > 0) append(", $movingLegsSeen while moving")
        orbit?.let { append(", ${it.describe()}") }
        walkBy?.let { append(", ${it.describe()}") }
        if (arrived) append(", arrived after the baseline")
        if (rotations > 0) append(", followed through $rotations rotation")
        if (rotations > 1) append("s")
    }
}

/**
 * What the session can honestly say right now.
 *
 * [survivors] is the whole finding, and it is a denominator rather than an answer. Forty
 * devices surviving three legs in a building means nothing at all - everybody in the
 * building survived. Two surviving after a mile of travel is a different statement, and
 * the number is what lets a reader tell the two apart.
 */
data class FollowState(
    val phase: FollowPhase,
    val legs: List<FollowLeg>,
    val candidates: List<FollowCandidate>,
    val watching: Int,
    val target: FollowCandidate?,
    val silentForMs: Long,
    val rhythm: Rhythm?,
    val expectedReturnMs: Long?,
    /** How long the session has been running, for the prompt to re-baseline. */
    val runningForMs: Long = 0L,
) {
    val survivors: Int get() = candidates.count { it.survivedAll }

    /** True once a circle has been walked, so the screen knows whether to report one. */
    val orbited: Boolean get() = legs.any { it.kind == LegKind.ORBIT }

    /** Survivors the circle also said are centred on you. */
    val centred: Int get() = candidates.count { it.survivedAll && it.orbit?.centred == true }

    val walkedBy: Boolean get() = legs.any { it.kind == LegKind.WALK_BY }

    /** Survivors the walk-by picked out. */
    val passed: Int get() = candidates.count { it.survivedAll && it.walkBy?.passed == true }

    /** Everything still in the running, best first. */
    val stillIn: List<FollowCandidate> get() = candidates.filter { it.survivedAll }

    /**
     * The short list, or empty while there are still too many for one.
     *
     * A list of forty is a room, and calling it a short list would be flattering it. Five
     * is the point at which a person can hold the whole thing in their head, look at each
     * one, and decide - and it is also the point at which it is worth spending effort per
     * device, which is what the rotation watch does.
     */
    val shortlist: List<FollowCandidate>
        get() = stillIn.takeIf { it.size in 1..FollowSession.SHORTLIST_MAX }.orEmpty()

    val narrowed: Boolean get() = shortlist.isNotEmpty()

    /**
     * True when this has been running a while and has not got anywhere.
     *
     * Almost always one thing: the target changed address partway through, so the device
     * you were narrowing towards stopped existing and its replacement missed every leg
     * before it appeared. Starting the baseline again is the fix, and nobody works that
     * out on their own while staring at a list that will not shrink.
     */
    val shouldRebaseline: Boolean
        get() = runningForMs >= FollowSession.REBASELINE_AFTER_MS && !narrowed && legs.size > 1

    /** How much the elimination has actually narrowed things down. */
    fun narrowing(): String = when {
        watching == 0 -> "Nothing heard yet."
        legs.isEmpty() -> "$watching devices in range. No test applied yet."
        survivors == watching -> "$survivors of $watching left - which is all of them, so " +
            "this has not narrowed anything yet."
        else -> "$survivors of $watching left."
    }
}

/**
 * Narrowing a room down to the one device that is traveling with you.
 *
 * The method is elimination rather than recognition, and that is what makes it work
 * without knowing anything about the target in advance. Stand still together and most of
 * what is in range stays in range - useless. Walk a mile together and almost nothing does:
 * the shops fall away, the parked cars fall away, the other passengers get off. What is
 * left after a few legs of travel is a very short list, and it contains the phone in the
 * other person's pocket.
 *
 * The honest output is that short list and its denominator, never a single name. Two
 * survivors out of two hundred after three miles is a strong claim; forty out of two
 * hundred after standing in a lobby is no claim at all, and the difference has to be
 * visible or the screen is just asserting things.
 *
 * Rotation is handled by [Following], with its refusals intact - a candidate whose address
 * changes is followed across only when the evidence is unambiguous, and dropped otherwise
 * rather than guessed at.
 *
 * Pure and Android-free.
 */
class FollowSession {

    private class Tracked(
        var label: String?,
        var vendor: String?,
        val isRandom: Boolean,
        val firstSeenMs: Long,
    ) {
        var lastSeenMs: Long = firstSeenMs
        var packets: Int = 0
        var rssiTotal: Double = 0.0
        val legs: MutableSet<Int> = mutableSetOf()
        val movingLegs: MutableSet<Int> = mutableSetOf()
        val addresses: MutableList<String> = mutableListOf()

        /** Arcs of the circle this device was heard in, and the levels it was heard at. */
        val orbitArcs: MutableSet<Int> = mutableSetOf()
        val orbitRssi: MutableList<Int> = mutableListOf()

        /** Every reading during the walk-by, in time order. */
        val walkByTrail: MutableList<Pair<Long, Int>> = mutableListOf()

        /** Not audible during the baseline, so it turned up afterwards. */
        var arrived: Boolean = false

        val meanRssi: Double get() = if (packets == 0) -127.0 else rssiTotal / packets
    }

    private val tracked = LinkedHashMap<String, Tracked>()
    private val legs = mutableListOf<FollowLeg>()

    private var targetKey: String? = null
    private var startedAtMs: Long = 0L

    /** Set when the baseline leg ends, so anything first heard later counts as an arrival. */
    private var baselineEndedAtMs: Long? = null

    /** When the person said they were closest during the walk-by. */
    private var walkByMidMs: Long? = null

    var phase: FollowPhase = FollowPhase.CENSUS
        private set

    /** Address changes of the target, for measuring its rotation rhythm. */
    private val targetChanges = mutableListOf<Long>()

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        label: String?,
        vendor: String?,
        isRandom: Boolean,
    ) {
        if (startedAtMs == 0L) startedAtMs = atMs
        val key = address.uppercase(Locale.US)
        val entry = tracked.getOrPut(key) {
            Tracked(label, vendor, isRandom, atMs).also {
                it.addresses.add(key)
                // Decided once, when the device is first heard, rather than recomputed
                // later. A device that arrives and then goes quiet is still an arrival.
                it.arrived = baselineEndedAtMs?.let { ended -> atMs > ended } ?: false
            }
        }
        label?.takeIf { it.isNotBlank() }?.let { entry.label = it }
        vendor?.let { entry.vendor = it }
        entry.lastSeenMs = atMs
        entry.packets++
        entry.rssiTotal += rssi

        legs.lastOrNull()?.takeIf { it.running }?.let { leg ->
            // The baseline is what everything else is measured against, not a test to
            // survive. Counting it would eliminate the one device the baseline exists to
            // find: if the target was out of the room, it missed that leg by definition.
            if (leg.kind != LegKind.BASELINE) entry.legs.add(leg.index)
            if (leg.moving) entry.movingLegs.add(leg.index)
            if (leg.kind == LegKind.ORBIT) {
                entry.orbitArcs.add(arcOf(leg, atMs))
                entry.orbitRssi.add(rssi)
            }
            if (leg.kind == LegKind.WALK_BY) {
                entry.walkByTrail.add(atMs to rssi)
            }
        }
    }

    /**
     * Marks the moment of closest approach during a walk-by.
     *
     * A tap rather than anything clever, because the person holding the phone knows
     * exactly when they drew level and no sensor on the phone does. Ignored outside a
     * walk-by leg, and the last tap wins - somebody who taps early and corrects themselves
     * meant the second one.
     */
    fun markClosest(atMs: Long) {
        val leg = legs.lastOrNull() ?: return
        if (leg.kind != LegKind.WALK_BY || !leg.running) return
        walkByMidMs = atMs
    }

    /**
     * Which slice of the circle a packet landed in.
     *
     * Counted from the start of the leg in fixed-length arcs rather than as a fraction of
     * the whole, because the whole is not known until the leg ends and the count has to be
     * right while it is still running.
     */
    private fun arcOf(leg: FollowLeg, atMs: Long): Int =
        ((atMs - leg.startedAtMs) / OrbitScore.ARC_MS).toInt().coerceAtLeast(0)

    /**
     * How many arcs a circle of this length has, rounded up.
     *
     * Rounded up rather than down so a lap that runs a few seconds past a boundary is not
     * scored as if the last sliver were a whole arc nobody could fill. A lap of exactly
     * eight arcs has eight, not nine: the instant the leg ends belongs to the next one.
     */
    private fun arcsIn(durationMs: Long): Int =
        ((durationMs + OrbitScore.ARC_MS - 1) / OrbitScore.ARC_MS).toInt().coerceAtLeast(0)

    /**
     * Starts a stretch of the session.
     *
     * A second circle would overwrite the first one's arcs rather than add to it, so it is
     * refused: the arcs of two circles walked at different radii are not the same
     * measurement, and silently mixing them would make the flatness test meaningless.
     */
    fun beginLeg(label: String, kind: LegKind, atMs: Long) {
        if (kind == LegKind.ORBIT && legs.any { it.kind == LegKind.ORBIT }) return
        if (kind == LegKind.WALK_BY && legs.any { it.kind == LegKind.WALK_BY }) return
        if (startedAtMs == 0L) startedAtMs = atMs
        endLeg(atMs)
        if (kind == LegKind.WALK_BY) walkByMidMs = null
        legs.add(FollowLeg(legs.size, label, atMs, null, kind))
        if (phase == FollowPhase.CENSUS && kind != LegKind.BASELINE) {
            phase = FollowPhase.NARROWING
        }
    }

    /**
     * Ends the baseline and starts counting arrivals from here.
     *
     * Separate from [endLeg] because the baseline is the only leg whose ending changes what
     * a later device means. Everything first heard after this is somebody who walked in.
     */
    fun endBaseline(atMs: Long) {
        endLeg(atMs)
        baselineEndedAtMs = atMs
        if (phase == FollowPhase.CENSUS) phase = FollowPhase.NARROWING
    }

    /**
     * Throws the legs away and starts the elimination again, keeping what is known.
     *
     * For the case the session cannot otherwise recover from: the target changed address
     * partway through, so the device being narrowed towards stopped existing and its
     * replacement has missed every leg since. Wiping leg membership puts every device back
     * on equal terms without forgetting the room.
     *
     * The baseline moment moves too, so "arrived" means arrived since this moment. That is
     * the honest reading - a device first heard an hour ago is not news now.
     */
    fun rebaseline(atMs: Long) {
        endLeg(atMs)
        legs.clear()
        walkByMidMs = null
        baselineEndedAtMs = null
        startedAtMs = atMs
        targetKey = null
        targetChanges.clear()
        phase = FollowPhase.CENSUS
        tracked.values.forEach { entry ->
            entry.legs.clear()
            entry.movingLegs.clear()
            entry.orbitArcs.clear()
            entry.orbitRssi.clear()
            entry.walkByTrail.clear()
            entry.arrived = false
        }
    }

    fun endLeg(atMs: Long) {
        val last = legs.lastOrNull() ?: return
        if (last.running) legs[legs.lastIndex] = last.copy(endedAtMs = atMs)
    }

    /**
     * Candidates, best first.
     *
     * A device only counts as surviving a leg if it was heard during it, so something that
     * dropped out of range and came back has not survived - which is the point, and is why
     * the elimination works at all.
     */
    fun candidates(nowMs: Long, minPackets: Int = MIN_PACKETS): List<FollowCandidate> {
        val possible = legs.count { it.kind != LegKind.BASELINE }
        val orbitLeg = legs.firstOrNull { it.kind == LegKind.ORBIT }
        val arcs = orbitLeg?.let { arcsIn(it.durationMs(nowMs)) } ?: 0
        val walkLeg = legs.firstOrNull { it.kind == LegKind.WALK_BY }
        // Scored only once the walk is over and the middle was marked. A walk-by with no
        // middle is three readings and a guess, and half a walk has no far end to compare.
        val walkMid = walkByMidMs
        val walkEnd = walkLeg?.endedAtMs
        return tracked.entries
            .filter { it.value.packets >= minPackets }
            .map { (address, entry) ->
                FollowCandidate(
                    address = address,
                    label = entry.label,
                    vendor = entry.vendor,
                    isRandom = entry.isRandom,
                    legsSeen = entry.legs.size,
                    legsPossible = possible,
                    movingLegsSeen = entry.movingLegs.size,
                    packets = entry.packets,
                    meanRssi = entry.meanRssi,
                    lastSeenMs = entry.lastSeenMs,
                    addresses = entry.addresses.toList(),
                    orbit = if (orbitLeg == null) null else scoreOrbit(entry, arcs),
                    walkBy = if (walkLeg == null || walkMid == null || walkEnd == null) {
                        null
                    } else {
                        WalkBy.score(
                            readings = entry.walkByTrail,
                            startMs = walkLeg.startedAtMs,
                            midMs = walkMid,
                            endMs = walkEnd,
                        )
                    },
                    arrived = entry.arrived,
                    firstSeenMs = entry.firstSeenMs,
                )
            }
            .sortedWith(
                compareByDescending<FollowCandidate> { it.weight }
                    .thenByDescending { it.meanRssi },
            )
    }

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

    fun lock(address: String) {
        targetKey = address.uppercase(Locale.US)
        phase = FollowPhase.HOLDING
    }

    fun unlock() {
        targetKey = null
        targetChanges.clear()
        phase = if (legs.isEmpty()) FollowPhase.CENSUS else FollowPhase.NARROWING
    }

    /**
     * Moves the lock onto the address the target has just put on.
     *
     * The decision is [Following]'s, with its refusals intact. This only records what it
     * was told, including the time - which is what makes a rotation rhythm measurable, and
     * therefore what makes it possible to say when the next one is due.
     */
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
     * that loses you the target is the one you were not looking at.
     *
     * The decision to call this is [Following]'s, refusals intact. This only records what
     * it was told - including when, which is what makes a rotation rhythm measurable and
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
        entry.legs.addAll(previous.legs)
        entry.movingLegs.addAll(previous.movingLegs)
        entry.orbitArcs.addAll(previous.orbitArcs)
        entry.orbitRssi.addAll(previous.orbitRssi)
        entry.walkByTrail.addAll(previous.walkByTrail)
        // An address that replaced one which was already here is not a new arrival. The
        // device arrived once, under whatever name it was wearing then.
        entry.arrived = previous.arrived

        // The old address is dropped rather than left behind. Leaving it would put the same
        // device on the list twice, once as a ghost that stopped answering, and a short list
        // with a ghost on it is a short list that is one shorter than it looks.
        tracked.remove(old)

        if (targetKey == old) {
            targetChanges.add(atMs)
            targetKey = key
            phase = FollowPhase.HOLDING
        }
        return true
    }

    fun state(nowMs: Long): FollowState {
        val candidates = candidates(nowMs)
        val target = targetKey?.let { key -> candidates.firstOrNull { it.address == key } }
        val silent = target?.let { nowMs - it.lastSeenMs } ?: 0L

        if (targetKey != null) {
            phase = if (silent > LOST_AFTER_MS) FollowPhase.LOST else FollowPhase.HOLDING
        }

        val rhythm = if (targetChanges.size >= 2) {
            RotationRhythm.analyze(targetChanges)
        } else {
            null
        }

        return FollowState(
            phase = phase,
            legs = legs.toList(),
            candidates = candidates,
            watching = tracked.size,
            target = target,
            silentForMs = silent,
            rhythm = rhythm,
            expectedReturnMs = expectedReturn(rhythm),
            runningForMs = if (startedAtMs == 0L) 0L else nowMs - startedAtMs,
        )
    }

    /**
     * When the target's next address is due, if anything is known about its rhythm.
     *
     * Measured where the session has watched two changes; otherwise the specification's
     * nine hundred second default, which is what most stacks inherit. Null when there is no
     * last change to count from at all - a target that has never rotated while being
     * watched could put on a new address at any moment, and a countdown to a made-up
     * deadline would be worse than no countdown.
     */
    private fun expectedReturn(rhythm: Rhythm?): Long? {
        val lastChange = targetChanges.lastOrNull() ?: return null
        val period = rhythm?.takeIf { it.measurable && it.regular }?.medianPeriodMs
            ?: RotationRhythm.SPEC_DEFAULT_MS
        return lastChange + period
    }

    fun csv(): String = buildString {
        appendLine("# SigEye follow session")
        appendLine("legs,${legs.size}")
        appendLine("leg,label,kind,started_ms,ended_ms")
        legs.forEach {
            appendLine("${it.index},${it.label.replace(',', ' ')},${it.kind.name}," +
                "${it.startedAtMs},${it.endedAtMs ?: ""}")
        }
        appendLine("address,label,vendor,random,legs_seen,legs_possible,moving_legs," +
            "packets,mean_rssi,orbit_arcs,orbit_arcs_total,orbit_spread_db,addresses")
        candidates(System.currentTimeMillis()).forEach { candidate ->
            appendLine(
                listOf(
                    candidate.address,
                    candidate.label?.replace(',', ' ') ?: "",
                    candidate.vendor?.replace(',', ' ') ?: "",
                    candidate.isRandom.toString(),
                    candidate.legsSeen.toString(),
                    candidate.legsPossible.toString(),
                    candidate.movingLegsSeen.toString(),
                    candidate.packets.toString(),
                    String.format(Locale.US, "%.1f", candidate.meanRssi),
                    candidate.orbit?.arcsHeard?.toString() ?: "",
                    candidate.orbit?.arcsTotal?.toString() ?: "",
                    candidate.orbit?.takeIf { !it.sparse }
                        ?.let { String.format(Locale.US, "%.1f", it.spreadDb) } ?: "",
                    candidate.addresses.joinToString(" "),
                ).joinToString(","),
            )
        }
    }

    companion object {
        /** Below this, a device has not been heard from enough to have survived anything. */
        const val MIN_PACKETS = 3

        /**
         * How many candidates count as a short list.
         *
         * Five is the number a person can hold in their head at once, look at each of, and
         * decide between. It is also the point at which it becomes worth spending real
         * effort per device, which is what the rotation watch does.
         */
        const val SHORTLIST_MAX = 5

        /**
         * How long to let a session flounder before suggesting a fresh baseline.
         *
         * Five minutes is long enough that a couple of legs have been walked and short
         * enough to be inside one rotation of the usual fifteen minute timer, so the
         * suggestion arrives while starting again is still cheap.
         */
        const val REBASELINE_AFTER_MS = 5 * 60 * 1000L

        /**
         * Silence after which the target counts as lost rather than quiet.
         *
         * Longer than the twenty seconds [Following] uses before it will look for a
         * successor, because this drives a countdown shown to a person: flipping to "lost"
         * and back over a couple of dropped packets would be worse than useless.
         */
        const val LOST_AFTER_MS = 45_000L
    }
}
