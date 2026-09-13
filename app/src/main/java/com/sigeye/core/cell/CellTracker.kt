package com.sigeye.core.cell

/** One reading of whichever cell the phone is currently camped on. */
data class CellSample(
    /** LTE, NR, WCDMA, GSM. */
    val technology: String,
    /** Operator, as MCC+MNC. */
    val operator: String?,
    /** Cell identity. Null when the modem will not say, which happens. */
    val cellId: Long?,
    /** Tracking or location area code. Changing this is a bigger move than a cell change. */
    val areaCode: Int?,
    /** Physical cell id - reused across the network, so not an identity on its own. */
    val pci: Int?,
    val channel: Int?,
    /** Reference signal power in dBm, or the closest equivalent for the technology. */
    val dbm: Int?,
    /** Android's own 0-4 bars. */
    val level: Int,
    val atMs: Long,
    /** How many other cells the modem could see at the same moment. */
    val neighbors: Int = 0,
) {
    /** What makes this a different cell from the last one. */
    val key: String
        get() = listOf(technology, operator, cellId, areaCode, pci).joinToString("|")

    val hasIdentity: Boolean get() = cellId != null || pci != null
}

/** A move from one cell to the next. */
data class Handover(
    val fromKey: String,
    val toKey: String,
    val at: CellSample,
    val previous: CellSample,
    /** How long the phone stayed on the previous cell. */
    val heldPreviousMs: Long,
    /** True when the area code changed too - a larger step across the network. */
    val areaChanged: Boolean,
)

data class CellStats(
    val current: CellSample?,
    val handovers: List<Handover>,
    val distinctCells: Int,
    val observedForMs: Long,
    val currentHeldMs: Long,
) {
    /** Handovers per hour, or null before there is enough time to mean anything. */
    val handoversPerHour: Double?
        get() {
            if (observedForMs < 60_000L) return null
            return handovers.size * 3_600_000.0 / observedForMs
        }
}

/**
 * Turns a stream of cell readings into handover events.
 *
 * Deliberately ignores signal strength when deciding whether the cell changed: a cell is
 * identified by who it is, not how loud. Strength is recorded alongside so the handover
 * can be read against it afterwards - the interesting pattern is a handover that happens
 * while signal was still strong, which usually means load balancing rather than coverage.
 *
 * Pure and Android-free, so the transition logic can be tested without a modem.
 */
class CellTracker(private val maxHandovers: Int = 500) {

    private val handovers = ArrayDeque<Handover>()
    private val seenKeys = LinkedHashSet<String>()

    /** Most recent reading of any kind, for display. May have no identity. */
    private var latest: CellSample? = null

    /**
     * Most recent reading that actually identified a cell.
     *
     * Kept separately from [latest] because modems return partial identities regularly.
     * If a blank reading were allowed to become the comparison point, the next real
     * reading would have nothing to compare against and the handover would be swallowed -
     * so a single blank would silently eat a transition.
     */
    private var lastIdentified: CellSample? = null

    // Nullable rather than a zero sentinel: zero is a legitimate timestamp, and treating
    // it as "unset" made the observation window measure zero forever.
    private var identifiedSinceMs: Long? = null
    private var startedAtMs: Long? = null

    fun reset() {
        handovers.clear()
        seenKeys.clear()
        latest = null
        lastIdentified = null
        identifiedSinceMs = null
        startedAtMs = null
    }

    /**
     * Feeds one reading. Returns the handover it caused, or null.
     *
     * A sample with no identity is recorded for display but never compared, so it can
     * neither cause a handover nor hide one.
     */
    fun observe(sample: CellSample): Handover? {
        if (startedAtMs == null) startedAtMs = sample.atMs
        latest = sample

        if (!sample.hasIdentity) return null

        val previous = lastIdentified
        if (previous == null) {
            lastIdentified = sample
            identifiedSinceMs = sample.atMs
            seenKeys.add(sample.key)
            return null
        }

        if (previous.key == sample.key) {
            // Same cell, fresher reading.
            lastIdentified = sample
            return null
        }

        val handover = Handover(
            fromKey = previous.key,
            toKey = sample.key,
            at = sample,
            previous = previous,
            heldPreviousMs = sample.atMs - (identifiedSinceMs ?: sample.atMs),
            areaChanged = previous.areaCode != null &&
                sample.areaCode != null &&
                previous.areaCode != sample.areaCode,
        )

        handovers.addLast(handover)
        while (handovers.size > maxHandovers) handovers.removeFirst()
        seenKeys.add(sample.key)

        lastIdentified = sample
        identifiedSinceMs = sample.atMs
        return handover
    }

    fun stats(nowMs: Long): CellStats = CellStats(
        // Prefer the identified cell for display; fall back to the raw reading so the
        // screen can still show the technology when identity is withheld.
        current = lastIdentified ?: latest,
        handovers = handovers.toList().asReversed(),
        distinctCells = seenKeys.size,
        observedForMs = startedAtMs?.let { nowMs - it } ?: 0L,
        currentHeldMs = identifiedSinceMs?.let { nowMs - it } ?: 0L,
    )
}
