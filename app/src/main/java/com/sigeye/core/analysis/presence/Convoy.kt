package com.sigeye.core.analysis.presence

import java.util.Locale

/** One device as it was in a saved leg. */
data class JourneyDevice(
    val address: String,
    val label: String,
    val vendor: String? = null,
    val isRandom: Boolean = false,
    val packets: Int = 0,
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val bestRssi: Int = -127,
)

/** A leg, with everything it heard, as written to disk. */
data class JourneyLeg(
    val index: Int,
    val label: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val devices: List<JourneyDevice>,
)

/** One place, or one stretch of a journey. */
data class Leg(
    val index: Int,
    val label: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
}

/** What one device did inside one leg. */
data class LegPresence(
    val legIndex: Int,
    val packets: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val bestRssi: Int,
)

enum class FollowConfidence(val label: String) {
    NONE("Not following"),
    WEAK("Seen twice"),
    NOTABLE("Worth noticing"),
    STRONG("Has been with you throughout"),
}

/** A device that has turned up in more than one leg. */
data class Follower(
    val address: String,
    val label: String,
    val vendor: String?,
    val isRandom: Boolean,
    val presences: List<LegPresence>,
    val totalLegs: Int,
    val yours: Boolean = false,
) {
    val legsShared: Int get() = presences.size

    /** Wall-clock time between first and last sighting anywhere. */
    val spanMs: Long
        get() = (presences.maxOf { it.lastSeenMs } - presences.minOf { it.firstSeenMs })
            .coerceAtLeast(0L)

    val bestRssi: Int get() = presences.maxOf { it.bestRssi }

    val everyLeg: Boolean get() = legsShared >= totalLegs && totalLegs >= 2

    /** Legs it was absent from, between the first and last it was present in. */
    val gaps: Int
        get() {
            val indices = presences.map { it.legIndex }.toSet()
            val first = indices.min()
            val last = indices.max()
            return (first..last).count { it !in indices }
        }

    /**
     * How much to make of it.
     *
     * Deliberately conservative, because the failure mode here is frightening somebody
     * about their own headphones. A randomised address can never rate above weak - it is a
     * different address every quarter of an hour, so seeing "it" twice across an hour is
     * either a coincidence or a device that is not rotating, and there is no way to tell
     * which from the outside.
     */
    val confidence: FollowConfidence
        get() = when {
            yours -> FollowConfidence.NONE
            legsShared < 2 -> FollowConfidence.NONE
            isRandom -> FollowConfidence.WEAK
            everyLeg && totalLegs >= 3 && spanMs >= LONG_SPAN_MS -> FollowConfidence.STRONG
            legsShared >= 3 -> FollowConfidence.NOTABLE
            spanMs >= LONG_SPAN_MS -> FollowConfidence.NOTABLE
            else -> FollowConfidence.WEAK
        }

    /** Why it was rated that way, in the order a person would check it. */
    fun reasons(): List<String> {
        val out = mutableListOf<String>()
        if (yours) {
            out.add("You have filed this as one of yours, so it is excluded.")
            return out
        }
        out.add("Seen in $legsShared of $totalLegs legs.")
        if (spanMs >= LONG_SPAN_MS) {
            out.add(String.format(Locale.US, "Across %.0f minutes.", spanMs / 60_000.0))
        }
        if (gaps > 0) {
            out.add("Absent for $gaps leg${if (gaps == 1) "" else "s"} in between.")
        }
        if (isRandom) {
            out.add(
                "The address is randomised, which normally changes every fifteen minutes " +
                    "or so. Seeing the same one across several legs means either it is " +
                    "not rotating - which trackers and fitted equipment often do not - or " +
                    "the legs were close enough together for one address to cover them. " +
                    "Nothing here can tell those apart.",
            )
        } else {
            out.add("The address is fixed, so this really is the same device each time.")
        }
        return out
    }

    companion object {
        /** Long enough that coincidence starts to look like company. */
        const val LONG_SPAN_MS = 20 * 60_000L
    }
}

