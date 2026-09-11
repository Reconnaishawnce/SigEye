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
    val neighbours: Int = 0,
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

    private var current: CellSample? = null
    private var currentSinceMs = 0L
    private var startedAtMs = 0L

    fun reset() {
        handovers.clear()
        seenKeys.clear()
        current = null
        currentSinceMs = 0L
        startedAtMs = 0L
    }

    /**
     * Feeds one reading. Returns the handover it caused, or null.
     *
     * A sample with no identity at all is recorded as the current reading but never
     * counted as a handover - modems return partial identities regularly, and treating
     * those as movement would invent handovers that never happened.
     */
    fun observe(sample: CellSample): Handover? {
        if (startedAtMs == 0L) startedAtMs = sample.atMs

        val previous = current
        if (previous == null) {
            current = sample
            currentSinceMs = sample.atMs
            if (sample.hasIdentity) seenKeys.add(sample.key)
            return null
        }

        if (previous.key == sample.key) {
            // Same cell, fresher reading.
            current = sample
            return null
        }

        if (!sample.hasIdentity || !previous.hasIdentity) {
            current = sample
            return null
        }

        val handover = Handover(
            fromKey = previous.key,
            toKey = sample.key,
            at = sample,
            previous = previous,
            heldPreviousMs = sample.atMs - currentSinceMs,
            areaChanged = previous.areaCode != null &&
                sample.areaCode != null &&
                previous.areaCode != sample.areaCode,
        )

        handovers.addLast(handover)
        while (handovers.size > maxHandovers) handovers.removeFirst()
        seenKeys.add(sample.key)

        current = sample
        currentSinceMs = sample.atMs
        return handover
    }

    fun stats(nowMs: Long): CellStats = CellStats(
        current = current,
        handovers = handovers.toList().asReversed(),
        distinctCells = seenKeys.size,
        observedForMs = if (startedAtMs == 0L) 0L else nowMs - startedAtMs,
        currentHeldMs = if (currentSinceMs == 0L) 0L else nowMs - currentSinceMs,
    )
}
