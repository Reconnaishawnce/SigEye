package com.sigeye.experiments.trainspotter

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Days of eight second bins, folded into one picture of what a street does.
 *
 * This experiment records a bin every eight seconds for as long as it is left running, and
 * all of that went into a CSV and nowhere else. A week of it holds the shape of a place:
 * when the first train goes, when the road gets busy, whether Sunday is different. None of
 * that is visible in a live chart of the last ten minutes.
 *
 * Ten thousand bins a day is more columns than a phone has pixels, so they are folded into
 * buckets of a few minutes. The bucket keeps the **largest** count in it rather than the
 * mean, because this experiment is about bursts: a train is thirty seconds of a hundred
 * new devices inside a quiet hour, and averaging it away would draw the hour instead of
 * the train.
 *
 * Pure and Android-free.
 */
object DayStrip {

    /** Minutes per column. Five gives 288 across a day, which fits a phone. */
    const val BUCKET_MINUTES = 5

    val COLUMNS = 24 * 60 / BUCKET_MINUTES

    /** Days kept. A fortnight is enough to see a weekly shape without a scroll bar. */
    const val MAX_DAYS = 14

    /** One day, folded. */
    data class Day(
        val label: String,
        val startMs: Long,
        /** Largest new-device count in each bucket, or null where nothing was recorded. */
        val buckets: List<Int?>,
        /** Buckets holding a bin somebody marked as a train. */
        val marked: Set<Int>,
    ) {
        val recorded: Int get() = buckets.count { it != null }

        val busiest: Int? get() = buckets.filterNotNull().maxOrNull()
    }

    /** What the whole strip is scaled against, and what it adds up to. */
    data class Strip(val days: List<Day>, val ceiling: Int, val marks: Int) {
        val empty: Boolean get() = days.isEmpty() || ceiling <= 0
    }

    /**
     * Folds recorded bins into days.
     *
     * Enrolment and warm-up bins are dropped the same way the threshold fitter drops them:
     * enrolment counts are deliberately zero and warm-up had no trustworthy baseline, so
     * drawing either would put a dark band across every morning the app was restarted.
     */
    fun build(bins: List<LoggedBin>, zone: TimeZone = TimeZone.getDefault()): Strip {
        val usable = bins.filter { it.phase == Phase.ARMED && it.startMs > 0 }
        if (usable.isEmpty()) return Strip(emptyList(), 0, 0)

        val byDay = usable.groupBy { dayStart(it.startMs, zone) }
        val days = byDay.entries
            .sortedByDescending { it.key }
            .take(MAX_DAYS)
            .map { (start, rows) ->
                val buckets = arrayOfNulls<Int>(COLUMNS)
                val marked = mutableSetOf<Int>()
                rows.forEach { bin ->
                    val column = columnOf(bin.startMs, start)
                    if (column !in 0 until COLUMNS) return@forEach
                    val held = buckets[column]
                    if (held == null || bin.newCount > held) buckets[column] = bin.newCount
                    if (bin.labelled) marked.add(column)
                }
                Day(
                    label = label(start, zone),
                    startMs = start,
                    buckets = buckets.toList(),
                    marked = marked,
                )
            }
            .sortedBy { it.startMs }

        return Strip(
            days = days,
            // Scaled to the busiest bucket anywhere in the strip, so days are comparable
            // with each other. Scaling each day to its own maximum would make a dead
            // Sunday look exactly as busy as a Monday rush.
            ceiling = days.mapNotNull { it.busiest }.maxOrNull() ?: 0,
            marks = days.sumOf { it.marked.size },
        )
    }

    /** Which column a moment falls in. */
    fun columnOf(atMs: Long, dayStartMs: Long): Int =
        ((atMs - dayStartMs) / (BUCKET_MINUTES * 60_000L)).toInt()

    /** Local midnight before a moment. */
    fun dayStart(atMs: Long, zone: TimeZone): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = atMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun label(startMs: Long, zone: TimeZone): String {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = startMs
        val day = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)
        return String.format(
            Locale.US,
            "%s %d",
            day ?: "?",
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** The hour a column starts, for an axis. */
    fun hourOf(column: Int): Int = column * BUCKET_MINUTES / 60
}
