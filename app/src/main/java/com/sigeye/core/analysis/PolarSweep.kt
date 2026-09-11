package com.sigeye.core.analysis

/** One heading sector's worth of readings. */
data class Sector(
    val index: Int,
    /** Middle of the sector, in compass degrees. */
    val centreDegrees: Float,
    val samples: Int,
    val meanRssi: Double,
    val minRssi: Int,
    val maxRssi: Int,
) {
    /** Enough readings to be worth believing rather than one stray packet. */
    fun isSettled(minSamples: Int): Boolean = samples >= minSamples
}

data class SweepResult(
    val sectors: List<Sector>,
    val settledSectors: Int,
    val totalSectors: Int,
    val totalSamples: Int,
    /** Strongest direction. Null until enough of the circle is covered. */
    val peak: Sector?,
    /** Weakest direction - the shadow. */
    val notch: Sector?,
) {
    val coverage: Float
        get() = if (totalSectors == 0) 0f else settledSectors.toFloat() / totalSectors

    /**
     * How much louder the best direction is than the worst, in dB.
     *
     * This is the whole measurement. A body-shaped absorber typically gives several dB;
     * a number near zero means either you did not turn, or nothing was blocking.
     */
    val frontToBackDb: Double?
        get() {
            val high = peak ?: return null
            val low = notch ?: return null
            return high.meanRssi - low.meanRssi
        }

    /**
     * Direction of the strongest region, not merely the strongest sector.
     *
     * A shadow is a wedge, not a line - a torso blocks forty degrees or so - which means
     * peak and notch are plateaus. Picking the single best sector puts the bearing at
     * whichever end of the plateau happened to win by noise, so both bearings are the
     * centroid of everything within a quarter of the range of the extreme.
     */
    val peakBearingDegrees: Float?
        get() = regionBearing(strong = true)

    /** Direction of the weakest region. See [peakBearingDegrees]. */
    val notchBearingDegrees: Float?
        get() = regionBearing(strong = false)

    /**
     * How far apart the strongest and weakest directions are, 0 to 180.
     *
     * With the phone held against your chest, facing the source gives line of sight and
     * facing away puts your torso in the path - so a genuine body shadow sits close to
     * opposite the source. A notch at some other angle is a reflection or an obstacle,
     * not you.
     */
    val peakToNotchDegrees: Float?
        get() {
            val high = peakBearingDegrees ?: return null
            val low = notchBearingDegrees ?: return null
            var difference = kotlin.math.abs(high - low) % 360f
            if (difference > 180f) difference = 360f - difference
            return difference
        }

    private fun regionBearing(strong: Boolean): Float? {
        val high = peak?.meanRssi ?: return null
        val low = notch?.meanRssi ?: return null
        val range = (high - low).coerceAtLeast(0.001)
        val margin = range * REGION_FRACTION

        val settled = sectors.filter { it.samples > 0 }
        val region = settled.filter {
            if (strong) it.meanRssi >= high - margin else it.meanRssi <= low + margin
        }
        if (region.isEmpty()) return null

        // Circular mean: 350 and 10 average to 0, not to 180.
        var sumSin = 0.0
        var sumCos = 0.0
        region.forEach {
            val radians = Math.toRadians(it.centreDegrees.toDouble())
            sumSin += Math.sin(radians)
            sumCos += Math.cos(radians)
        }
        val degrees = Math.toDegrees(Math.atan2(sumSin, sumCos)).toFloat()
        return ((degrees % 360f) + 360f) % 360f
    }

    private companion object {
        /** How far down from the extreme still counts as part of that region. */
        const val REGION_FRACTION = 0.25
    }

    /** Near-opposite peak and notch is the signature of a body rather than a room. */
    val looksLikeBodyShadow: Boolean
        get() = peakToNotchDegrees?.let { it >= 130f } == true

    /** True once there is enough of the circle to draw a conclusion from. */
    fun isUsable(minCoverage: Float = 0.75f): Boolean = coverage >= minCoverage
}

/**
 * Bins signal readings by compass heading.
 *
 * Used to find the direction in which something blocks a signal - most usefully your own
 * body, which is mostly water and absorbs 2.4 GHz well enough to leave a measurable notch
 * when it sits between the phone and the source.
 *
 * Pure and Android-free: the binning and the statistics are testable without a compass.
 */
class PolarSweep(
    val sectorCount: Int = 24,
    /** Readings needed before a sector counts as measured rather than glanced at. */
    private val minSamplesPerSector: Int = 3,
) {
    private val counts = IntArray(sectorCount)
    private val sums = LongArray(sectorCount)
    private val mins = IntArray(sectorCount) { Int.MAX_VALUE }
    private val maxs = IntArray(sectorCount) { Int.MIN_VALUE }
    private var samples = 0

    val sectorWidthDegrees: Float get() = 360f / sectorCount

    fun reset() {
        counts.fill(0)
        sums.fill(0)
        mins.fill(Int.MAX_VALUE)
        maxs.fill(Int.MIN_VALUE)
        samples = 0
    }

    fun add(headingDegrees: Float, rssi: Int) {
        val index = sectorOf(headingDegrees)
        counts[index]++
        sums[index] += rssi.toLong()
        if (rssi < mins[index]) mins[index] = rssi
        if (rssi > maxs[index]) maxs[index] = rssi
        samples++
    }

    /** Normalises any heading, including negative and over-360, into a sector. */
    fun sectorOf(headingDegrees: Float): Int {
        val normalised = ((headingDegrees % 360f) + 360f) % 360f
        return (normalised / sectorWidthDegrees).toInt().coerceIn(0, sectorCount - 1)
    }

    fun result(): SweepResult {
        val sectors = (0 until sectorCount).map { index ->
            Sector(
                index = index,
                centreDegrees = index * sectorWidthDegrees + sectorWidthDegrees / 2f,
                samples = counts[index],
                meanRssi = if (counts[index] == 0) {
                    0.0
                } else {
                    sums[index].toDouble() / counts[index]
                },
                minRssi = if (counts[index] == 0) 0 else mins[index],
                maxRssi = if (counts[index] == 0) 0 else maxs[index],
            )
        }

        val settled = sectors.filter { it.isSettled(minSamplesPerSector) }
        return SweepResult(
            sectors = sectors,
            settledSectors = settled.size,
            totalSectors = sectorCount,
            totalSamples = samples,
            // Only settled sectors can be the answer - one stray packet must not get to
            // define where the shadow is.
            peak = settled.maxByOrNull { it.meanRssi },
            notch = settled.minByOrNull { it.meanRssi },
        )
    }
}
