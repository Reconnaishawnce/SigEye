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

    /** Sector-wise mean across runs, weighted by how many readings each contributed. */
    private fun combine(): SweepResult {
        if (runs.isEmpty()) {
            return SweepResult(emptyList(), 0, sectorCount, 0, null, null)
        }

        val sectors = (0 until sectorCount).map { index ->
            var weighted = 0.0
            var weight = 0
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            runs.forEach { run ->
                val sector = run.sectors.getOrNull(index) ?: return@forEach
                if (sector.samples <= 0) return@forEach
                weighted += sector.meanRssi * sector.samples
                weight += sector.samples
                if (sector.minRssi < min) min = sector.minRssi
                if (sector.maxRssi > max) max = sector.maxRssi
            }
            Sector(
                index = index,
                centreDegrees = index * (360f / sectorCount) + (360f / sectorCount) / 2f,
                samples = weight,
                meanRssi = if (weight == 0) 0.0 else weighted / weight,
                minRssi = if (weight == 0) 0 else min,
                maxRssi = if (weight == 0) 0 else max,
            )
        }

        val settled = sectors.filter { it.samples >= 3 }
        return SweepResult(
            sectors = sectors,
            settledSectors = settled.size,
            totalSectors = sectorCount,
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
