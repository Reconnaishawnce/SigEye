package com.sigeye.core.analysis.rf

import com.sigeye.core.analysis.identity.Fingerprint
import kotlin.math.roundToInt

/**
 * How much of what one device sent actually arrived.
 *
 * A transmitter advertising every hundred milliseconds should produce ten packets a
 * second. Count what turned up, divide, and the shortfall is everything that went wrong
 * between the two antennas - a busy band, distance, a wall, or the phone's own scanner
 * falling behind.
 *
 * The interval is estimated from the smallest gaps rather than the typical ones, because
 * the typical gap already contains the losses being measured. [Fingerprint.baseIntervalMs]
 * does exactly that and is reused wholesale.
 */
data class Reception(
    val address: String,
    val label: String?,
    val packets: Int,
    val windowMs: Long,
    val baseIntervalMs: Long,
    val meanRssi: Int?,
) {
    val observedPerSecond: Double
        get() = if (windowMs <= 0) 0.0 else packets * 1000.0 / windowMs

    /** What the device's own advertising interval says it should be producing. */
    val expectedPerSecond: Double?
        get() = if (baseIntervalMs <= 0) null else 1000.0 / baseIntervalMs

    /**
     * Received over sent, from zero to one.
     *
     * Capped at one: the interval estimate is a low percentile of the gaps, so a device
     * whose advertising speeds up mid-window can briefly appear to deliver more than it
     * sent, and a ratio above one would read as a measurement rather than as the artefact
     * it is.
     */
    val ratio: Double?
        get() {
            val expected = expectedPerSecond ?: return null
            if (expected <= 0.0) return null
            return (observedPerSecond / expected).coerceIn(0.0, 1.0)
        }

    /** Packets the interval says were sent and never arrived. */
    val missed: Int?
        get() {
            val expected = expectedPerSecond ?: return null
            val sent = (expected * windowMs / 1000.0).roundToInt()
            return (sent - packets).coerceAtLeast(0)
        }

    val usable: Boolean get() = baseIntervalMs > 0 && packets >= MIN_PACKETS

    fun percent(): String = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "-"

    companion object {
        /** Below this there are not enough gaps to estimate an interval from. */
        const val MIN_PACKETS = 8

        /**
         * Builds a reception figure from raw arrival times.
         *
         * The window is the span of the arrivals themselves rather than the wall clock of
         * the recording: a device that only came into range halfway through should be
         * judged on the half it was present for, not punished for the half it was absent.
         */
        fun of(
            address: String,
            label: String?,
            arrivalsMs: List<Long>,
            rssis: List<Int> = emptyList(),
        ): Reception {
            val sorted = arrivalsMs.sorted()
            val gaps = sorted.zipWithNext { a, b -> b - a }
            return Reception(
                address = address,
                label = label,
                packets = sorted.size,
                windowMs = if (sorted.size < 2) 0L else sorted.last() - sorted.first(),
                baseIntervalMs = Fingerprint.baseIntervalMs(gaps),
                meanRssi = if (rssis.isEmpty()) null else rssis.average().roundToInt(),
            )
        }

        /**
         * What the best-performing device in the room is managing.
         *
         * The absolute ratio is not worth much on its own - every phone's scanner loses
         * some packets, and how many depends on the chipset, the Android version and what
         * else is running. The best device in view is a reference measured on this phone,
         * in this second, so comparing against it removes all of that and leaves the part
         * that is about the link.
         */
        fun best(receptions: List<Reception>): Reception? =
            receptions.filter { it.usable }.maxByOrNull { it.ratio ?: 0.0 }

        /**
         * How far below the room's best this device is sitting, in percentage points.
         *
         * Null when there is nothing to compare against, which is the honest answer with
         * one device in view.
         */
        fun shortfall(reception: Reception, best: Reception?): Int? {
            val mine = reception.ratio ?: return null
            val theirs = best?.ratio ?: return null
            if (best.address == reception.address) return null
            return ((theirs - mine) * 100).roundToInt().coerceAtLeast(0)
        }

        /** A reading below this is losing enough packets to be worth explaining. */
        const val POOR_RATIO = 0.5
    }
}
