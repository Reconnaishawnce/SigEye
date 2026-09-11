package com.sigeye.experiments.trainspotter

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
    /**
     * Opening stretch where devices are recorded but not counted.
     *
     * Without it the first bin does not measure arrivals, it enumerates the entire
     * standing population of the neighbourhood - hundreds of devices - which buries every
     * later reading under a single spike on the chart and writes a misleading row to CSV.
     */
    val enrollmentSeconds: Int = 20,
    /** After enrollment, how long to learn a baseline before arming alerts. */
    val warmupSeconds: Int = 60,
    /** How many recent bins feed the rolling baseline. */
    val baselineBins: Int = 60,
    /** A bin spikes when its count is this many times the baseline... */
    val spikeFactor: Double = 3.0,
    /** ...and also at least this many devices, so quiet-hours noise cannot trip it. */
    val spikeMinCount: Int = 4,
    /** Minimum gap between notifications, so one train is one buzz. */
    val alertCooldownSeconds: Int = 120,
    /**
     * How often to tear the scan down and start it again.
     *
     * The BLE controller keeps a finite duplicate-filter table. In a busy area, with every
     * nearby phone rotating its address, that table fills within minutes and many chipsets
     * then stop reporting genuinely new advertisers as well. Restarting the scan flushes
     * it. This is the single most important setting for keeping the app alive past the
     * first few minutes.
     */
    val scanCycleMinutes: Int = 4,
) {
    val binMillis: Long get() = binSeconds * 1_000L
    val windowMillis: Long get() = windowMinutes * 60_000L
    val historyMillis: Long get() = historyMinutes * 60_000L
    val alertCooldownMillis: Long get() = alertCooldownSeconds * 1_000L
    val scanCycleMillis: Long get() = scanCycleMinutes * 60_000L

    /** Bins spent enrolling, at least one. */
    val enrollmentBins: Int
        get() = Math.ceil(enrollmentSeconds.toDouble() / binSeconds).toInt().coerceAtLeast(1)

    /** Bins spent learning a baseline after enrollment, at least one. */
    val warmupBins: Int
        get() = Math.ceil(warmupSeconds.toDouble() / binSeconds).toInt().coerceAtLeast(1)

    /** Total bins before alerts can fire. */
    val armedAfterBins: Int get() = enrollmentBins + warmupBins

    /** Number of bins we keep in the ring buffer. */
    val historyBins: Int get() = (historyMillis / binMillis).toInt().coerceAtLeast(1)

    companion object {
        val DEFAULT = PulseConfig()
    }
}
