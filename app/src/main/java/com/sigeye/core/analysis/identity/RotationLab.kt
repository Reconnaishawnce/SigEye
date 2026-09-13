package com.sigeye.core.analysis.identity

import java.util.Locale

/** One device's track through the room, with every address it has worn. */
data class RotationTrack(
    val chainId: Int,
    val label: String,
    val vendor: String,
    val addresses: List<String>,
    val changeTimesMs: List<Long>,
    val rhythm: Rhythm,
    val weakestLink: LinkConfidence,
    val lastRssi: Int,
    val lastSeenMs: Long,
) {
    val rotations: Int get() = addresses.size - 1

    val current: String get() = addresses.last()

    fun summary(): String = buildString {
        append("$rotations rotation")
        if (rotations != 1) append("s")
        if (rhythm.measurable) append(", ").append(rhythm.describePeriod())
        if (rhythm.phaseUsable) append(", phase held")
    }
}

/** What the room as a whole is doing about address privacy. */
data class RoomRhythm(
    val periodsMs: List<Long>,
    val medianPeriodMs: Long?,
    val buckets: List<PeriodBucket>,
    val tracksWithPhase: Int,
    val tracksMeasured: Int,
) {
    val measurable: Boolean get() = medianPeriodMs != null
}

/**
 * The room, grouped by who made it and measured on how it behaves.
 *
 * Three questions, from the same stream of advertisements:
 *
 *  - who is here, by manufacturer, and what each manufacturer's fleet does about privacy;
 *  - how often the devices that do rotate actually rotate, measured rather than assumed;
 *  - whether any of them keeps a stable phase, which is an identifier that every rotation
 *    carries across intact.
 *
 * Deliberately separated from [RotationHunt], which follows one device the user picked and
 * lets them prove the claim by walking it away. This is the other half: not "is that the
 * same phone" but "what does a roomful of them do", which is a statistical question and is
 * answered as one.
 *
 * Pure and Android-free.
 */
