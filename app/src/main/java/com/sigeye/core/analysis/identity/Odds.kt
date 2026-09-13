package com.sigeye.core.analysis.identity

import kotlin.math.abs
import kotlin.math.sqrt

/** One thing that moved a candidate's score, and which way. */
data class ScoreReason(
    val text: String,
    /** Positive for evidence it is the target, negative for evidence it is not. */
    val points: Double,
) {
    val against: Boolean get() = points < 0
}

/** How much a candidate looks like the thing you are following. */
enum class Odds(val label: String) {
    /** Something argues actively against it. */
    RULED_OUT("Ruled out"),

    /** On the list because it has not been eliminated, which is not the same as evidence. */
    STILL_HERE("Still here"),

    /** Evidence beyond having survived. */
    PROBABLE("Probable"),

    /** Either a lot of evidence, or enough evidence with almost nothing else left. */
    STANDOUT("Stands out"),
}

/** What one candidate's case amounts to. */
data class CandidateScore(
    val address: String,
    val points: Double,
    val reasons: List<ScoreReason>,
    /** This candidate's share of all the positive evidence on the board. */
    val share: Double,
    /** How many others are still in with it. */
    val rivals: Int,
    /** Addresses moving in lockstep with this one, which is what a second pocket item does. */
    val companions: List<String>,
    val odds: Odds,
) {
    val supporting: List<ScoreReason> get() = reasons.filter { !it.against }
    val against: List<ScoreReason> get() = reasons.filter { it.against }

    /** The one line under the number. */
    fun headline(): String = when (odds) {
        Odds.RULED_OUT -> against.minByOrNull { it.points }?.text ?: "Something argues against it"
        Odds.STANDOUT -> supporting.maxByOrNull { it.points }?.text ?: "Well ahead of the rest"
        Odds.PROBABLE -> supporting.maxByOrNull { it.points }?.text ?: "More than just surviving"
        Odds.STILL_HERE -> "Has not been ruled out, which is not the same as evidence"
    }
}

/**
 * Scoring a candidate against everything the follow has learned.
 *
 * The old ranking was an integer heuristic that only ever went up, and the number on the
 * screen was how many devices had not yet been eliminated. Those are different questions,
 * and the second one flatters itself: a device is on the list because nothing has ruled it
 * out, which is not evidence that it is the right one.
 *
 * Two things this gets right that a plain count cannot.
 *
 * Evidence runs both ways. A device that did not get louder when you walked past the person
 * has actively argued against itself, and a device that never moves at all relative to you
 * is in your own pocket. Both used to sit on the list looking exactly like a device with
 * nothing known about it.
 *
 * And people carry more than one device. Two things in the same pocket see the same body,
 * the same walls and the same multipath as you move, so their signals rise and fall
 * together. Treating them as rivals splitting the evidence is backwards - each one is a
 * reason to believe the other, and a phone with a watch beside it is a stronger finding
 * than a phone alone.
 *
 * Pure and Android-free.
 */
object Scoring {

    // ------------------------------------------------------------------ what things are worth

    /** Per minute held continuously. The backbone: time is what a follow actually spends. */
    const val PER_MINUTE = 1.0

    /** Capped, because an hour with a lamppost is still a lamppost. */
    const val MAX_ENDURANCE = 12.0

    /** Arrived after the baseline, when you said the target was not there yet. */
    const val ARRIVED = 10.0

    const val PASSED_WALK_BY = 8.0
    const val FAILED_WALK_BY = -10.0
    const val PASSED_ORBIT = 4.0
    const val FAILED_ORBIT = -5.0

    /**
     * Followed through an address change and carried on behaving.
     *
     * A rotation is a moment at which a coincidence usually ends. Something that was
     * keeping pace with you by chance does not put on a new address in the same instant and
     * carry on keeping pace.
     */
    const val PER_ROTATION = 3.0

    /** Looks like it is in your own pocket rather than theirs. */
    const val CARRIED = -12.0

    /** Went quiet and came back, which a device genuinely with them does not usually do. */
    const val RETURNED = -2.0

    /** Each device moving in lockstep with this one. */
    const val PER_COMPANION = 4.0

    // ------------------------------------------------------------------ where the bands fall

