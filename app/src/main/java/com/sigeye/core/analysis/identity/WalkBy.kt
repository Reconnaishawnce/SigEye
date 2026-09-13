package com.sigeye.core.analysis.identity

import kotlin.math.abs

/**
 * What walking past somebody said about one device.
 *
 * The circle eliminates. This one selects, and the two are complementary: a circle asks
 * which devices stayed the same distance from you, and a walk-by asks which device you
 * passed. Stand a person still, start at one end of a corridor, walk past them, and stop
 * the same distance away on the other side. A device on that person is furthest at the
 * start, closest in the middle, furthest again at the end - and because the two ends are
 * the same distance, it comes back to roughly the level it started at.
 *
 * That shape is specific. Three things have to hold, and each rules out a different way of
 * being wrong:
 *
 * - **It rose.** The level in the middle has to beat both ends by a real margin. Log-distance
 *   path loss means halving the range is about six decibels, and walking past somebody at
 *   arm's length from four metres out is a much bigger change than that.
 * - **It peaked in the middle.** A device that was loudest at the start is one you walked
 *   away from; loudest at the end is one you walked towards and stopped near. Neither is
 *   the person you passed, and without this check both would pass on the rise alone.
 * - **It came back.** The two ends are the same distance from the target by construction,
 *   so a device on the target reads about the same at both. A device that ended far louder
 *   than it started is somewhere along the direction of travel, not at the middle of it.
 *
 * **What it does not rule out.** Anything else the person is carrying passes, which is fine,
 * those are also them. Anything sitting on a table beside them passes too - this finds the
 * place, and the person is standing in it. And a corridor with a door halfway along will
 * hand you a peak that is architecture rather than proximity, which is why the result is
 * one line of evidence rather than an answer.
 *
 * Pure and Android-free.
 */
data class WalkByScore(
    val packets: Int,
    /** Mean level while you were at the start mark. */
    val startDbm: Double,
    /** Best smoothed level anywhere in the walk, and when it happened. */
    val peakDbm: Double,
    val peakAtMs: Long,
    /** Mean level while you were at the end mark. */
    val endDbm: Double,
    /** When you said you were closest. */
    val midAtMs: Long,
    val walkMs: Long,
) {
    /** How far the middle beat the better of the two ends. */
    val riseDb: Double get() = peakDbm - maxOf(startDbm, endDbm)

    /** How unlike each other the two ends were. Should be small; they are the same distance. */
    val symmetryDb: Double get() = abs(startDbm - endDbm)

    /** How far the peak landed from where you said the closest point was. */
    val offsetMs: Long get() = abs(peakAtMs - midAtMs)

    val sparse: Boolean get() = packets < WalkBy.MIN_PACKETS

    val rose: Boolean get() = !sparse && riseDb >= WalkBy.RISE_DB

    val centred: Boolean
        get() = !sparse && offsetMs <= (walkMs * WalkBy.OFFSET_FRACTION).toLong()

    val symmetric: Boolean get() = !sparse && symmetryDb <= WalkBy.SYMMETRY_DB

    val passed: Boolean get() = rose && centred && symmetric

    /**
     * Asymmetry is reported first, before the rise, because it explains the other two.
     *
     * A device you walked towards climbs the whole way and therefore has no rise *over its
     * better end* - so "it never got louder" would be technically true and useless. Saying
     * the ends disagree names what actually happened.
     */
    fun describe(): String = when {
        sparse -> "only $packets packets during the walk"
        !symmetric -> "ended ${symmetryDb.toInt()} dB from where it started, so it is along " +
            "your path rather than beside it"
        !rose -> "never got louder as you passed, ${riseDb.toInt()} dB"
        !centred -> "loudest ${offsetMs / 1000} s away from where you said you passed"
        else -> "rose ${riseDb.toInt()} dB as you passed and came back down"
    }
}

object WalkBy {

    /**
     * How much louder the middle has to be than the ends.
     *
     * Six decibels is a halving of range in free space and rather less than that indoors,
     * so this is a modest ask for a walk that starts several metres out and passes within
     * one. Set low rather than tight for the usual reason: a phone in a back pocket with a
     * body between it and you is a weak signal, and losing the real target costs more than
     * keeping an extra candidate.
     */
    const val RISE_DB = 6.0

    /**
     * How far apart the two ends may read and still count as the same distance.
     *
     * Generous. The instruction says to stop the same distance away, and people are bad at
     * that, and the two ends of a corridor are rarely equally furnished.
     */
    const val SYMMETRY_DB = 10.0

    /** How near the middle mark the peak has to fall, as a fraction of the whole walk. */
    const val OFFSET_FRACTION = 0.25

    /** Below this, there is no shape to read - only a few readings that happened. */
    const val MIN_PACKETS = 6

    /** What fraction of the walk counts as being at each end. */
    const val END_FRACTION = 0.2

    /**
     * Scores one device's readings from a walk.
     *
     * @param readings time and level, in time order, from the whole walk.
     * @param startMs when the walk began, which is the first mark.
     * @param midMs when the person said they were closest.
     * @param endMs when the walk ended, which is the third mark.
     */
    fun score(
        readings: List<Pair<Long, Int>>,
        startMs: Long,
        midMs: Long,
        endMs: Long,
    ): WalkByScore {
        val walkMs = (endMs - startMs).coerceAtLeast(1L)
        val window = (walkMs * END_FRACTION).toLong().coerceAtLeast(1L)

        val startMean = readings
            .filter { it.first <= startMs + window }
            .map { it.second.toDouble() }
            .averageOr(FLOOR_DBM)
        val endMean = readings
            .filter { it.first >= endMs - window }
            .map { it.second.toDouble() }
            .averageOr(FLOOR_DBM)

        val smoothed = smooth(readings)
        val peak = smoothed.maxByOrNull { it.second }

        return WalkByScore(
            packets = readings.size,
            startDbm = startMean,
            peakDbm = peak?.second ?: FLOOR_DBM,
            peakAtMs = peak?.first ?: midMs,
            endDbm = endMean,
            midAtMs = midMs,
            walkMs = walkMs,
        )
    }

    /**
     * A running median, so one lucky packet cannot be the peak.
     *
     * A median rather than a moving average, deliberately. Advertisement levels jump
     * several decibels between consecutive packets with nothing moving, and the peak is the
     * single most load-bearing number here - an unsmoothed maximum picks the noisiest
     * device in the room every time, and a mean only dilutes an outlier rather than
     * discarding it. A median of five throws a single spike away completely, which is the
     * behaviour wanted: one packet is never evidence of anything.
     *
     * The window shrinks at the two ends rather than dropping them. The first and last
     * readings are inside the end windows that set the two baselines, and losing them there
     * would quietly bias both.
     */
    private fun smooth(readings: List<Pair<Long, Int>>): List<Pair<Long, Double>> =
        readings.indices.map { index ->
            val low = (index - SMOOTH_EITHER_SIDE).coerceAtLeast(0)
            val high = (index + SMOOTH_EITHER_SIDE).coerceAtMost(readings.lastIndex)
            val window = readings.subList(low, high + 1)
                .map { it.second.toDouble() }
                .sorted()
            readings[index].first to window[window.size / 2]
        }

    /** Two either side, so a median of five where there is room for one. */
    private const val SMOOTH_EITHER_SIDE = 2

    /** What a device with no readings at one end is credited with: nothing heard. */
    const val FLOOR_DBM = -110.0

    private fun List<Double>.averageOr(fallback: Double): Double =
        if (isEmpty()) fallback else average()
}
