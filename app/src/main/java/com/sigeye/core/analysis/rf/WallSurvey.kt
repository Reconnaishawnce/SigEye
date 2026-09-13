package com.sigeye.core.analysis.rf

import java.util.Locale

/**
 * One place you stood, named by you, and what the building did to 5 GHz there.
 *
 * [baselineAtMs] is not bookkeeping. Excess loss is measured against a baseline taken with
 * nothing in the way, and two spots measured against two different baselines are two
 * different measurements - the reference moved underneath them. Carrying the baseline with
 * the spot is what lets a survey be resumed a day later instead of being thrown away every
 * time somebody re-baselines.
 */
data class SurveySpot(
    val name: String,
    /** Null when nothing could be measured here - every 5 GHz radio was gone. */
    val excessDb: Double?,
    val pairs: Int,
    val lost5: Int,
    val takenAtMs: Long,
    val baselineAtMs: Long,
) {
    val measured: Boolean get() = excessDb != null

    /** True when the faster band has effectively stopped existing at this spot. */
    val blackspot: Boolean get() = pairs > 0 && lost5 >= pairs
}

/** What a walk around a building added up to. */
data class SurveySummary(
    val spots: Int,
    val worst: SurveySpot,
    val best: SurveySpot,
    val blackspots: Int,
) {
    /** How much worse the worst spot is than the best. The survey's actual finding. */
    val spanDb: Double get() = (worst.excessDb ?: 0.0) - (best.excessDb ?: 0.0)

    /** Only worth stating when the two ends are far enough apart to be telling you something. */
    val tellsYouSomething: Boolean get() = spanDb >= WallSurvey.WORTH_SAYING_DB
}

/**
 * A building survey: several named spots measured against one baseline.
 *
 * The screen had a list of spots called "Spot 1" and "Spot 2" in the order they happened to
 * be saved, which is the order least useful to somebody surveying a house. What you want to
 * know is which room is worst and how much worse it is than the best one, and for that the
 * spots have to carry the name of the room and be sorted by what they cost.
 *
 * Pure and Android-free.
 */
object WallSurvey {

    /** Below this, the difference between the best and worst room is not worth a sentence. */
    const val WORTH_SAYING_DB = 4.0

    /** Worst first, because a survey is a hunt for where it is bad. */
    fun order(spots: List<SurveySpot>): List<SurveySpot> = spots.sortedWith(
        // Unmeasurable spots last rather than first: a null is not a good reading, and
        // sorting them to the top would put the least informative rows where the eye lands.
        compareByDescending<SurveySpot> { it.measured }
            .thenByDescending { it.excessDb ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.takenAtMs },
    )

    /** The spots belonging to one baseline, which is the only set that may be compared. */
    fun against(spots: List<SurveySpot>, baselineAtMs: Long): List<SurveySpot> =
        spots.filter { it.baselineAtMs == baselineAtMs }

    /**
     * The best and worst of a survey, or null when there is nothing to compare.
     *
     * Two measured spots minimum. One spot has no best and no worst, and saying it is both
     * would be a sentence with no content dressed up as a finding.
     */
    fun summarize(spots: List<SurveySpot>): SurveySummary? {
        val measured = spots.filter { it.measured }
        if (measured.size < 2) return null
        val ordered = order(measured)
        return SurveySummary(
            spots = spots.size,
            worst = ordered.first(),
            best = ordered.last(),
            blackspots = spots.count { it.blackspot },
        )
    }

    /** The one sentence a survey is for. */
    fun finding(summary: SurveySummary): String = when {
        !summary.tellsYouSomething ->
            "${summary.spots} spots and barely ${format(summary.spanDb)} between the best " +
                "and the worst. The building is not what is costing you 5 GHz here."

        summary.blackspots > 0 ->
            "${summary.worst.name} costs ${format(summary.spanDb)} more than " +
                "${summary.best.name}, and ${summary.blackspots} " +
                (if (summary.blackspots == 1) "spot has" else "spots have") +
                " lost 5 GHz altogether."

        else ->
            "${summary.worst.name} costs ${format(summary.spanDb)} more than " +
                "${summary.best.name}. That difference is the building between them."
    }

    /** A name that is not "Spot 4", offered as a starting point rather than imposed. */
    fun suggestName(existing: List<SurveySpot>): String {
        val taken = existing.map { it.name.lowercase() }.toSet()
        return ROOMS.firstOrNull { it.lowercase() !in taken } ?: "Spot ${existing.size + 1}"
    }

    /** The survey itself, one row per spot. The pairs export is a different document. */
    fun csv(spots: List<SurveySpot>): String = buildString {
        appendLine("spot,excess_db,access_points,lost_5ghz,blackspot,taken_ms")
        order(spots).forEach { spot ->
            append(spot.name.replace(',', ' '))
            append(',')
            append(spot.excessDb?.let { String.format(Locale.US, "%.1f", it) } ?: "")
            append(',')
            append(spot.pairs)
            append(',')
            append(spot.lost5)
            append(',')
            append(if (spot.blackspot) "1" else "0")
            append(',')
            appendLine(spot.takenAtMs)
        }
    }

    private fun format(db: Double): String = String.format(Locale.US, "%.1f dB", db)

    /**
     * Offered in the order somebody walking a house would reach them, not alphabetically.
     * The field is free text - these only save typing.
     */
    private val ROOMS = listOf(
        "Living room",
        "Kitchen",
        "Bedroom",
        "Upstairs",
        "Bathroom",
        "Hallway",
        "Basement",
        "Garage",
        "Garden",
    )

    /** What the quick-pick chips offer. */
    fun roomNames(): List<String> = ROOMS
}