class RotationLab(
    private val silenceMs: Long = 25_000L,
) {

    private class Seen(
        var shape: AdvertShape,
        val isRandom: Boolean,
        var payloadVendor: String?,
        var ouiVendor: String?,
        val firstSeenMs: Long,
    ) {
        var lastSeenMs: Long = firstSeenMs
        var packets: Int = 0
        var lastRssi: Int = -127
        val gaps: MutableList<Long> = mutableListOf()
        val trail: MutableList<Pair<Long, Int>> = mutableListOf()

        fun observe(rssi: Int, atMs: Long) {
            if (packets > 0) gaps.add(atMs - lastSeenMs)
            lastSeenMs = atMs
            packets++
            lastRssi = rssi
            trail.add(atMs to rssi)
            while (trail.size > TRAIL_LENGTH) trail.removeAt(0)
        }

        val intervalMs: Long get() = Fingerprint.baseIntervalMs(gaps)
    }

    private val seen = LinkedHashMap<String, Seen>()
    private val chains = ChainTracker(
        silenceMs = silenceMs,
        // The lab reports on a room it was not invited into, so only the strongest link
        // is worth drawing. A wall of "probably" about strangers is what the earlier
        // room-wide version got wrong.
        minimumConfidence = LinkConfidence.STRONG,
    )

    @Synchronized
    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        shape: AdvertShape,
        isRandom: Boolean,
        payloadVendor: String?,
        ouiVendor: String?,
    ) {
        val key = address.uppercase(Locale.US)
        val entry = seen.getOrPut(key) {
            Seen(shape, isRandom, payloadVendor, ouiVendor, atMs)
        }
        if (shape.distinctiveness > entry.shape.distinctiveness) entry.shape = shape
        payloadVendor?.let { entry.payloadVendor = it }
        ouiVendor?.let { entry.ouiVendor = it }
        entry.observe(rssi, atMs)
        chains.observe(key, rssi, atMs, shape, isRandom)
    }

    @Synchronized
    fun tick(nowMs: Long) {
        // Room-wide: every address may start a chain. The hunt screen is the one that
        // narrows to a single device the user asked about.
        chains.seed(seen.keys)
        chains.tick(nowMs)
        prune(nowMs)
    }

    val addressCount: Int get() = seen.size

    @Synchronized
    fun audible(nowMs: Long): Int = seen.count { nowMs - it.value.lastSeenMs <= silenceMs }

    @Synchronized
    fun cohorts(nowMs: Long): List<Cohort> = Cohorts.build(
        seen.entries
            .filter { nowMs - it.value.lastSeenMs <= COHORT_MEMORY_MS }
            .map { (address, entry) ->
                CohortMember(
                    address = address,
                    payloadVendor = entry.payloadVendor,
                    ouiVendor = entry.ouiVendor,
                    isRandom = entry.isRandom,
                    intervalMs = entry.intervalMs,
                    shapeKey = entry.shape.key,
                    lastSeenMs = entry.lastSeenMs,
                )
            },
    )

    /**
     * Every device seen to change address at least once.
     *
     * The change is timed at the moment the new address first spoke, not the moment the
     * old one was noticed to be gone - the second is a property of how long this app waits
     * before giving up, and would put the app's own timeout into the measurement.
     */
    @Synchronized
    fun tracks(nicknameOf: (String) -> String? = { null }): List<RotationTrack> =
        chains.chains(nicknameOf).map { chain ->
            val changeTimes = chain.links.drop(1).map { it.startedAtMs }
            val head = seen[chain.current]
            RotationTrack(
                chainId = chain.id,
                label = chain.label,
                vendor = head?.let {
                    Cohorts.vendorOf(
                        CohortMember(
                            address = chain.current,
                            payloadVendor = it.payloadVendor,
                            ouiVendor = it.ouiVendor,
                            isRandom = it.isRandom,
                            intervalMs = it.intervalMs,
                            shapeKey = it.shape.key,
                        ),
                    )
                } ?: Cohorts.UNKNOWN,
                addresses = chain.addresses,
                changeTimesMs = changeTimes,
                // The first link has no change before it, so a chain of n addresses gives
                // n-1 change times and n-2 periods. Three addresses is the first point at
                // which a period exists at all.
                rhythm = RotationRhythm.analyze(changeTimes),
                weakestLink = chain.weakestLink,
                lastRssi = chain.lastRssi,
                lastSeenMs = seen[chain.current]?.lastSeenMs ?: 0L,
            )
        }.sortedByDescending { it.rotations }

    /** The room's rotation habits, pooled across every track that produced a period. */
    @Synchronized
    fun roomRhythm(tracks: List<RotationTrack>): RoomRhythm {
        val periods = tracks.flatMap { it.rhythm.periodsMs }.filter { RotationRhythm.withinSpec(it) }
        val sorted = periods.sorted()
        return RoomRhythm(
            periodsMs = periods,
            medianPeriodMs = if (sorted.isEmpty()) null else sorted[sorted.size / 2],
            buckets = RotationRhythm.histogram(periods),
            tracksWithPhase = tracks.count { it.rhythm.phaseUsable },
            tracksMeasured = tracks.count { it.rhythm.measurable },
        )
    }

    /** Signal over time for one address, for drawing against another. */
    @Synchronized
    fun trail(address: String): List<Pair<Long, Int>> =
        seen[address.uppercase(Locale.US)]?.trail.orEmpty().toList()

    /** Every address of a track, end to end, so a rotation shows as one continuous line. */
    @Synchronized
    fun trackTrail(track: RotationTrack): List<Pair<Long, Int>> =
        track.addresses.flatMap { trail(it) }.sortedBy { it.first }

    @Synchronized
    fun csv(): String = buildString {
        appendLine("# SigEye rotation lab")
        appendLine("track,vendor,addresses,rotations,period_ms,phase_ms,phase_usable,confidence")
        tracks().forEach { track ->
            append(track.chainId).append(',')
                .append(track.vendor.replace(',', ' ')).append(',')
                .append(track.addresses.joinToString(" ")).append(',')
                .append(track.rotations).append(',')
                .append(track.rhythm.medianPeriodMs ?: "").append(',')
                .append(track.rhythm.phaseMs ?: "").append(',')
                .append(track.rhythm.phaseUsable).append(',')
                .append(track.weakestLink.name)
            appendLine()
        }
    }

    private fun prune(nowMs: Long) {
        val stale = seen.filterValues { nowMs - it.lastSeenMs > COHORT_MEMORY_MS }.keys.toList()
        stale.forEach { seen.remove(it) }
    }

    private companion object {
        /** Readings kept per address for the comparison chart. */
        const val TRAIL_LENGTH = 240

        /**
         * How long a departed address stays part of the room.
         *
         * Long enough to span several rotations, because a track is built from addresses
         * that have already gone quiet - forgetting them promptly would delete the very
         * thing being measured.
         */
        const val COHORT_MEMORY_MS = 45 * 60_000L
    }
}
