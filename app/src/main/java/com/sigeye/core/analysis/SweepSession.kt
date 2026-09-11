package com.sigeye.core.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** How well repeated sweeps agree about where the shadow is. */
enum class SweepAgreement(val label: String, val verdict: String) {
    UNKNOWN(
        "One sweep only",
        "Run it again facing the same way. A single sweep cannot tell your body from " +
            "the room.",
    ),
    CONSISTENT(
        "Sweeps agree",
        "The shadow landed in the same place each time. That is you - it turns with you " +
            "because it is attached to you.",
    ),
    MIXED(
        "Sweeps partly agree",
        "Close but not tight. Some of this is your body and some is the room. Try " +
            "standing somewhere with fewer hard surfaces.",
    ),
    SCATTERED(
        "Sweeps disagree",
        "The shadow moved between runs, so it is the room reflecting, not you absorbing. " +
            "Multipath is winning here - try an open space or a closer source.",
    ),
}

data class SessionResult(
    val runs: List<SweepResult>,
    /** Sector means across every run, so a single noisy sweep cannot dominate. */
    val combined: SweepResult,
    /** Where each run put the shadow. */
    val notchHeadings: List<Float>,
    /** Circular spread of those headings, in degrees. Low means they agree. */
    val notchSpreadDegrees: Float?,
    val agreement: SweepAgreement,
) {
    val runCount: Int get() = runs.size
}

/**
 * Several sweeps of the same spot, combined.
 *
 * One sweep cannot separate your body from the room: a reflection off a radiator makes a
 * notch too, and looks identical in a single run. The difference is that your shadow turns
 * with you and the room's does not - so the test is whether repeated sweeps put the notch
 * in the same place.
 *
 * That is the whole reason this class exists rather than just averaging: the *spread* of
 * the notch across runs is the finding, not an implementation detail.
 */
class SweepSession(private val sectorCount: Int = 24) {

    private val runs = mutableListOf<SweepResult>()

    fun add(result: SweepResult) {
        runs.add(result)
    }

    fun clear() = runs.clear()

    fun count(): Int = runs.size

    fun result(): SessionResult {
        val combined = combine()
        val headings = runs.mapNotNull { it.notch?.centreDegrees }
        val spread = circularSpread(headings)
        return SessionResult(
            runs = runs.toList(),
            combined = combined,
            notchHeadings = headings,
            notchSpreadDegrees = spread,
            agreement = when {
                runs.size < 2 || spread == null -> SweepAgreement.UNKNOWN
                spread <= 25f -> SweepAgreement.CONSISTENT
                spread <= 55f -> SweepAgreement.MIXED
                else -> SweepAgreement.SCATTERED
            },
        )
    }

    /**
     * Mean across runs by bearing, weighted by how many readings each contributed.
     *
     * By bearing rather than by sector index, which matters now that a run's resolution is
     * chosen from what its source actually delivered: a slow turn binned into 8 sectors
     * and a fast one binned into 24 do not agree about what sector 3 means, and lining
     * them up by index would have averaged 135 degrees with 52 and quietly dropped two
     * thirds of the coarse run on top.
     *
     * The combined plot is no finer than the coarsest run in it, because a session cannot
     * be more certain about direction than its worst contributor.
     */
    private fun combine(): SweepResult {
        if (runs.isEmpty()) {
            return SweepResult(emptyList(), 0, sectorCount, 0, null, null)
        }

        val resolution = runs.minOf { it.totalSectors }.coerceIn(4, 72)
        val width = 360f / resolution

        val weighted = DoubleArray(resolution)
        val weights = IntArray(resolution)
        val mins = IntArray(resolution) { Int.MAX_VALUE }
        val maxs = IntArray(resolution) { Int.MIN_VALUE }

        runs.forEach { run ->
            run.sectors.forEach { sector ->
                if (sector.samples <= 0) return@forEach
                val bearing = ((sector.centreDegrees % 360f) + 360f) % 360f
                val index = (bearing / width).toInt().coerceIn(0, resolution - 1)
                weighted[index] += sector.meanRssi * sector.samples
                weights[index] += sector.samples
                if (sector.minRssi < mins[index]) mins[index] = sector.minRssi
                if (sector.maxRssi > maxs[index]) maxs[index] = sector.maxRssi
            }
        }

        val sectors = (0 until resolution).map { index ->
            Sector(
                index = index,
                centreDegrees = index * width + width / 2f,
                samples = weights[index],
                meanRssi = if (weights[index] == 0) 0.0 else weighted[index] / weights[index],
                minRssi = if (weights[index] == 0) 0 else mins[index],
                maxRssi = if (weights[index] == 0) 0 else maxs[index],
            )
        }

        val settled = sectors.filter { it.samples >= 3 }
        return SweepResult(
            sectors = sectors,
            settledSectors = settled.size,
            totalSectors = resolution,
            totalSamples = runs.sumOf { it.totalSamples },
            peak = settled.maxByOrNull { it.meanRssi },
            notch = settled.minByOrNull { it.meanRssi },
        )
    }

    companion object {
        /**
         * Spread of a set of angles, in degrees.
         *
         * Done on the unit circle because 350 and 10 are twenty degrees apart, not three
         * hundred and forty. Returns null for fewer than two headings.
         */
        fun circularSpread(headings: List<Float>): Float? {
            if (headings.size < 2) return null
            var sumSin = 0.0
            var sumCos = 0.0
            headings.forEach { heading ->
                val radians = Math.toRadians(heading.toDouble())
                sumSin += sin(radians)
                sumCos += cos(radians)
            }
            val meanRadians = atan2(sumSin, sumCos)
            // Largest deviation from the mean direction, which is what a user cares about:
            // did any run disagree badly, not what the average disagreement was.
            return headings.maxOf { heading ->
                val radians = Math.toRadians(heading.toDouble())
                var difference = Math.toDegrees(radians - meanRadians)
                while (difference > 180) difference -= 360
                while (difference < -180) difference += 360
                abs(difference).toFloat()
            }
        }
    }
}
