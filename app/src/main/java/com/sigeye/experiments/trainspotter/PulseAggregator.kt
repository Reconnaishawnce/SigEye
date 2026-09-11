package com.sigeye.experiments.trainspotter

/**
 * Turns a stream of BLE advertisements into per-bin counts of *newly seen* addresses,
 * and flags bins that spike above a rolling baseline.
 *
 * Why a baseline rather than a raw threshold: phones rotate their BLE addresses roughly
 * every 15 minutes, so stationary neighbours mint a steady drip of "new" addresses.
 * That drip is the baseline; a train is a sharp multiple of it.
 *
 * Not thread-safe by design - the service confines it to a single coroutine.
 */
class PulseAggregator(config: PulseConfig = PulseConfig.DEFAULT) {

    var config: PulseConfig = config
        private set

    private val lastSeen = HashMap<String, Long>()
    private val bins = ArrayDeque<Bin>()

    private var currentBinStart = Long.MIN_VALUE
    private var currentNew = 0
    private var pendingLabel: String = ""

    /** Total advertisements accepted since start, for a sanity readout. */
    var totalAdvertisements: Long = 0
        private set

    fun history(): List<Bin> = bins.toList()

    fun currentCount(): Int = currentNew

    fun activeUnique(): Int = lastSeen.size

    /** True once enough bins have closed for the baseline to mean anything. */
    fun isWarm(): Boolean = bins.size >= config.warmupBins

    /** Bins still needed before spike detection arms itself. */
    fun binsUntilWarm(): Int = (config.warmupBins - bins.size).coerceAtLeast(0)

    fun reconfigure(newConfig: PulseConfig) {
        config = newConfig
        trimHistory()
    }

    /** Marks the next closed bin - so a "Train now" tap lands on real data. */
    fun markLabel(label: String) {
        pendingLabel = label
    }

    fun reset() {
        lastSeen.clear()
        bins.clear()
        currentBinStart = Long.MIN_VALUE
        currentNew = 0
        pendingLabel = ""
        totalAdvertisements = 0
    }

    /**
     * Feeds one advertisement. Returns true if this address counted as new.
     */
    fun observe(address: String, rssi: Int, nowMs: Long): Boolean {
        if (rssi < config.rssiFloor) return false
        if (currentBinStart == Long.MIN_VALUE) currentBinStart = nowMs
        totalAdvertisements++

        val previous = lastSeen.put(address, nowMs)
        val isNew = previous == null || nowMs - previous > config.windowMillis
        if (isNew) currentNew++
        return isNew
    }

    /**
     * Closes the bin that was accumulating and starts a fresh one.
     * Returns the closed bin, or null if no bin has started yet.
     */
    fun closeBin(nowMs: Long): Bin? {
        if (currentBinStart == Long.MIN_VALUE) {
            currentBinStart = nowMs
            return null
        }
        // The clock can move backwards (NTP correction, manual change). Treat that as
        // the start of a fresh bin rather than emitting a negative-length one.
        if (nowMs < currentBinStart) {
            currentBinStart = nowMs
            currentNew = 0
            return null
        }

        forgetStaleAddresses(nowMs)

        val baseline = computeBaseline()
        val spike = isWarm() && isSpike(currentNew, baseline)
        val bin = Bin(
            startMs = currentBinStart,
            newCount = currentNew,
            activeUnique = lastSeen.size,
            baseline = baseline,
            spike = spike,
            label = pendingLabel,
        )

        bins.addLast(bin)
        trimHistory()

        currentBinStart = nowMs
        currentNew = 0
        pendingLabel = ""
        return bin
    }

    /**
     * Median of recent *non-spiking* bins. Excluding spikes keeps a long train from
     * inflating the very baseline it is supposed to stand out against.
     */
    fun computeBaseline(): Double {
        val recent = bins.asReversed()
            .asSequence()
            .take(config.baselineBins)
            .filterNot { it.spike }
            .map { it.newCount }
            .toMutableList()

        if (recent.isEmpty()) return 0.0
        recent.sort()
        val mid = recent.size / 2
        return if (recent.size % 2 == 1) {
            recent[mid].toDouble()
        } else {
            (recent[mid - 1] + recent[mid]) / 2.0
        }
    }

    fun isSpike(count: Int, baseline: Double): Boolean {
        if (count < config.spikeMinCount) return false
        // Floor the baseline at 1.0 so a dead-quiet stretch doesn't make every
        // single blip look like a 10x spike.
        return count >= baseline.coerceAtLeast(1.0) * config.spikeFactor
    }

    private fun forgetStaleAddresses(nowMs: Long) {
        val cutoff = nowMs - config.windowMillis
        val iterator = lastSeen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    private fun trimHistory() {
        while (bins.size > config.historyBins) bins.removeFirst()
    }
}
