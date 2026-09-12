package com.sigeye.core.analysis

import java.util.Locale
import kotlin.math.abs

/** One reading inside a recording. */
data class Ping(val atMs: Long, val rssi: Int)

/** What a device did over the course of a recording. */
enum class Behaviour(val label: String, val meaning: String) {
    PASSED(
        "Passed by",
        "Appeared, rose to a peak, fell away and left. The shape of something going " +
            "past - a vehicle, or somebody walking through.",
    ),
    ARRIVED(
        "Arrived and stayed",
        "Turned up partway through and was still there at the end.",
    ),
    LEFT(
        "Was here and left",
        "Present from the start, gone before the end.",
    ),
    THROUGHOUT_STEADY(
        "Here the whole time, unchanging",
        "Present from start to finish with a signal that barely moved. Furniture - and " +
            "the first thing to filter out.",
    ),
    THROUGHOUT_VARYING(
        "Here the whole time, but moving",
        "Present throughout, with a signal that changed materially. Something being " +
            "carried, or something with a person near it.",
    ),
    GLIMPSED(
        "Glimpsed",
        "Too few readings to say anything. Usually a device at the edge of range.",
    ),
}

data class Track(
    val address: String,
    val label: String,
    val vendor: String?,
    val isRandom: Boolean,
    val pings: List<Ping>,
    val recordingStartMs: Long,
    val recordingEndMs: Long,
) {
    val packets: Int get() = pings.size
    val firstSeenMs: Long get() = pings.firstOrNull()?.atMs ?: recordingStartMs
    val lastSeenMs: Long get() = pings.lastOrNull()?.atMs ?: recordingStartMs

    val peakRssi: Int get() = pings.maxOfOrNull { it.rssi } ?: -127
    val floorRssi: Int get() = pings.minOfOrNull { it.rssi } ?: -127
    val rangeDb: Int get() = peakRssi - floorRssi
    val meanRssi: Double get() = if (pings.isEmpty()) 0.0 else pings.map { it.rssi }.average()

    /** Where in the recording it was first heard, 0 to 1. */
    val entryFraction: Float
        get() {
            val span = (recordingEndMs - recordingStartMs).coerceAtLeast(1L)
            return ((firstSeenMs - recordingStartMs).toFloat() / span).coerceIn(0f, 1f)
        }

    val exitFraction: Float
        get() {
            val span = (recordingEndMs - recordingStartMs).coerceAtLeast(1L)
            return ((lastSeenMs - recordingStartMs).toFloat() / span).coerceIn(0f, 1f)
        }

    val presentFromStart: Boolean get() = entryFraction <= EDGE_FRACTION
    val presentToEnd: Boolean get() = exitFraction >= 1f - EDGE_FRACTION

    /** Peak position within its own visit, 0 to 1. Middle means it went past. */
    val peakFraction: Float
        get() {
            if (pings.size < 3) return 0.5f
            val peakAt = pings.maxByOrNull { it.rssi }?.atMs ?: return 0.5f
            val span = (lastSeenMs - firstSeenMs).coerceAtLeast(1L)
            return ((peakAt - firstSeenMs).toFloat() / span).coerceIn(0f, 1f)
        }

    /**
     * How it behaved.
     *
     * The order matters: a pass is the most specific thing that can be said, so it is
     * tested first, and "unchanging furniture" is the most common, so it is what anything
     * unremarkable falls through to.
     */
    val behaviour: Behaviour
        get() = when {
            packets < MIN_PACKETS -> Behaviour.GLIMPSED
            looksLikePass -> Behaviour.PASSED
            !presentFromStart && presentToEnd -> Behaviour.ARRIVED
            presentFromStart && !presentToEnd -> Behaviour.LEFT
            rangeDb >= MOVING_RANGE_DB -> Behaviour.THROUGHOUT_VARYING
            else -> Behaviour.THROUGHOUT_STEADY
        }

    /**
     * Rose to a peak somewhere in the middle and fell away again, having arrived and left.
     *
     * This is the shape the whole experiment exists to find. Requiring the peak to be in
     * the middle rather than at either end is what separates something passing from
     * something that simply walked into range and stopped, which otherwise look identical
     * in a list of signal strengths.
     */
    val looksLikePass: Boolean
        get() {
            if (packets < MIN_PACKETS) return false
            if (presentFromStart && presentToEnd) return false
            if (rangeDb < PASS_AMPLITUDE_DB) return false
            if (peakFraction < 0.2f || peakFraction > 0.8f) return false
            val before = pings.takeWhile { it.atMs <= peakAtMs }
            val after = pings.dropWhile { it.atMs < peakAtMs }
            val rise = peakRssi - (before.minOfOrNull { it.rssi } ?: peakRssi)
            val fall = peakRssi - (after.minOfOrNull { it.rssi } ?: peakRssi)
            return rise >= PASS_SIDE_DB && fall >= PASS_SIDE_DB
        }

    val peakAtMs: Long get() = pings.maxByOrNull { it.rssi }?.atMs ?: firstSeenMs

    fun summary(): String = String.format(
        Locale.US,
        "%d packets, %d to %d dBm, %.0fs",
        packets,
        floorRssi,
        peakRssi,
        (lastSeenMs - firstSeenMs) / 1000.0,
    )

    companion object {
        /** Within this much of either end counts as having been there all along. */
        const val EDGE_FRACTION = 0.1f

        const val MIN_PACKETS = 4

        /** Signal swing above which something present throughout was not sitting still. */
        const val MOVING_RANGE_DB = 12

        /** A pass has to actually rise and fall by this much overall. */
        const val PASS_AMPLITUDE_DB = 10

        /** ...and by this much on each side of the peak. */
        const val PASS_SIDE_DB = 6
    }
}

