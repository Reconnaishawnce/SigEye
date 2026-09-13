package com.sigeye.core.analysis.identity

/**
 * One address while it is still being heard from.
 *
 * The running state every piece of fingerprinting needs: what the advertisement looks
 * like, how often it arrives, how loud it is, and when it was last audible. [identity]
 * freezes that into the [Identity] the scoring works on.
 *
 * Kept open so a tracker can hang its own bookkeeping off it - [ChainTracker] adds which
 * chain an address belongs to - without either of them owning a second copy of the
 * observation logic. Two copies would drift, and the one that drifted would be the one
 * making claims about whose phone this is.
 *
 * Pure and Android-free.
 */
open class LiveAddress(
    var shape: AdvertShape,
    val isRandom: Boolean,
    val firstSeenMs: Long,
    var lastSeenMs: Long,
    var packets: Int = 0,
    val gaps: MutableList<Long> = mutableListOf(),
    var lastRssi: Int = -127,
    var bestRssi: Int = -127,
    val recent: ArrayDeque<Int> = ArrayDeque(),
) {
    fun observe(rssi: Int, atMs: Long) {
        if (packets > 0) {
            val gap = atMs - lastSeenMs
            // A gap of four minutes is a device that left the room and came back, not an
            // advertising interval, and letting those in measures absences rather than
            // firmware. The estimator takes a low percentile so a few would not have moved
            // the answer - the reason to drop them is that they are not the thing being
            // measured.
            if (gap in 1L..MAX_GAP_MS) {
                gaps.add(gap)
                // Capped, and this is the whole of the second bug. Nothing ever bounded
                // this list, identity() sorts it, and identity() is called for every
                // audible device twice a second - so half an hour in a busy place had the
                // main thread sorting hundreds of thousands of longs on every tick until
                // Android gave up on the app.
                while (gaps.size > MAX_GAPS) gaps.removeAt(0)
            }
        }
        lastSeenMs = atMs
        packets++
        lastRssi = rssi
        if (rssi > bestRssi) bestRssi = rssi
        recent.addLast(rssi)
        while (recent.size > RECENT_WINDOW) recent.removeFirst()
    }

    val recentRssi: Double get() = if (recent.isEmpty()) -127.0 else recent.average()

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
            rssiSpread = Fingerprint.spread(recent.toList()),
        )
    }

    companion object {
        /** Readings kept for the signal-continuity test. Twenty is a few seconds of talk. */
        private const val RECENT_WINDOW = 20

        /**
         * Gaps kept per address.
         *
         * Plenty for a low-percentile interval estimate, and small enough that sorting it
         * for every audible device twice a second costs nothing.
         */
        const val MAX_GAPS = 200

        /** Longer than this is an absence rather than an advertising interval. */
        const val MAX_GAP_MS = 30_000L
    }
}