data class ConvoyReport(
    val legs: List<Leg>,
    val followers: List<Follower>,
    val devicesSeen: Int,
) {
    val candidates: List<Follower>
        get() = followers.filter { it.confidence != FollowConfidence.NONE }
            .sortedWith(
                compareByDescending<Follower> { it.confidence.ordinal }
                    .thenByDescending { it.legsShared }
                    .thenByDescending { it.spanMs },
            )

    val strong: List<Follower> get() = candidates.filter { it.confidence == FollowConfidence.STRONG }

    /**
     * Why the answer should not be trusted yet, or null once it can be.
     *
     * The base rate is the whole problem with this experiment. Two legs recorded in the
     * same room a minute apart share everything in the room, and calling that a convoy
     * would be worse than useless - it would teach someone to distrust a reading that
     * means nothing at all.
     */
    fun verdict(): String? {
        val closed = legs.size
        return when {
            closed < 2 -> "One leg so far. Mark a new one somewhere else - this compares " +
                "places, so it needs at least two."
            legs.any { it.durationMs < MIN_LEG_MS } ->
                "At least one leg is very short. A leg needs long enough for everything " +
                    "around you to advertise at least once, or things will look absent " +
                    "that were simply quiet."
            totalSpan() < MIN_TOTAL_SPAN_MS ->
                "All of this happened inside " +
                    (totalSpan() / 60_000L) + " minutes. Devices that are simply near you " +
                    "will appear in every leg, so give it longer, and move further."
            devicesSeen < 5 ->
                "Only $devicesSeen devices in total. Somewhere this quiet cannot " +
                    "distinguish a follower from the only thing around."
            else -> null
        }
    }

    fun totalSpan(): Long {
        if (legs.isEmpty()) return 0L
        return (legs.maxOf { it.endedAtMs } - legs.minOf { it.startedAtMs }).coerceAtLeast(0L)
    }

    companion object {
        const val MIN_LEG_MS = 60_000L
        const val MIN_TOTAL_SPAN_MS = 10 * 60_000L
    }
}

/**
 * Whether anything has been coming with you.
 *
 * Record a leg in one place, another somewhere else, a third somewhere else again, and
 * anything that appears in all of them was travelling rather than living in any of them.
 * That is the whole idea, and it is much weaker than it sounds for three reasons that this
 * class exists to be honest about.
 *
 * The base rate is brutal. Two legs recorded close together in time and space share most of
 * their contents simply because they overlap, so short journeys produce long lists of
 * innocent devices. Hence the refusal to draw conclusions below a real span.
 *
 * Randomised addresses defeat it. A phone following you changes address every quarter of an
 * hour and appears as a stranger in every leg. What this can actually catch is things with
 * fixed addresses - fitted equipment, tyre sensors, cheap trackers, and anything whose
 * maker did not bother - which is a real and useful category but not the whole threat.
 *
 * And your own belongings follow you perfectly. Filing them as yours is not a nicety; it is
 * the difference between a usable list and forty rows of your own earbuds.
 *
 * Pure and Android-free.
 */
class ConvoyTracker {

    private class LegBuilder(
        val index: Int,
        var label: String,
        val startedAtMs: Long,
        var endedAtMs: Long,
    ) {
        val seen = LinkedHashMap<String, LegPresence>()
        val labels = LinkedHashMap<String, String>()
        val vendors = LinkedHashMap<String, String?>()
        val randoms = LinkedHashMap<String, Boolean>()
    }

    private val legs = mutableListOf<LegBuilder>()
    private var current: LegBuilder? = null

    val legCount: Int get() = legs.size
    val currentLabel: String? get() = current?.label
    val isRecording: Boolean get() = current != null

    /** Closes any open leg and starts a new one. */
    fun startLeg(label: String, nowMs: Long) {
        current?.let { it.endedAtMs = nowMs }
        val builder = LegBuilder(legs.size, label, nowMs, nowMs)
        legs.add(builder)
        current = builder
    }

    fun closeLeg(nowMs: Long) {
        current?.endedAtMs = nowMs
        current = null
    }

    fun reset() {
        legs.clear()
        current = null
    }

    /**
     * The whole journey in a form that can be written to disk and read back.
     *
     * A journey is hours long and the screen holding it can be closed at a traffic light,
     * so keeping it only in memory made the experiment unusable for the thing it exists
     * to do.
     */
    fun export(): List<JourneyLeg> = legs.map { leg ->
        JourneyLeg(
            index = leg.index,
            label = leg.label,
            startedAtMs = leg.startedAtMs,
            endedAtMs = leg.endedAtMs,
            devices = leg.seen.map { (address, presence) ->
                JourneyDevice(
                    address = address,
                    label = leg.labels[address] ?: address,
                    vendor = leg.vendors[address],
                    isRandom = leg.randoms[address] == true,
                    packets = presence.packets,
                    firstSeenMs = presence.firstSeenMs,
                    lastSeenMs = presence.lastSeenMs,
                    bestRssi = presence.bestRssi,
                )
            },
        )
    }