/** Ways of cutting down a recording to the thing you are looking for. */
data class ForensicFilter(
    /** Drop anything present for the whole recording without changing. */
    val hideFurniture: Boolean = false,
    /** Drop anything seen in the reference set - a previous recording, or a baseline. */
    val hideKnown: Boolean = false,
    /** Keep only things that rose and fell, which is what going past looks like. */
    val passesOnly: Boolean = false,
    /** Drop randomised addresses. */
    val fixedOnly: Boolean = false,
    val minPackets: Int = 0,
)

enum class ForensicSort(val label: String) {
    ARRIVAL("When it appeared"),
    STRENGTH("How close it got"),
    VARIATION("How much it moved"),
    PACKETS("How much it said"),
}

/**
 * A recording, and the questions you ask it afterwards.
 *
 * Watching a busy street live is hopeless: two hundred devices scroll past and the one that
 * mattered is three screens up by the time you notice it. So this records everything and
 * does the looking afterwards, when you know what you are looking for.
 *
 * The filters are the useful part, and they are all subtractive. Two hundred devices minus
 * everything that sat there unchanged, minus everything you had already seen at home,
 * minus everything that never rose and fell, is usually a handful - and the one that drove
 * past is generally in it.
 *
 * Pure and Android-free.
 */
class ForensicRecorder(
    /** Cap on stored readings per device, so an hour on a busy street stays bounded. */
    private val maxPingsPerDevice: Int = 3_000,
) {

    private class Builder(
        var label: String,
        var vendor: String?,
        var isRandom: Boolean,
        val pings: MutableList<Ping> = mutableListOf(),
        var dropped: Int = 0,
    )

    private val builders = LinkedHashMap<String, Builder>()
    private var startedAtMs = 0L
    private var lastAtMs = 0L
    private var recording = false

    val deviceCount: Int get() = builders.size
    val packetCount: Int get() = builders.values.sumOf { it.pings.size + it.dropped }
    val isRecording: Boolean get() = recording

    fun start(nowMs: Long) {
        builders.clear()
        startedAtMs = nowMs
        lastAtMs = nowMs
        recording = true
    }

    fun stop(nowMs: Long) {
        lastAtMs = maxOf(nowMs, lastAtMs)
        recording = false
    }

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        label: String? = null,
        vendor: String? = null,
        isRandom: Boolean = false,
    ) {
        if (!recording || atMs < startedAtMs) return
        val key = address.uppercase(Locale.US)
        val builder = builders.getOrPut(key) { Builder(label ?: key, vendor, isRandom) }
        label?.takeIf { it.isNotBlank() }?.let { builder.label = it }
        vendor?.let { builder.vendor = it }
        builder.isRandom = isRandom

        if (builder.pings.size >= maxPingsPerDevice) {
            // Thin rather than stop: drop every other stored reading and carry on, so a
            // long recording keeps the shape of the whole thing instead of the first
            // few minutes of it in detail and nothing after.
            val kept = builder.pings.filterIndexed { index, _ -> index % 2 == 0 }
            builder.dropped += builder.pings.size - kept.size
            builder.pings.clear()
            builder.pings.addAll(kept)
        }
        builder.pings.add(Ping(atMs, rssi))
        if (atMs > lastAtMs) lastAtMs = atMs
    }

    fun tracks(): List<Track> = builders.map { (address, builder) ->
        Track(
            address = address,
            label = builder.label,
            vendor = builder.vendor,
            isRandom = builder.isRandom,
            pings = builder.pings.toList(),
            recordingStartMs = startedAtMs,
            recordingEndMs = lastAtMs,
        )
    }

    val spanMs: Long get() = (lastAtMs - startedAtMs).coerceAtLeast(0L)

    /** Applies a filter and a sort, which is the whole review workflow. */
    fun review(
        filter: ForensicFilter = ForensicFilter(),
        sort: ForensicSort = ForensicSort.ARRIVAL,
        known: Set<String> = emptySet(),
    ): List<Track> {
        val knownUpper = known.map { it.uppercase(Locale.US) }.toSet()
        return tracks()
            .filter { it.packets >= filter.minPackets }
            .filter { !(filter.hideFurniture && it.behaviour == Behaviour.THROUGHOUT_STEADY) }
            .filter { !(filter.hideKnown && knownUpper.contains(it.address)) }
            .filter { !(filter.passesOnly && !it.looksLikePass) }
            .filter { !(filter.fixedOnly && it.isRandom) }
            .let { tracks ->
                when (sort) {
                    ForensicSort.ARRIVAL -> tracks.sortedBy { it.firstSeenMs }
                    ForensicSort.STRENGTH -> tracks.sortedByDescending { it.peakRssi }
                    ForensicSort.VARIATION -> tracks.sortedByDescending { it.rangeDb }
                    ForensicSort.PACKETS -> tracks.sortedByDescending { it.packets }
                }
            }
    }

    /** How many of each behaviour, for the summary. */
    fun census(): Map<Behaviour, Int> =
        tracks().groupingBy { it.behaviour }.eachCount()

    /** Every reading, flattened, for export. */
    fun csv(): String = buildString {
        append("# SigEye forensic recording\n")
        append("# devices=").append(builders.size)
            .append(" span_ms=").append(spanMs).append('\n')
        append("elapsed_ms,address,label,rssi_dbm,behaviour\n")
        tracks().forEach { track ->
            val behaviour = track.behaviour.name
            track.pings.forEach { ping ->
                append(ping.atMs - startedAtMs).append(',')
                    .append(track.address).append(',')
                    .append(track.label.replace(',', ' ')).append(',')
                    .append(ping.rssi).append(',')
                    .append(behaviour).append('\n')
            }
        }
    }
}
