package com.sigeye.experiments.trainspotter

import kotlin.math.abs

/** One recorded bin, read back from a day's CSV. */
data class LoggedBin(
    val startMs: Long,
    val newCount: Int,
    val baseline: Double,
    val phase: Phase,
    val label: String,
) {
    val labelled: Boolean get() = label.isNotBlank()
}

/** How one candidate threshold would have done against what you actually marked. */
data class ThresholdScore(
    val factor: Double,
    val minCount: Int,
    /** Trains you marked that this threshold would have caught. */
    val caught: Int,
    /** Trains you marked that it would have missed. */
    val missed: Int,
    /** Spikes it would have produced away from anything you marked. */
    val unmarked: Int,
) {
    val marked: Int get() = caught + missed

    val catchRate: Double get() = if (marked == 0) 0.0 else caught.toDouble() / marked

    /**
     * Extra alerts per train caught, which is the cost side of the trade.
     *
     * Not called a false positive rate, because that is not what it is: an unmarked spike is
     * only a false alarm if nothing went past, and you were not standing there with a finger
     * on the button all day. See [ThresholdFit].
     */
    val extrasPerCatch: Double get() = if (caught == 0) Double.MAX_VALUE else unmarked.toDouble() / caught
}

/** What the recorded days say about where the threshold should sit. */
data class ThresholdAdvice(
    val days: Int,
    val bins: Int,
    val marked: Int,
    val scores: List<ThresholdScore>,
    val suggested: ThresholdScore?,
    val current: ThresholdScore?,
) {
    val enough: Boolean get() = marked >= ThresholdFit.MIN_MARKS
}

/**
 * Works out what the burst threshold should have been, from the trains you marked.
 *
 * Train Spotter has had a ground-truth button since it was written - press it when one
 * actually goes past - and until now the labels went into the CSV and nowhere else. Days of
 * somebody standing at a window doing the one thing a machine cannot, and the threshold
 * stayed a number I picked.
 *
 * This replays the recorded bins against every candidate threshold and reports what each
 * would have done. It is a genuine replay rather than a simulation: the count and the
 * baseline in each row are what the app actually had at that moment, so a threshold that
 * catches a train here would have caught it then.
 *
 * **The asymmetry that matters.** A marked bin with no spike is unambiguously a miss - a
 * train went past and the app said nothing. A spike with no mark is *not* unambiguously a
 * false alarm: nobody stands at a window for six days with a finger on the button, and
 * plenty of real trains go unmarked. So misses are counted as errors and unmarked spikes
 * are counted as a cost, and the two are reported separately rather than stirred into one
 * accuracy figure that would be quietly wrong.
 *
 * Pure and Android-free.
 */
object ThresholdFit {

    /** Below this many marked trains there is nothing to fit to, and it says so. */
    const val MIN_MARKS = 5

    /**
     * How far either side of a mark a spike still counts as having caught it.
     *
     * A train spans several bins and the tap lands on whichever one somebody's thumb found,
     * usually a little late. Two bins either side is about twenty seconds at the default
     * length, which covers a tap while the train is still passing and one just after it has
     * gone.
     */
    const val WINDOW_BINS = 2

    /** Candidate thresholds, from barely selective to very. */
    val FACTORS: List<Double> = generateSequence(1.5) { it + 0.25 }.takeWhile { it <= 8.0 }.toList()

    /**
     * Scores every candidate against the recorded days.
     *
     * @param bins every bin read back, in any order.
     * @param binMillis how long a bin was, for turning [WINDOW_BINS] into a time window.
     * @param currentFactor the threshold in use now, so the answer can be compared to it.
     * @param minCount the minimum burst size in use, held fixed - it is a separate knob and
     *   fitting two at once on a handful of trains would be fitting noise.
     */
    fun advise(
        bins: List<LoggedBin>,
        binMillis: Long,
        currentFactor: Double,
        minCount: Int,
    ): ThresholdAdvice {
        // Enrolment bins are bookkeeping and warm-up bins had no trustworthy baseline, so
        // neither is evidence about a threshold.
        val usable = bins.filter { it.phase == Phase.ARMED }.sortedBy { it.startMs }
        val marks = usable.filter { it.labelled }.map { it.startMs }
        val window = binMillis * WINDOW_BINS

        fun score(factor: Double): ThresholdScore {
            val spiking = usable.filter { would(it, factor, minCount) }
            val caught = marks.count { mark ->
                spiking.any { abs(it.startMs - mark) <= window }
            }
            val unmarked = spiking.count { spike ->
                marks.none { abs(spike.startMs - it) <= window }
            }
            return ThresholdScore(
                factor = factor,
                minCount = minCount,
                caught = caught,
                missed = marks.size - caught,
                unmarked = unmarked,
            )
        }

        val scores = FACTORS.map(::score)

        return ThresholdAdvice(
            days = usable.map { it.startMs / 86_400_000L }.distinct().size,
            bins = usable.size,
            marked = marks.size,
            scores = scores,
            suggested = suggest(scores),
            current = score(currentFactor),
        )
    }

    /**
     * The strictest threshold that still catches everything you marked.
     *
     * Strictest rather than best, and in that order on purpose. Missing a train is the
     * failure somebody notices and the one the button exists to prevent, so nothing that
     * misses one is offered however quiet it is. Among the ones that catch everything, the
     * highest is the quietest - and if nothing catches everything, the one that catches most
     * is offered instead, with the screen saying that is what happened.
     */
    fun suggest(scores: List<ThresholdScore>): ThresholdScore? {
        if (scores.isEmpty()) return null
        val perfect = scores.filter { it.marked > 0 && it.missed == 0 }
        if (perfect.isNotEmpty()) return perfect.maxByOrNull { it.factor }
        return scores.filter { it.marked > 0 }
            .maxWithOrNull(compareBy<ThresholdScore> { it.caught }.thenByDescending { it.factor })
    }

    /** Whether this bin would have spiked at this threshold. Same rule the app uses live. */
    private fun would(bin: LoggedBin, factor: Double, minCount: Int): Boolean {
        if (bin.newCount < minCount) return false
        return bin.newCount >= bin.baseline.coerceAtLeast(1.0) * factor
    }

    /**
     * Reads one CSV line into a bin, or null if it is not one.
     *
     * Deliberately forgiving. These files are appended to over days by an app that has been
     * updated in between, so a short row from an older version is a row to skip rather than
     * a reason to abandon the day.
     */
    fun parse(line: String): LoggedBin? {
        val parts = line.split(',')
        if (parts.size < 7) return null
        val startMs = parts[1].trim().toLongOrNull() ?: return null
        val newCount = parts[2].trim().toIntOrNull() ?: return null
        val baseline = parts[4].trim().toDoubleOrNull() ?: return null
        val phase = runCatching { Phase.valueOf(parts[6].trim().uppercase()) }.getOrNull()
            ?: return null
        return LoggedBin(
            startMs = startMs,
            newCount = newCount,
            baseline = baseline,
            phase = phase,
            label = parts.getOrNull(7)?.trim().orEmpty(),
        )
    }
}
