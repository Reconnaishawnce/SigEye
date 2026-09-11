package com.blepulse.core

/**
 * All tunables in one place. Pure data - no Android types - so the aggregator
 * stays unit-testable on the JVM.
 */
data class PulseConfig(
    /** Width of one bucket on the chart / one CSV row. */
    val binSeconds: Int = 5,
    /** An address counts as "new" only if unseen for this long. */
    val windowMinutes: Int = 10,
    /** Advertisements weaker than this are ignored entirely. */
    val rssiFloor: Int = -85,
    /** How much chart history to keep in memory. */
    val historyMinutes: Int = 30,
    /** How many recent bins feed the rolling baseline (60 bins x 5s = 5 min). */
    val baselineBins: Int = 60,
    /**
     * Bins to observe before any spike can fire. Without this the very first bins
     * compare themselves against an empty baseline and every one of them looks
     * like a train. 24 bins x 5s = a two minute warm-up after you press Start.
     */
    val warmupBins: Int = 24,
    /** A bin spikes when its count is this many times the baseline... */
    val spikeFactor: Double = 3.0,
    /** ...and also at least this many devices, so quiet-hours noise can't trip it. */
    val spikeMinCount: Int = 4,
    /** Minimum gap between notifications, so one train is one buzz. */
    val alertCooldownSeconds: Int = 120,
) {
    val binMillis: Long get() = binSeconds * 1_000L
    val windowMillis: Long get() = windowMinutes * 60_000L
    val historyMillis: Long get() = historyMinutes * 60_000L
    val alertCooldownMillis: Long get() = alertCooldownSeconds * 1_000L

    /** Number of bins we keep in the ring buffer. */
    val historyBins: Int get() = (historyMillis / binMillis).toInt().coerceAtLeast(1)

    companion object {
        val DEFAULT = PulseConfig()
    }
}
