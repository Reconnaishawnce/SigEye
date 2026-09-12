package com.sigeye.core.analysis

import java.util.Locale
import kotlin.math.abs

/** One slice of time, and what the radio looked like during it. */
data class Bucket(
    val index: Int,
    val startMs: Long,
    val durationMs: Long,
    /** Distinct addresses heard in this slice. */
    val devices: Int = 0,
    /** Addresses heard here that had never been heard before. */
    val arrivals: Int = 0,
    /** Addresses that had been present and were not heard in this slice. */
    val departures: Int = 0,
    val packets: Int = 0,
    /** Devices present in this slice that are present in most slices. */
    val residents: Int = 0,
) {
    val churn: Int get() = arrivals + departures
}

/** A device that was around for long enough to have a character. */
data class Resident(
    val address: String,
    val label: String,
    val bucketsPresent: Int,
    val totalBuckets: Int,
    val meanRssi: Double,
    val rssiSpread: Double,
    /** The slice where its signal changed most abruptly, if one stands out. */
    val shiftedAtBucket: Int? = null,
    val shiftDb: Double = 0.0,
) {
    val presenceFraction: Float
        get() = if (totalBuckets == 0) 0f else bucketsPresent.toFloat() / totalBuckets

    /** Here essentially the whole time: furniture rather than traffic. */
    val fixture: Boolean get() = presenceFraction >= 0.8f

    val moved: Boolean get() = shiftedAtBucket != null
}

data class PlaceReport(
    val buckets: List<Bucket>,
    val residents: List<Resident>,
    val totalDevices: Int,
    val spanMs: Long,
) {
    val busiest: Bucket? get() = buckets.maxByOrNull { it.devices }
    val quietest: Bucket? get() = buckets.filter { it.packets > 0 }.minByOrNull { it.devices }
    val mostChurn: Bucket? get() = buckets.maxByOrNull { it.churn }

    val fixtures: List<Resident> get() = residents.filter { it.fixture }
    val movers: List<Resident>
        get() = residents.filter { it.moved }.sortedByDescending { abs(it.shiftDb) }

    val medianDevices: Double
        get() {
            val counts = buckets.filter { it.packets > 0 }.map { it.devices }.sorted()
            if (counts.isEmpty()) return 0.0
            return if (counts.size % 2 == 1) {
                counts[counts.size / 2].toDouble()
            } else {
                (counts[counts.size / 2 - 1] + counts[counts.size / 2]) / 2.0
            }
        }

    /**
     * What the recording amounts to, or why it does not amount to anything.
     *
     * A profile of twenty minutes is not a profile of a place, it is a profile of twenty
     * minutes, and saying so is more useful than printing a confident chart of noise.
     */
    fun verdict(): String? {
        val measured = buckets.count { it.packets > 0 }
        return when {
            measured == 0 -> "Nothing was recorded at all."
            measured < 3 -> "Only $measured slices have any data. A place has a shape over " +
                "hours, not minutes - leave this running much longer."
            totalDevices < 3 -> "Only $totalDevices devices were ever heard. Somewhere " +
                "this quiet has no rhythm to find."
            else -> null
        }
    }
}

/**
 * What a place does over hours, rather than what it contains right now.
 *
 * Leave a phone somewhere for an afternoon and the interesting questions stop being "what
 * is here" and become "when is it busy", "when does it empty out", and "did anything that
 * normally sits still start behaving differently". Those are questions about a time series,
 * so this bins everything into slices and reports the shape.
 *
 * The distinction that does most of the work is between traffic and fixtures. A device
 * heard in four slices out of forty is somebody walking past; one heard in thirty-eight is
 * part of the building. Only the second kind can meaningfully *change*, and a fixture whose
 * signal steps abruptly is the single most interesting thing this can find - something
 * that had been sitting still was moved.
 *
 * Pure and Android-free.
 */
