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

    /** Sectors with any reading at all, settled or not. */
    val touchedSectors: Int get() = sectors.count { it.samples > 0 }

    /**
     * How much of the circle has been visited, as opposed to measured.
     *
     * The difference matters while a sweep is running: a slow advertiser leaves most
     * sectors touched but unsettled for the whole first turn, and a screen that only shows
     * settled coverage looks broken to someone who is plainly turning round.
     */
    val touchedFraction: Float
        get() = if (totalSectors == 0) 0f else touchedSectors.toFloat() / totalSectors

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
/** One reading, kept as it arrived. */
data class HeadingSample(val atMs: Long, val headingDegrees: Float, val rssi: Int)

class PolarSweep(
    val sectorCount: Int = 24,
    /** Readings needed before a sector counts as measured rather than glanced at. */
    private val minSamplesPerSector: Int = 3,
) {
    // Readings are kept rather than accumulated straight into bins.
    //
    // Binning on arrival fixes the resolution before anything is known about how fast the
    // source talks, and that was the flaw: 24 sectors needing three readings each is 72
    // well-spread packets, which a transmitter advertising once a second cannot deliver
    // inside a turn anyone is willing to perform. Most of the circle stayed unsettled, was
    // therefore not drawn, and the experiment looked like it had captured almost nothing.
    // Keeping the readings means the resolution can be chosen afterwards, from what
    // actually arrived - and the same turn can be re-binned instead of re-walked.
    private val samples = mutableListOf<HeadingSample>()

    val sectorWidthDegrees: Float get() = 360f / sectorCount

    val sampleCount: Int get() = samples.size

    fun reset() {
        samples.clear()
        stationaryDrops = 0
        lastTimedMs = null
        lastTimedHeading = null
    }

    /**
     * Records a reading, unless the phone was not turning when it arrived.
     *
     * An exported sweep spent 36% of its packets in the seven seconds between pressing
     * start and beginning to turn, all of them piling into one sector. That direction then
     * carried half the readings in the record and looked like the most confident thing in
     * it, while every other sector went hungry - a fixed packet budget spent on the one
     * bearing that was already known.
     *
     * The measurement is how signal varies *with direction*, so a reading taken while the
     * direction is not changing adds nothing to it. Dropping them moved the peak of that
     * sweep thirty degrees and took peak-to-notch separation from 105 degrees to 135,
     * across the threshold where a shadow reads as body-shaped rather than architectural.
     *
     * @return true if the reading was kept.
     */
    fun add(headingDegrees: Float, rssi: Int, atMs: Long = NO_TIME): Boolean {
        if (atMs != NO_TIME) {
            val previousMs = lastTimedMs
            val previousHeading = lastTimedHeading
            if (previousMs != null && previousHeading != null) {
                val seconds = (atMs - previousMs) / 1000.0
                if (seconds > 0 &&
                    turnRate(previousHeading, headingDegrees, seconds) <
                    MIN_TURN_RATE_DEGREES_PER_SECOND
                ) {
                    stationaryDrops++
                    return false
                }
            }
            lastTimedMs = atMs
            lastTimedHeading = headingDegrees
        }
        samples.add(
            HeadingSample(
                atMs = if (atMs == NO_TIME) 0L else atMs,
                headingDegrees = headingDegrees,
                rssi = rssi,
            ),
        )
        return true
    }

    // Nullable rather than a zero sentinel: zero is a perfectly good timestamp, and an
    // earlier pair of trackers in this codebase got this exact thing wrong.
    private var lastTimedMs: Long? = null
    private var lastTimedHeading: Float? = null

    /** Readings discarded because the phone was not turning. */
    var stationaryDrops: Int = 0
        private set

    /** Degrees a second between two headings, taking the short way round the circle. */
    private fun turnRate(from: Float, to: Float, seconds: Double): Double {
        var delta = to - from
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return kotlin.math.abs(delta) / seconds
    }

    /** Every reading, in arrival order. For export and diagnosis. */
    fun readings(): List<HeadingSample> = samples.toList()

    /** Normalises any heading, including negative and over-360, into a sector. */
    fun sectorOf(headingDegrees: Float, count: Int = sectorCount): Int {
        val normalised = ((headingDegrees % 360f) + 360f) % 360f
        return (normalised / (360f / count)).toInt().coerceIn(0, count - 1)
    }

    fun result(): SweepResult = result(sectorCount)

    fun result(count: Int): SweepResult {
        val resolution = count.coerceIn(4, 72)
        val counts = IntArray(resolution)
        val sums = LongArray(resolution)
        val mins = IntArray(resolution) { Int.MAX_VALUE }
        val maxs = IntArray(resolution) { Int.MIN_VALUE }

        samples.forEach { sample ->
            val index = sectorOf(sample.headingDegrees, resolution)
            counts[index]++
            sums[index] += sample.rssi.toLong()
            if (sample.rssi < mins[index]) mins[index] = sample.rssi
            if (sample.rssi > maxs[index]) maxs[index] = sample.rssi
        }

        val width = 360f / resolution
        val sectors = (0 until resolution).map { index ->
            Sector(
                index = index,
                centreDegrees = index * width + width / 2f,
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
            totalSectors = resolution,
            totalSamples = samples.size,
            // Only settled sectors can be the answer - one stray packet must not get to
            // define where the shadow is.
            //
            // A three-point median was tried here as well, to stop a lone sector holding
            // four noisy readings from capturing the peak. On the sweep that prompted it
            // the median moved the peak but left the notch and the peak-to-notch
            // separation - the number the conclusion rests on - completely unchanged,
            // while destroying any notch only one sector wide. Not worth it.
            peak = settled.maxByOrNull { it.meanRssi },
            notch = settled.minByOrNull { it.meanRssi },
        )
    }

    /**
     * The finest binning this turn can actually support.
     *
     * Walks from fine to coarse and takes the first resolution where enough of the circle
     * settles. A chatty beacon keeps the full 24 sectors; a sensor that speaks once a
     * second drops to 8, which is 45 degrees a sector - blunt, but a blunt answer drawn
     * from real readings beats a fine one that is three quarters empty.
     */
    fun bestResolution(minCoverage: Float = 0.75f): Int {
        RESOLUTIONS.forEach { resolution ->
            if (result(resolution).coverage >= minCoverage) return resolution
        }
        return RESOLUTIONS.last()
    }

    /** The best result this turn supports, at the resolution that supports it. */
    fun adaptiveResult(minCoverage: Float = 0.75f): SweepResult =
        result(bestResolution(minCoverage))

    private companion object {
        /** Finest first. Each divides 360 evenly so sector centres stay tidy. */
        val RESOLUTIONS = listOf(24, 18, 12, 8)

        /**
         * Slow enough to allow a deliberate, careful turn - a full circle in three
         * minutes still counts - and fast enough to exclude standing still.
         */
        const val MIN_TURN_RATE_DEGREES_PER_SECOND = 2.0

        /** No timestamp supplied, so the turn gate cannot and does not apply. */
        const val NO_TIME = Long.MIN_VALUE
    }
}