    /** Enough evidence to stand out on its own, whatever else is on the list. */
    const val STANDOUT_POINTS = 20.0

    /** Enough evidence to stand out when there is hardly anything left to compete. */
    const val THIN_FIELD_POINTS = 10.0

    /** At or below this many others still in, the field counts as thin. */
    const val THIN_FIELD = 2

    /** Below this, a candidate has done nothing but survive. */
    const val PROBABLE_POINTS = 6.0

    // ------------------------------------------------------------------ companions

    /** How alike two signals must move before they are called one pocket. */
    const val COMPANION_CORRELATION = 0.72

    /** And how close in level, because correlation alone links a room to itself. */
    const val COMPANION_LEVEL_DB = 18.0

    /** Buckets to resample trails into, in milliseconds. */
    const val BUCKET_MS = 5_000L

    /** Overlapping buckets needed before a correlation means anything. */
    const val MIN_BUCKETS = 6

    /**
     * Beyond this many candidates, companions are not computed.
     *
     * It is every pair against every other, and on a concourse that is thousands of
     * comparisons twice a second for an answer nobody can use yet - at forty candidates
     * you are not looking at somebody's pocket, you are looking at a crowd.
     */
    const val COMPANION_LIMIT = 20

    /**
     * Scores every candidate, including what argues against each one.
     *
     * @param trails recent level readings per address, used to find devices sharing a
     *   pocket. Pass an empty map to skip that entirely.
     */
    fun score(
        candidates: List<FollowCandidate>,
        tuning: FollowTuning,
        nowMs: Long,
        trails: Map<String, List<Pair<Long, Int>>> = emptyMap(),
    ): List<CandidateScore> {
        val live = candidates.filter { it.stillIn }
        val companions = companions(live, trails)

        val raw = candidates.associate { candidate ->
            candidate.address to reasons(
                candidate = candidate,
                tuning = tuning,
                nowMs = nowMs,
                companions = companions[candidate.address].orEmpty(),
            )
        }

        val totals = raw.mapValues { (_, reasons) -> reasons.sumOf { it.points } }
        val positive = totals.values.filter { it > 0 }.sum()

        return candidates.map { candidate ->
            val reasons = raw.getValue(candidate.address)
            val points = totals.getValue(candidate.address)
            val mine = companions[candidate.address].orEmpty()
            CandidateScore(
                address = candidate.address,
                points = points,
                reasons = reasons,
                share = if (positive <= 0.0 || points <= 0.0) 0.0 else points / positive,
                rivals = (live.size - 1).coerceAtLeast(0),
                companions = mine,
                odds = band(points, reasons, live.size - 1),
            )
        }.sortedByDescending { it.points }
    }

    /**
     * Which band a score falls in.
     *
     * Two ways to stand out, deliberately. A lot of evidence stands out on its own. Rather
     * less evidence also stands out when there are two devices left, because eliminating
     * forty things is itself the measurement - that is what the whole walk was for.
     */
    fun band(points: Double, reasons: List<ScoreReason>, rivals: Int): Odds = when {
        reasons.any { it.points <= FAILED_WALK_BY } || reasons.any { it.points <= CARRIED } ->
            Odds.RULED_OUT

        points >= STANDOUT_POINTS -> Odds.STANDOUT
        points >= THIN_FIELD_POINTS && rivals <= THIN_FIELD -> Odds.STANDOUT
        points >= PROBABLE_POINTS -> Odds.PROBABLE
        else -> Odds.STILL_HERE
    }