    /** Restores a journey. Any leg that was open is restored closed. */
    fun restore(journey: List<JourneyLeg>) {
        legs.clear()
        current = null
        journey.sortedBy { it.index }.forEach { saved ->
            val builder = LegBuilder(legs.size, saved.label, saved.startedAtMs, saved.endedAtMs)
            saved.devices.forEach { device ->
                val key = device.address.uppercase(Locale.US)
                builder.seen[key] = LegPresence(
                    legIndex = builder.index,
                    packets = device.packets,
                    firstSeenMs = device.firstSeenMs,
                    lastSeenMs = device.lastSeenMs,
                    bestRssi = device.bestRssi,
                )
                builder.labels[key] = device.label
                builder.vendors[key] = device.vendor
                builder.randoms[key] = device.isRandom
            }
            legs.add(builder)
        }
    }

    /**
     * Adds a leg from somewhere recorded earlier by something else.
     *
     * A snapshot taken at home last week is a perfectly good first leg, and much better
     * than one recorded five minutes ago in the same street - the whole difficulty with
     * this experiment is getting real separation between legs.
     */
    fun addLegFromSnapshot(label: String, atMs: Long, devices: List<Sighting>) {
        current?.let { it.endedAtMs = atMs }
        current = null
        val builder = LegBuilder(legs.size, label, atMs, atMs)
        devices.forEach { sighting ->
            val key = sighting.address.uppercase(Locale.US)
            builder.seen[key] = LegPresence(
                legIndex = builder.index,
                packets = sighting.sightings,
                firstSeenMs = sighting.firstSeenMs.takeIf { it > 0 } ?: atMs,
                lastSeenMs = sighting.lastSeenMs.takeIf { it > 0 } ?: atMs,
                bestRssi = sighting.bestRssi,
            )
            builder.labels[key] = sighting.label()
            builder.vendors[key] = sighting.vendor
            builder.randoms[key] = sighting.isRandom
        }
        legs.add(builder)
    }

    /** Every device in every leg, for export. */
    fun csv(): String = buildString {
        appendLine("# SigEye journey")
        appendLine("leg,leg_label,address,label,packets,best_rssi_dbm,random")
        legs.forEach { leg ->
            leg.seen.forEach { (address, presence) ->
                append(leg.index).append(',')
                    .append(leg.label.replace(',', ' ')).append(',')
                    .append(address).append(',')
                    .append((leg.labels[address] ?: address).replace(',', ' ')).append(',')
                    .append(presence.packets).append(',')
                    .append(presence.bestRssi).append(',')
                    .append(leg.randoms[address] == true)
                appendLine()
            }
        }
    }

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        label: String? = null,
        vendor: String? = null,
        isRandom: Boolean = false,
    ) {
        val leg = current ?: return
        val key = address.uppercase(Locale.US)
        val existing = leg.seen[key]
        leg.seen[key] = LegPresence(
            legIndex = leg.index,
            packets = (existing?.packets ?: 0) + 1,
            firstSeenMs = existing?.firstSeenMs ?: atMs,
            lastSeenMs = atMs,
            bestRssi = maxOf(rssi, existing?.bestRssi ?: rssi),
        )
        label?.takeIf { it.isNotBlank() }?.let { leg.labels[key] = it }
        vendor?.let { leg.vendors[key] = it }
        leg.randoms[key] = isRandom
        if (atMs > leg.endedAtMs) leg.endedAtMs = atMs
    }

    /**
     * @param mine addresses filed as belonging to the user, which follow them by design.
     * @param minPackets how many readings inside a leg count as having really been there,
     *   so a single stray packet from three streets away does not make something a
     *   travelling companion.
     */
    fun report(mine: Set<String> = emptySet(), minPackets: Int = 3): ConvoyReport {
        val mineUpper = mine.map { it.uppercase(Locale.US) }.toSet()
        val closed = legs.map {
            Leg(it.index, it.label, it.startedAtMs, it.endedAtMs)
        }

        val byAddress = LinkedHashMap<String, MutableList<LegPresence>>()
        legs.forEach { leg ->
            leg.seen.forEach { (address, presence) ->
                if (presence.packets >= minPackets) {
                    byAddress.getOrPut(address) { mutableListOf() }.add(presence)
                }
            }
        }

        val followers = byAddress
            .filterValues { it.size >= 2 }
            .map { (address, presences) ->
                Follower(
                    address = address,
                    label = legs.firstNotNullOfOrNull { it.labels[address] } ?: address,
                    vendor = legs.firstNotNullOfOrNull { it.vendors[address] },
                    isRandom = legs.any { it.randoms[address] == true },
                    presences = presences.sortedBy { it.legIndex },
                    totalLegs = legs.size,
                    yours = mineUpper.contains(address),
                )
            }

        return ConvoyReport(
            legs = closed,
            followers = followers,
            devicesSeen = legs.flatMap { it.seen.keys }.distinct().size,
        )
    }
}
