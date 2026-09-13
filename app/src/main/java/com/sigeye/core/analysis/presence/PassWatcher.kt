package com.sigeye.core.analysis.presence

/** Why a track that ended was not reported as a pass. */
enum class PassRejection(val label: String) {
    TOO_FEW("Too few readings"),
    NO_SHAPE("No rise and fall"),
    NO_CROSSINGS("Could not time it"),
}

data class WatcherStats(
    val tracking: Int = 0,
    val finished: Int = 0,
    val passes: Int = 0,
    val rejectedTooFew: Int = 0,
    val rejectedNoShape: Int = 0,
    val rejectedNoCrossings: Int = 0,
) {
    val rejected: Int get() = rejectedTooFew + rejectedNoShape + rejectedNoCrossings

    /**
     * Why nothing is being reported, or null if something is.
     *
     * Every branch here is a thing that looks identical from the outside - an empty screen
     * - and has a completely different cause and remedy.
     */
    fun verdict(): String? = when {
        tracking == 0 && finished == 0 ->
            "Nothing is advertising nearby at all. Without a transmitter going past there " +
                "is nothing to time."
        passes == 0 && finished == 0 ->
            "Devices are in range but none has left yet. A pass is only measurable once " +
                "it has been and gone."
        passes == 0 && rejectedTooFew >= rejected ->
            "Things are coming and going, but too briefly to time. Transmitters that " +
                "advertise slowly cannot be caught at speed - this works best on traffic " +
                "carrying chatty devices, and closer to the road."
        passes == 0 && rejectedNoShape >= rejected ->
            "Devices appear and disappear without the signal rising and falling around a " +
                "peak. That is what walking out of range looks like, rather than a pass. " +
                "Try somewhere with a clear line to the traffic."
        passes == 0 ->
            "Passes are being seen but not timed cleanly. Move closer to the line of " +
                "travel, or somewhere with less in the way."
        else -> null
    }
}

/**
 * Watches everything at once and notices what goes past.
 *
 * The previous design asked you to pick a device and then capture its pass, which requires
 * knowing which of forty anonymous addresses belongs to the car that has not arrived yet.
 * In practice traffic carries randomised addresses that appear for a few seconds and are
 * never seen again, so by the time you could identify the right one it had gone.
 *
 * So this keeps a rolling window for every address, and when one falls quiet - the device
 * has left - it asks [SpeedEstimator] whether what it left behind was pass-shaped. Nothing
 * to pick, nothing to time by hand. It also counts what it threw away and why, because an
 * empty results list otherwise looks the same whether nothing drove past or everything did
 * and none of it could be measured.
 *
 * Pure and Android-free.
 */
class PassWatcher(
    var distanceMetres: Double = 10.0,
    var pathLossExponent: Double = 2.0,
    /** How much history to keep per device. */
    private val windowMs: Long = 40_000L,
    /** Silence after which a device counts as gone. */
    private val quietMs: Long = 5_000L,
) {

    private val tracks = LinkedHashMap<String, MutableList<PassSample>>()
    private var finished = 0
    private var passes = 0
    private var rejectedTooFew = 0
    private var rejectedNoShape = 0
    private var rejectedNoCrossings = 0

    fun reset() {
        tracks.clear()
        finished = 0
        passes = 0
        rejectedTooFew = 0
        rejectedNoShape = 0
        rejectedNoCrossings = 0
    }

    fun observe(address: String, rssi: Int, atMs: Long) {
        val track = tracks.getOrPut(address) { mutableListOf() }
        track.add(PassSample(atMs, rssi))
        // Keep the window bounded so a beacon that sits there all afternoon cannot grow
        // without limit.
        val cutoff = atMs - windowMs
        while (track.isNotEmpty() && track.first().atMs < cutoff) track.removeAt(0)
    }

    /**
     * Finalises every track that has gone quiet, and returns any that were passes.
     *
     * Called on a timer rather than per packet: a device is recognised as gone by the
     * absence of packets, which no packet can tell you about.
     */
    fun tick(nowMs: Long): List<PassResult> {
        val gone = tracks.entries
            .filter { nowMs - (it.value.lastOrNull()?.atMs ?: 0L) > quietMs }
            .map { it.key }

        val found = mutableListOf<PassResult>()
        gone.forEach { address ->
            val samples = tracks.remove(address) ?: return@forEach
            finished++
            val result = SpeedEstimator.analyse(
                address = address,
                samples = samples,
                distanceMetres = distanceMetres,
                pathLossExponent = pathLossExponent,
            )
            when {
                result.quality != PassQuality.REJECTED -> {
                    passes++
                    found.add(result)
                }
                samples.size < SpeedEstimator.MIN_SAMPLES -> rejectedTooFew++
                result.reason?.contains("crossing") == true -> rejectedNoCrossings++
                else -> rejectedNoShape++
            }
        }
        return found
    }

    /** Addresses currently in range, loudest first, for showing what is being watched. */
    fun live(nowMs: Long): List<Pair<String, Int>> = tracks.entries
        .filter { nowMs - (it.value.lastOrNull()?.atMs ?: 0L) <= quietMs }
        .mapNotNull { entry -> entry.value.maxByOrNull { it.rssi }?.let { entry.key to it.rssi } }
        .sortedByDescending { it.second }

    fun stats(nowMs: Long): WatcherStats = WatcherStats(
        tracking = live(nowMs).size,
        finished = finished,
        passes = passes,
        rejectedTooFew = rejectedTooFew,
        rejectedNoShape = rejectedNoShape,
        rejectedNoCrossings = rejectedNoCrossings,
    )
}