    private fun reasons(
        candidate: FollowCandidate,
        tuning: FollowTuning,
        nowMs: Long,
        companions: List<String>,
    ): List<ScoreReason> {
        val out = mutableListOf<ScoreReason>()

        val minutes = candidate.heldForMs(nowMs) / 60_000.0
        if (minutes >= 1.0) {
            out.add(
                ScoreReason(
                    "Stayed with you for ${minutes.toInt()} minutes",
                    (minutes * PER_MINUTE).coerceAtMost(MAX_ENDURANCE),
                ),
            )
        }

        if (candidate.arrived) {
            out.add(
                ScoreReason(
                    "Was not here at the baseline and turned up afterwards",
                    ARRIVED,
                ),
            )
        }

        candidate.walkBy?.let { walkBy ->
            out.add(
                if (walkBy.passed) {
                    ScoreReason(
                        "Rose and fell as you walked past them",
                        PASSED_WALK_BY,
                    )
                } else {
                    ScoreReason(
                        "Did not rise when you walked past them, which is the one test " +
                            "something on that person cannot fail",
                        FAILED_WALK_BY,
                    )
                },
            )
        }

        candidate.orbit?.let { orbit ->
            out.add(
                if (orbit.centred) {
                    ScoreReason("Held steady while you circled them", PASSED_ORBIT)
                } else {
                    ScoreReason(
                        "Swung around as you circled, the way something fixed in the room " +
                            "does rather than something on a person",
                        FAILED_ORBIT,
                    )
                },
            )
        }

        if (candidate.rotations > 0) {
            out.add(
                ScoreReason(
                    "Followed through ${candidate.rotations} address " +
                        (if (candidate.rotations == 1) "change" else "changes") +
                        " and carried on keeping pace",
                    candidate.rotations * PER_ROTATION,
                ),
            )
        }

        if (candidate.carried(tuning)) {
            out.add(
                ScoreReason(
                    "Never moves relative to you, which is what your own pocket looks like",
                    CARRIED,
                ),
            )
        }

        if (candidate.returnedAtMs != null) {
            out.add(ScoreReason("Dropped out and came back", RETURNED))
        }

        if (companions.isNotEmpty()) {
            out.add(
                ScoreReason(
                    "Rises and falls in step with ${companions.size} other " +
                        (if (companions.size == 1) "device" else "devices") +
                        ", the way things in one pocket do",
                    companions.size * PER_COMPANION,
                ),
            )
        }

        return out
    }

    // ------------------------------------------------------------------ one pocket

    /**
     * Which candidates are moving as one.
     *
     * Two devices in the same pocket are shadowed by the same body, reflected by the same
     * walls and blocked by the same van, so as you walk their levels rise and fall together.
     * A device across the street does not do that however loud it is.
     *
     * Correlation alone is not enough, because everything in a lift gets quiet at once. The
     * levels have to be close as well, which is what separates one pocket from one street.
     */
    fun companions(
        candidates: List<FollowCandidate>,
        trails: Map<String, List<Pair<Long, Int>>>,
    ): Map<String, List<String>> {
        if (candidates.size < 2 || candidates.size > COMPANION_LIMIT) return emptyMap()

        val buckets = candidates.associate { it.address to bucket(trails[it.address].orEmpty()) }
        val found = mutableMapOf<String, MutableList<String>>()

        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                if (abs(a.recentRssi - b.recentRssi) > COMPANION_LEVEL_DB) continue
                val r = correlation(buckets.getValue(a.address), buckets.getValue(b.address))
                    ?: continue
                if (r < COMPANION_CORRELATION) continue
                found.getOrPut(a.address) { mutableListOf() }.add(b.address)
                found.getOrPut(b.address) { mutableListOf() }.add(a.address)
            }
        }
        return found
    }

    /** Readings averaged into fixed time buckets, so two trails can be lined up. */
    fun bucket(trail: List<Pair<Long, Int>>): Map<Long, Double> = trail
        .groupBy { it.first / BUCKET_MS }
        .mapValues { (_, readings) -> readings.map { it.second }.average() }

    /** Pearson correlation over the buckets two trails share, or null if too few. */
    fun correlation(a: Map<Long, Double>, b: Map<Long, Double>): Double? {
        val shared = a.keys intersect b.keys
        if (shared.size < MIN_BUCKETS) return null

        val xs = shared.map { a.getValue(it) }
        val ys = shared.map { b.getValue(it) }
        val meanX = xs.average()
        val meanY = ys.average()

        var top = 0.0
        var leftSq = 0.0
        var rightSq = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            top += dx * dy
            leftSq += dx * dx
            rightSq += dy * dy
        }
        // A trail that never moved has no shape to match. Two flat lines are not evidence
        // of anything, and dividing by zero here would report them as a perfect pair.
        if (leftSq <= 0.0 || rightSq <= 0.0) return null
        return top / sqrt(leftSq * rightSq)
    }
}
