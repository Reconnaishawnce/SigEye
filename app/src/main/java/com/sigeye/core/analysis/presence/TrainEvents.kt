package com.sigeye.core.analysis.presence

import com.sigeye.experiments.trainspotter.Bin
import com.sigeye.experiments.trainspotter.Phase
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A run of consecutive spiking bins: one thing going past, rather than one bin of it. */
data class TrainPass(
    val startMs: Long,
    val endMs: Long,
    val bins: Int,
    val peakNew: Int,
    val totalNew: Int,
    val baseline: Double,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** How far the peak stood above what the place normally does. */
    val strength: Double get() = if (baseline <= 0.0) 0.0 else peakNew / baseline

    val hourOfDay: Int
        get() = Calendar.getInstance(TimeZone.getDefault())
            .apply { timeInMillis = startMs }
            .get(Calendar.HOUR_OF_DAY)

    fun describe(): String = String.format(
        Locale.US,
        "%d devices over %.0fs, %.1fx the usual",
        totalNew,
        durationMs / 1000.0,
        strength,
    )
}

/**
 * Turning spiking bins into things that went past.
 *
 * The detector works a bin at a time, which is right for noticing but wrong for counting:
 * a train takes longer than one bin, so a single pass arrives as a run of three or four
 * spikes and the screen showed it as three or four events. Anyone watching for an
 * afternoon got a number with no relationship to the number of trains.
 *
 * Grouping consecutive spikes into one pass also produces the two things worth knowing
 * that a bin cannot give you on its own: how long the thing took to go by, and what time
 * of day it happened.
 *
 * Pure and Android-free.
 */
class TrainLog(
    /**
     * How many quiet bins are tolerated inside one pass.
     *
     * A long train briefly dips below the threshold in the middle - a gap between
     * carriages, a stretch where nobody's phone happened to advertise - and splitting on
     * that would double the count of everything long.
     */
    private val gapTolerance: Int = 1,
) {

    private val passes = mutableListOf<TrainPass>()
    private var openStart: Long? = null
    private var openEnd = 0L
    private var openBins = 0
    private var openPeak = 0
    private var openTotal = 0
    private var openBaseline = 0.0
    private var quietRun = 0

    fun add(bin: Bin) {
        if (bin.phase == Phase.ENROLL) return

        if (bin.spike) {
            if (openStart == null) {
                openStart = bin.startMs
                openBins = 0
                openPeak = 0
                openTotal = 0
                openBaseline = bin.baseline
            }
            quietRun = 0
            openBins++
            openEnd = bin.startMs
            openTotal += bin.newCount
            if (bin.newCount > openPeak) openPeak = bin.newCount
            if (bin.baseline > openBaseline) openBaseline = bin.baseline
            return
        }

        if (openStart == null) return
        quietRun++
        if (quietRun > gapTolerance) close(bin.startMs)
    }

    /** Ends any pass still open, for when recording stops. */
    fun flush(nowMs: Long) {
        if (openStart != null) close(nowMs)
    }

    private fun close(endMs: Long) {
        val start = openStart ?: return
        passes.add(
            TrainPass(
                startMs = start,
                endMs = maxOf(endMs, openEnd),
                bins = openBins,
                peakNew = openPeak,
                totalNew = openTotal,
                baseline = openBaseline,
            ),
        )
        openStart = null
        quietRun = 0
    }

    fun passes(): List<TrainPass> = passes.toList()

    val count: Int get() = passes.size

    /** A pass is still being counted, so the display can say "one going past now". */
    val inProgress: Boolean get() = openStart != null

    val meanDurationMs: Long
        get() = if (passes.isEmpty()) 0L else passes.sumOf { it.durationMs } / passes.size

    /** Passes per hour of the day, for seeing a timetable emerge. */
    fun byHour(): Map<Int, Int> = passes.groupingBy { it.hourOfDay }.eachCount()

    /** Mean gap between one pass starting and the next, which is the service interval. */
    val meanIntervalMs: Long
        get() {
            if (passes.size < 2) return 0L
            val gaps = passes.map { it.startMs }.sorted().zipWithNext { a, b -> b - a }
            return gaps.sum() / gaps.size
        }

    fun summary(): String = when {
        passes.isEmpty() -> "Nothing has gone past yet."
        passes.size == 1 -> "One pass, " + passes.first().describe() + "."
        else -> String.format(
            Locale.US,
            "%d passes, averaging %.0fs each%s.",
            passes.size,
            meanDurationMs / 1000.0,
            if (meanIntervalMs > 0) {
                String.format(Locale.US, ", about %.0f minutes apart", meanIntervalMs / 60_000.0)
            } else {
                ""
            },
        )
    }

    fun reset() {
        passes.clear()
        openStart = null
        quietRun = 0
    }
}
