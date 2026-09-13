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
    val recent: ArrayDeque<Int> = ArrayDeque(),
) {
    fun observe(rssi: Int, atMs: Long) {
        if (packets > 0) gaps.add(atMs - lastSeenMs)
        lastSeenMs = atMs
        packets++
        lastRssi = rssi
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
            bestRssi = recent.maxOrNull() ?: -127,
            intervalJitter = Fingerprint.intervalJitter(gaps, base),
            rssiSpread = Fingerprint.spread(recent.toList()),
        )
    }

    private companion object {
        /** Readings kept for the signal-continuity test. Twenty is a few seconds of talk. */
        const val RECENT_WINDOW = 20
    }
}
