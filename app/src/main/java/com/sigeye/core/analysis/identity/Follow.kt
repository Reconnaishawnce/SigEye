package com.sigeye.core.analysis.identity

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

/** One stretch of the session: a place stood in, or a distance travelled. */
data class FollowLeg(
    val index: Int,
    val label: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    /** True when the phone was moving during it, which is what makes a leg discriminating. */
    val moving: Boolean,
) {
    val running: Boolean get() = endedAtMs == null

    fun durationMs(nowMs: Long): Long = (endedAtMs ?: nowMs) - startedAtMs
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
     */
    val weight: Int get() = legsSeen + movingLegsSeen * 2

    fun describe(): String = buildString {
        append("$legsSeen of $legsPossible legs")
        if (movingLegsSeen > 0) append(", $movingLegsSeen while moving")
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
) {
    val survivors: Int get() = candidates.count { it.survivedAll }

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

        val meanRssi: Double get() = if (packets == 0) -127.0 else rssiTotal / packets
    }

    private val tracked = LinkedHashMap<String, Tracked>()
    private val legs = mutableListOf<FollowLeg>()

    private var targetKey: String? = null

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
        val key = address.uppercase(Locale.US)
        val entry = tracked.getOrPut(key) {
            Tracked(label, vendor, isRandom, atMs).also { it.addresses.add(key) }
        }
        label?.takeIf { it.isNotBlank() }?.let { entry.label = it }
        vendor?.let { entry.vendor = it }
        entry.lastSeenMs = atMs
        entry.packets++
        entry.rssiTotal += rssi

        legs.lastOrNull()?.takeIf { it.running }?.let { leg ->
            entry.legs.add(leg.index)
            if (leg.moving) entry.movingLegs.add(leg.index)
        }
    }

    /**
     * Starts a stretch of the session.
     *
     * @param moving whether the phone is traveling during it. A standing leg cuts what
     *   walks past; a moving leg cuts everything that stayed behind, which is nearly
     *   everything.
     */
    fun beginLeg(label: String, moving: Boolean, atMs: Long) {
        endLeg(atMs)
        legs.add(FollowLeg(legs.size, label, atMs, null, moving))
        if (phase == FollowPhase.CENSUS) phase = FollowPhase.NARROWING
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
        val possible = legs.size
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
                )
            }
            .sortedWith(
                compareByDescending<FollowCandidate> { it.weight }
                    .thenByDescending { it.meanRssi },
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
        val key = newAddress.uppercase(Locale.US)
        val entry = tracked[key] ?: return
        tracked[old]?.let { previous ->
            entry.addresses.clear()
            entry.addresses.addAll(previous.addresses)
            entry.addresses.add(key)
            entry.label = entry.label ?: previous.label
            entry.legs.addAll(previous.legs)
            entry.movingLegs.addAll(previous.movingLegs)
        }
        targetChanges.add(atMs)
        targetKey = key
        phase = FollowPhase.HOLDING
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
        appendLine("leg,label,moving,started_ms,ended_ms")
        legs.forEach {
            appendLine("${it.index},${it.label.replace(',', ' ')},${it.moving}," +
                "${it.startedAtMs},${it.endedAtMs ?: ""}")
        }
        appendLine("address,label,vendor,random,legs_seen,legs_possible,moving_legs," +
            "packets,mean_rssi,addresses")
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
                    candidate.addresses.joinToString(" "),
                ).joinToString(","),
            )
        }
    }

    companion object {
        /** Below this, a device has not been heard from enough to have survived anything. */
        const val MIN_PACKETS = 3

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