class PlaceProfile(
    /** How long each slice covers. Ten minutes over four hours gives twenty-four slices. */
    var bucketMs: Long = 10 * 60_000L,
) {

    private data class Track(
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var label: String,
        val buckets: MutableSet<Int> = mutableSetOf(),
        val rssiByBucket: MutableMap<Int, MutableList<Int>> = mutableMapOf(),
        var packets: Int = 0,
    )

    private val tracks = LinkedHashMap<String, Track>()
    private var startedAtMs = 0L
    private var lastAtMs = 0L
    private var started = false

    val deviceCount: Int get() = tracks.size

    fun start(nowMs: Long) {
        tracks.clear()
        startedAtMs = nowMs
        lastAtMs = nowMs
        started = true
    }

    fun observe(address: String, rssi: Int, atMs: Long, label: String? = null) {
        if (!started || atMs < startedAtMs) return
        val key = address.uppercase(Locale.US)
        val bucket = ((atMs - startedAtMs) / bucketMs).toInt()

        val track = tracks.getOrPut(key) {
            Track(firstSeenMs = atMs, lastSeenMs = atMs, label = label ?: key)
        }
        label?.takeIf { it.isNotBlank() }?.let { track.label = it }
        track.lastSeenMs = atMs
        track.packets++
        track.buckets.add(bucket)
        track.rssiByBucket.getOrPut(bucket) { mutableListOf() }.add(rssi)
        if (atMs > lastAtMs) lastAtMs = atMs
    }

    fun report(nowMs: Long = lastAtMs): PlaceReport {
        if (!started) return PlaceReport(emptyList(), emptyList(), 0, 0)

        val span = (nowMs - startedAtMs).coerceAtLeast(0L)
        val bucketCount = (span / bucketMs).toInt() + 1

        val buckets = (0 until bucketCount).map { index ->
            val present = tracks.filterValues { it.buckets.contains(index) }
            val previous = tracks.filterValues { it.buckets.contains(index - 1) }.keys

            Bucket(
                index = index,
                startMs = startedAtMs + index * bucketMs,
                durationMs = bucketMs,
                devices = present.size,
                // First appearance anywhere, not merely absence from the slice before -
                // otherwise a device that blinks in and out is counted as arriving over
                // and over, and the churn figure becomes meaningless.
                arrivals = present.count { (_, track) -> track.buckets.min() == index },
                departures = if (index == 0) 0 else previous.count { !present.containsKey(it) },
                packets = present.values.sumOf { it.rssiByBucket[index]?.size ?: 0 },
                residents = present.count { (_, track) ->
                    track.buckets.size >= bucketCount * 0.8
                },
            )
        }

        val residents = tracks
            .filterValues { it.buckets.size >= MIN_BUCKETS_FOR_RESIDENT }
            .map { (address, track) -> residentFor(address, track, bucketCount) }
            .sortedByDescending { it.presenceFraction }

        return PlaceReport(
            buckets = buckets,
            residents = residents,
            totalDevices = tracks.size,
            spanMs = span,
        )
    }

    private fun residentFor(address: String, track: Track, bucketCount: Int): Resident {
        val means = track.rssiByBucket
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, values) -> values.average() }
            .toSortedMap()

        val all = track.rssiByBucket.values.flatten()
        val mean = all.average()
        val spread = if (all.size < 2) {
            0.0
        } else {
            kotlin.math.sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size)
        }

        // A move is one big step. Oscillation is many equal ones.
        //
        // Comparing the largest step against that device's overall spread does not
        // separate those: a link alternating between -40 and -85 has a spread of about 22
        // and a step of 45 every single slice, so it clears any multiple of its own
        // spread and gets reported as having moved, over and over. Comparing the largest
        // step against the *typical* step does separate them - something that was moved
        // has one outlier among near-zeroes, and something that swings has no outlier at
        // all.
        val deltas = mutableListOf<Double>()
        var shiftBucket: Int? = null
        var shift = 0.0
        val ordered = means.entries.toList()
        for (index in 1 until ordered.size) {
            val delta = ordered[index].value - ordered[index - 1].value
            deltas.add(abs(delta))
            if (abs(delta) > abs(shift)) {
                shift = delta
                shiftBucket = ordered[index].key
            }
        }

        val typicalStep = deltas.sorted().let { sorted ->
            when {
                sorted.isEmpty() -> 0.0
                sorted.size % 2 == 1 -> sorted[sorted.size / 2]
                else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
        // A floor, because a perfectly steady device has a typical step of zero and
        // everything is infinitely larger than nothing.
        }.coerceAtLeast(1.0)

        val significant = abs(shift) >= MEANINGFUL_SHIFT_DB &&
            abs(shift) >= typicalStep * STEP_STANDS_OUT_BY

        return Resident(
            address = address,
            label = track.label,
            bucketsPresent = track.buckets.size,
            totalBuckets = bucketCount,
            meanRssi = mean,
            rssiSpread = spread,
            shiftedAtBucket = if (significant) shiftBucket else null,
            shiftDb = if (significant) shift else 0.0,
        )
    }

    companion object {
        /** Below this a device is passing through rather than living here. */
        const val MIN_BUCKETS_FOR_RESIDENT = 3

        /**
         * A step smaller than this is the room rather than a movement.
         *
         * Multipath alone moves a stationary link several dB - which is what the fading
         * experiment exists to show - so the threshold has to clear that, and the step
         * also has to be large relative to that particular device's own spread.
         */
        const val MEANINGFUL_SHIFT_DB = 8.0

        /**
         * How far the largest step has to stand above the typical one.
         *
         * Three is enough to separate one outlier among near-zeroes from a signal that
         * steps by the same amount every slice, which is oscillation rather than movement.
         */
        const val STEP_STANDS_OUT_BY = 3.0
    }
}
