package com.sigeye.core.analysis.identity

/** What a run of [Trial] produced, counted against the answer. */
data class Score(
    /** Handovers the app took, and got right. */
    val followed: Int,
    /** Handovers the app took, and got wrong. This is the one that matters. */
    val wrong: Int,
    /** Handovers that happened and were not taken. */
    val missed: Int,
    /** Handovers it put to a person instead of deciding. */
    val asked: Int,
) {
    val attempted: Int get() = followed + wrong

    /** Of the ones it decided to take, how many were right. */
    val precision: Double get() = if (attempted == 0) 0.0 else followed.toDouble() / attempted

    /** Of the handovers that happened, how many it followed. */
    val recall: Double
        get() = (followed + missed).let { if (it == 0) 0.0 else followed.toDouble() / it }

    /**
     * One number to sweep against, weighted towards not being wrong.
     *
     * Missing a rotation costs a follow, which somebody notices and can restart. Taking the
     * wrong one produces a confident trail leading to a stranger, which nobody notices at
     * all. They are not symmetrical failures and a score that treated them as such would
     * happily tune the app towards the worse one.
     */
    val quality: Double get() = recall - WRONG_COSTS * (wrong.toDouble() / (attempted + 1))

    override fun toString(): String =
        "followed=$followed wrong=$wrong missed=$missed asked=$asked " +
            "precision=%.2f recall=%.2f quality=%.3f".format(precision, recall, quality)

    companion object {
        /** How many correct follows one wrong one is worth giving up to avoid. */
        const val WRONG_COSTS = 3.0
    }
}

/**
 * Replays a modeled or recorded walk through the real rotation logic and marks the result.
 *
 * Deliberately built on [Handoffs] itself rather than on a copy of its reasoning. A harness
 * that reimplements the thing it is measuring measures the reimplementation, and the whole
 * point is to be able to change a threshold and find out what it does to the app.
 */
object Trial {

    /** Rebuilt per address, which is exactly what the app has to work with. */
    private class Seen(val address: String) {
        var shape = AdvertShape()
        var first = 0L
        var last = 0L
        var packets = 0
        var best = -127
        var recent = -127.0
        val trail = mutableListOf<Pair<Long, Int>>()
        val gaps = mutableListOf<Long>()

        fun add(packet: WalkModel.Packet) {
            if (first == 0L) first = packet.atMs else gaps.add(packet.atMs - last)
            shape = packet.shape
            last = packet.atMs
            packets++
            best = maxOf(best, packet.rssi)
            recent = packet.rssi.toDouble()
            trail.add(packet.atMs to packet.rssi)
            if (trail.size > 40) trail.removeAt(0)
            if (gaps.size > 40) gaps.removeAt(0)
        }

        fun identity() = Identity(
            address = address,
            shape = shape,
            isRandom = true,
            firstSeenMs = first,
            lastSeenMs = last,
            packets = packets,
            medianGapMs = if (gaps.isEmpty()) 0L else gaps.sorted()[gaps.size / 2],
            recentRssi = recent,
            bestRssi = best,
        )
    }

    /**
     * Runs the walk and marks every handover the app made.
     *
     * @param takeAsks whether an [Handoff.Ask] counts as taking its best option. False is
     *   the honest default: asking is not deciding, and counting it as a correct follow
     *   would let a threshold score well by refusing to commit to anything.
     */
    fun run(
        walk: WalkModel.Walk,
        tuning: HandoffTuning = HandoffTuning.DEFAULT,
        takeAsks: Boolean = false,
    ): Score {
        val seen = mutableMapOf<String, Seen>()
        val decided = mutableMapOf<String, String?>()
        var asked = 0
        val claimed = mutableSetOf<String>()

        walk.packets.forEach { packet ->
            seen.getOrPut(packet.address) { Seen(packet.address) }.add(packet)

            // The app only ever gets to ask this question as time passes, so the harness
            // asks it the same way rather than at the end with everything visible.
            val now = packet.atMs
            seen.values
                .filter { candidate ->
                    candidate.address !in decided &&
                        candidate.packets >= Handoffs.MIN_TRAIL &&
                        now - candidate.last >= tuning.silenceMs &&
                        now - candidate.last <= tuning.giveUpMs
                }
                .forEach { quiet ->
                    val arrivals = seen.values
                        .filter { it.address != quiet.address && it.address !in claimed }
                        .filter { now - it.last <= tuning.silenceMs }
                        .filter { it.packets >= Handoffs.MIN_TRAIL }
                        .map { it.identity() }

                    val departure =
                        Handoffs.classify(quiet.address, quiet.trail.toList(), quiet.best)

                    when (
                        val handoff = Handoffs.decide(
                            previous = quiet.identity(),
                            departure = departure,
                            candidates = arrivals,
                            nowMs = now,
                            taken = claimed,
                            tuning = tuning,
                        )
                    ) {
                        is Handoff.Rotated -> {
                            decided[quiet.address] = handoff.to.address
                            claimed.add(handoff.to.address)
                        }

                        is Handoff.Ask -> {
                            asked++
                            if (takeAsks) {
                                decided[quiet.address] = handoff.options.first().address
                                claimed.add(handoff.options.first().address)
                            } else {
                                decided[quiet.address] = null
                            }
                        }

                        is Handoff.Gone -> decided[quiet.address] = null
                        Handoff.Waiting -> Unit
                    }
                }
        }

        val answer = walk.truth.toMap()
        var followed = 0
        var wrong = 0
        decided.forEach { (from, to) ->
            if (to == null) return@forEach
            if (answer[from] == to) followed++ else wrong++
        }
        // A handover is missed when it really happened and the app took nothing for it.
        val missed = walk.truth.count { (from, _) -> decided[from] == null }

        return Score(followed = followed, wrong = wrong, missed = missed, asked = asked)
    }

    /** Runs the same walk across a range of one setting, for comparing them. */
    fun <T> sweep(
        walk: WalkModel.Walk,
        values: List<T>,
        apply: (HandoffTuning, T) -> HandoffTuning,
    ): List<Pair<T, Score>> = values.map { value ->
        value to run(walk, apply(HandoffTuning.DEFAULT, value))
    }
}
