package com.sigeye.core.analysis

/** Why a packet that arrived did not end up in the sweep. */
enum class DropReason(val label: String) {
    NOT_THE_SOURCE("Different device"),
    NO_COMPASS("No compass reading"),
    NOT_TURNING("Phone was not turning"),
}

data class SweepCounters(
    /** Every advertisement the hub delivered while recording, from any device. */
    val packetsSeen: Int = 0,
    /** Those from the chosen source. */
    val packetsFromSource: Int = 0,
    /** Those actually binned. */
    val recorded: Int = 0,
    val droppedNoCompass: Int = 0,
    val droppedNotTurning: Int = 0,
    val elapsedMs: Long = 0,
    val headingUpdates: Int = 0,
    val distinctHeadings: Int = 0,
) {
    val sourceRate: Double
        get() = if (elapsedMs <= 0) 0.0 else packetsFromSource * 1000.0 / elapsedMs

    val headingRate: Double
        get() = if (elapsedMs <= 0) 0.0 else headingUpdates * 1000.0 / elapsedMs

    /**
     * The one-line answer to "why did this turn capture nothing", or null if it did.
     *
     * Ordered by what to check first. The whole point is that the previous two attempts at
     * this bug were diagnosed by reasoning about the code rather than by asking the code
     * what it saw, and both were wrong.
     */
    fun verdict(): String? = when {
        packetsSeen == 0 ->
            "No packets at all. The radio is not delivering - check Bluetooth is on and " +
                "that no other app has grabbed the scanner."
        packetsFromSource == 0 ->
            "Packets are arriving, but none from the device you picked. It may have " +
                "stopped advertising, or rotated to a new random address."
        headingUpdates == 0 ->
            "No compass readings at all. The sweep has nothing to plot against."
        distinctHeadings <= 2 ->
            "The compass barely moved. Turn your whole body rather than the phone."
        recorded == 0 && droppedNotTurning > 0 ->
            "Everything arrived, but the phone was not turning, so none of it told the " +
                "sweep anything new. Start turning."
        recorded == 0 ->
            "Packets and headings both arrived, but nothing was recorded - that is a bug " +
                "in the app rather than anything you did."
        sourceRate < 1.0 ->
            "This source sends about " + String.format(
                java.util.Locale.US,
                "%.1f",
                sourceRate,
            ) + " packets a second. Too few to fill a circle - pick a chattier device."
        else -> null
    }
}

/**
 * Counts what happened during a sweep, so a failed one can say why.
 *
 * Not statistics - bookkeeping. It exists because the capture bug in this experiment has
 * now been diagnosed twice from first principles and fixed wrongly twice; the third attempt
 * asks the running app what it actually saw.
 *
 * Pure and Android-free.
 */
class SweepDiagnostics {

    private var packetsSeen = 0
    private var packetsFromSource = 0
    private var recorded = 0
    private var droppedNoCompass = 0
    private var droppedNotTurning = 0
    private var headingUpdates = 0
    private var startedAtMs = 0L
    private var lastAtMs = 0L

    /** Coarse heading buckets touched, as a cheap "did the phone actually turn" check. */
    private val headingBuckets = mutableSetOf<Int>()

    fun reset(nowMs: Long) {
        packetsSeen = 0
        packetsFromSource = 0
        recorded = 0
        droppedNoCompass = 0
        droppedNotTurning = 0
        headingUpdates = 0
        headingBuckets.clear()
        startedAtMs = nowMs
        lastAtMs = nowMs
    }

    fun packet(nowMs: Long) {
        packetsSeen++
        lastAtMs = nowMs
    }

    fun fromSource(nowMs: Long) {
        packetsFromSource++
        lastAtMs = nowMs
    }

    fun recorded(headingDegrees: Float) {
        recorded++
        headingBuckets.add(
            (((headingDegrees % 360f) + 360f) % 360f / 30f).toInt().coerceIn(0, 11),
        )
    }

    fun dropped(reason: DropReason) {
        when (reason) {
            DropReason.NO_COMPASS -> droppedNoCompass++
            DropReason.NOT_TURNING -> droppedNotTurning++
            DropReason.NOT_THE_SOURCE -> Unit
        }
    }

    fun heading() {
        headingUpdates++
    }

    fun counters(nowMs: Long = lastAtMs): SweepCounters = SweepCounters(
        packetsSeen = packetsSeen,
        packetsFromSource = packetsFromSource,
        recorded = recorded,
        droppedNoCompass = droppedNoCompass,
        droppedNotTurning = droppedNotTurning,
        elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
        headingUpdates = headingUpdates,
        distinctHeadings = headingBuckets.size,
    )
}
