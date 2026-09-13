package com.sigeye.core.analysis.identity

import kotlin.math.abs

/** Why an address stopped being heard. */
enum class Exit(val label: String) {
    /**
     * Loud and steady right up to the silence.
     *
     * Nothing walks out of range in one packet. A device that was strong and then simply
     * was not there either changed address, was switched off, or went into a bag, and of
     * those three the first is overwhelmingly the most common on a phone.
     */
    VANISHED("Cut off mid-sentence"),

    /**
     * Got quieter and quieter and then went.
     *
     * This is what leaving looks like, and it is the case where hunting for a successor
     * does active harm: the device is gone, so anything that matches is a different device
     * that happens to share a firmware.
     */
    FADED("Faded out"),

    /** Not enough of a trail to say which. */
    UNCLEAR("Went quiet"),
}

/** How an address left, and the numbers behind the verdict. */
data class Departure(
    val address: String,
    val exit: Exit,
    /** Decibels per second over the closing window. Negative means receding. */
    val slopeDbPerSec: Double,
    /** Where it was at the end. */
    val finalDbm: Double,
    /** The best this device was ever heard at, which is what the end is judged against. */
    val bestDbm: Int,
    val readings: Int,
    val lastSeenMs: Long,
) {
    /** Whether it is worth looking for where this device went. */
    val worthChasing: Boolean get() = exit != Exit.FADED

    fun describe(): String = when (exit) {
        Exit.VANISHED -> "It was still ${-finalDbm.toInt()} dBm strong and then stopped " +
            "mid-stream, which is what a rotation looks like and not what leaving looks like."

        Exit.FADED -> "It faded out over the last few seconds, losing " +
            "${(bestDbm - finalDbm).toInt()} dB from its best. That is something walking " +
            "away, so whatever appears now is a different device."

        Exit.UNCLEAR -> "Too few readings before it went quiet to say whether it rotated " +
            "or walked off."
    }
}

/** One address that might be where a device went, with everything needed to judge it. */
data class Successor(
    val address: String,
    val score: LinkScore,
    /** Old one's last packet to the new one's first. Negative means they overlapped. */
    val gapMs: Long,
    /** How far the level moved across the swap. */
    val rssiDeltaDb: Double,
    val intervalSlots: Int,
    val previousIntervalSlots: Int,
    val intervalJitter: Double,
    val packets: Int,
    /**
     * This option's share of the evidence among the options offered, 0 to 1.
     *
     * Relative, and it has to be read alongside [LinkScore.confidence]. One candidate
     * holding all of the evidence because it is the only one that turned up is a different
     * situation from one holding all of it against three rivals, and a bare percentage
     * would show those identically.
     */
    val share: Double,
) {
    val confidence: LinkConfidence get() = score.confidence

    val intervalsMatch: Boolean get() = Fingerprint.intervalsAgree(
        (previousIntervalSlots * 0.625).toLong(),
        (intervalSlots * 0.625).toLong(),
    )
}

/** What to do about a device that has gone quiet. */
sealed interface Handoff {

    /** Still inside the silence window. Ask again shortly. */
    data object Waiting : Handoff

    /** One candidate, far enough ahead of the rest to take without asking. */
    data class Rotated(val departure: Departure, val to: Successor) : Handoff

    /**
     * Several candidates, or one that is not convincing enough to take unattended.
     *
     * This is the case the whole design turns on. Refusing here loses the target, and
     * guessing here follows a stranger. An operator who is standing there watching the
     * person can usually break the tie in a second, so the app asks instead of doing
     * either.
     */
    data class Ask(val departure: Departure, val options: List<Successor>) : Handoff

    /** Nothing plausible. The device left, or it is not coming back under a new name. */
    data class Gone(val departure: Departure, val why: String) : Handoff
}

/**
 * Following a device through a rotation, while somebody is standing there watching it.
 *
 * [Following] solves a neighboring problem and solves it obstructively on purpose: moving a
 * nickname onto the wrong phone writes a false statement into every other screen, forever,
 * with nobody present to notice. So it demands the strongest confidence and refuses the
 * moment two candidates tie.
 *
 * This is not that problem. During a follow the operator is present, can see the person,
 * and a wrong stitch shows itself within a minute and can be undone. Meanwhile refusing
 * costs the target outright, and an address rotating every fifteen minutes means a refusal
 * policy ends every follow at the first rotation. The failure that matters here is the
 * opposite one.
 *
 * So the policy is: take it when one candidate is clearly ahead, ask when it is not, and
 * only give up when the device visibly walked away. Asking is a first-class outcome rather
 * than a failure, which is why [Successor] carries the numbers an operator would want
 * rather than a verdict they have to trust.
 *
 * Pure and Android-free.
 */
object Handoffs {

    /** Silence after which an address counts as finished rather than quiet. */
    const val SILENCE_MS = 18_000L

    /**
     * How long before the silence a successor may already have been advertising.
     *
     * A rotation produces an address that did not exist a moment earlier. Something that
     * has been broadcasting for a minute is a different device with the same firmware, and
     * adopting it is the mistake that turns a follow into a fiction.
     */
    const val APPEARED_WITHIN_MS = 8_000L

    /**
     * After this much silence, stop looking.
     *
     * Shorter than [Following.GIVE_UP_MS], because a follow is a live thing. Three minutes
     * of nothing during a walk means the person turned a corner you did not turn.
     */
    const val GIVE_UP_MS = 3 * 60_000L

    /** Share of the evidence one option must hold before it is taken unattended. */
    const val AUTO_SHARE = 0.72

    /** And the confidence it must reach on its own, regardless of having no rivals. */
    val AUTO_CONFIDENCE = LinkConfidence.LIKELY

    /** Below this, an option is not even worth putting in front of somebody. */
    val ASK_CONFIDENCE = LinkConfidence.POSSIBLE

    /** More than this many options is a menu nobody can read on a street corner. */
    const val MAX_OPTIONS = 3

    /**
     * More plausible successors than this and there is no question worth asking.
     *
     * This is the one that was wrong, and it produced the worst moment in the app: a
     * dialog offering three devices at six percent each, while a hundred and fifty others
     * fitted just as well. Nobody standing on a street can break that tie, the app
     * certainly cannot, and putting it to somebody implies there is an answer in there.
     *
     * When this many match, the honest output is that the trail is cold.
     */
    const val TOO_MANY = 6

    // ------------------------------------------------------------------ how it left

    /** Readings needed in the closing window before the shape of the ending means anything. */
    const val MIN_TRAIL = 5

    /** How far back to look when deciding how an address ended. */
    const val TRAIL_WINDOW_MS = 20_000L

    /** Decibels per second at or past which the device is receding rather than sitting still. */
    const val FADE_SLOPE = -0.35

    /** How far below its own best a device has to end up for that fall to count. */
    const val FADE_DEPTH_DB = 10.0

    /** At or below this it was at the edge of hearing, whatever the slope says. */
    const val EDGE_DBM = -93.0

    /**
     * Why an address went quiet, from the shape of its last few seconds.
     *
     * The distinction is the one thing that keeps this from inventing devices. Chasing a
     * successor after a genuine walk-off is not a harmless miss: something will always
     * match eventually, and what it produces is a confident trail leading to a stranger.
     *
     * @param trail time and level, oldest first. Only the closing window is used.
     * @param bestDbm the best this device was ever heard at, which is what the ending is
     *   measured against - ending at -80 means nothing until you know whether it started
     *   at -78 or at -45.
     */
    fun classify(address: String, trail: List<Pair<Long, Int>>, bestDbm: Int): Departure {
        val lastSeen = trail.lastOrNull()?.first ?: 0L
        val window = trail.filter { it.first >= lastSeen - TRAIL_WINDOW_MS }

        if (window.size < MIN_TRAIL) {
            return Departure(
                address = address,
                exit = Exit.UNCLEAR,
                slopeDbPerSec = 0.0,
                finalDbm = window.lastOrNull()?.second?.toDouble() ?: -127.0,
                bestDbm = bestDbm,
                readings = window.size,
                lastSeenMs = lastSeen,
            )
        }

        val slope = slopeDbPerSec(window)
        val finalDbm = window.takeLast(3).map { it.second }.average()
        val declining = slope <= FADE_SLOPE
        val lostGround = finalDbm <= bestDbm - FADE_DEPTH_DB

        val exit = when {
            // Either half alone is ambiguous. A device can sit at the edge of hearing all
            // follow without moving, and a device can lose ten decibels to a passing van
            // and still be right there.
            declining && lostGround -> Exit.FADED
            finalDbm <= EDGE_DBM -> Exit.FADED
            !declining && !lostGround -> Exit.VANISHED
            else -> Exit.UNCLEAR
        }

        return Departure(
            address = address,
            exit = exit,
            slopeDbPerSec = slope,
            finalDbm = finalDbm,
            bestDbm = bestDbm,
            readings = window.size,
            lastSeenMs = lastSeen,
        )
    }

    /** Least squares slope of level against time, in dB per second. */
    fun slopeDbPerSec(trail: List<Pair<Long, Int>>): Double {
        if (trail.size < 2) return 0.0
        val t0 = trail.first().first
        val xs = trail.map { (it.first - t0) / 1000.0 }
        val ys = trail.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        return if (den <= 0.0) 0.0 else num / den
    }

    // ------------------------------------------------------------------ where it went

    /**
     * What to do about one device that has gone quiet.
     *
     * @param candidates every address currently audible, including ones that have been
     *   around a while. Filtering by when they appeared happens here, so a caller cannot
     *   accidentally offer up a device that was already on the air.
     */
    fun decide(
        previous: Identity,
        departure: Departure,
        candidates: List<Identity>,
        nowMs: Long,
        taken: Set<String> = emptySet(),
    ): Handoff {
        val silence = nowMs - previous.lastSeenMs
        if (silence < SILENCE_MS) return Handoff.Waiting

        if (!previous.isRandom) {
            return Handoff.Gone(
                departure,
                "This address is fixed, so it has no rotation to follow. It stopped " +
                    "advertising or it left.",
            )
        }
        if (previous.shape.tooPlainToMatchOn) {
            return Handoff.Gone(
                departure,
                "Its advertisement carries almost nothing distinctive, so anything that " +
                    "matched would be a coincidence rather than a link.",
            )
        }
        if (silence > GIVE_UP_MS) {
            return Handoff.Gone(
                departure,
                "Silent for ${silence / 60_000} minutes. Whatever turns up now carrying a " +
                    "similar advertisement is more likely another one of the same model.",
            )
        }
        if (!departure.worthChasing) {
            return Handoff.Gone(departure, departure.describe())
        }

        val scored = candidates
            .asSequence()
            .filter { it.address != previous.address }
            .filter { it.address !in taken }
            .filter { it.isRandom }
            .filter { it.firstSeenMs >= previous.lastSeenMs - APPEARED_WITHIN_MS }
            .map { it to Fingerprint.score(previous, it) }
            .filter { it.second.confidence.ordinal >= ASK_CONFIDENCE.ordinal }
            .sortedByDescending { it.second.points }
            .toList()

        if (scored.isEmpty()) {
            return Handoff.Gone(
                departure,
                "Nothing that appeared since it went quiet matches it. A device that has " +
                    "just rotated needs a few seconds of packets before it can be " +
                    "recognized, so this may still be worth another look.",
            )
        }

        if (scored.size > TOO_MANY) {
            return Handoff.Gone(
                departure,
                "${scored.size} devices appeared at about the right moment and match about " +
                    "as well as each other. That is a crowd rather than a rotation - " +
                    "nothing here can pick between them and neither could you.",
            )
        }

        // Over what is actually being shown, not over everything considered. Shares that
        // summed to eighteen percent across three options, because thirteen others were
        // quietly in the denominator, is how this got put in front of somebody as three
        // six percent guesses.
        val shown = scored.take(MAX_OPTIONS)
        val total = shown.sumOf { it.second.points }.toDouble()
        val options = shown.map { (identity, score) ->
            Successor(
                address = identity.address,
                score = score,
                gapMs = identity.firstSeenMs - previous.lastSeenMs,
                rssiDeltaDb = identity.recentRssi - previous.recentRssi,
                intervalSlots = identity.intervalSlots,
                previousIntervalSlots = previous.intervalSlots,
                intervalJitter = identity.intervalJitter,
                packets = identity.packets,
                share = if (total <= 0.0) 0.0 else score.points / total,
            )
        }

        val best = options.first()
        val confident = best.confidence.ordinal >= AUTO_CONFIDENCE.ordinal
        val alone = best.share >= AUTO_SHARE

        // Both halves, deliberately. Clearly ahead of three bad options is still bad, and
        // a strong match with an equally strong rival is exactly the room-full-of-iPhones
        // case where guessing is worst.
        return if (confident && alone) {
            Handoff.Rotated(departure, best)
        } else {
            Handoff.Ask(departure, options)
        }
    }

    /** Why an [Handoff.Ask] could not be settled without somebody, in one line. */
    fun whyAsking(ask: Handoff.Ask): String {
        val best = ask.options.first()
        return when {
            ask.options.size > 1 && best.share < AUTO_SHARE ->
                "${ask.options.size} devices appeared at about the right moment and none " +
                    "of them stands out. You were there - which one was it?"

            best.confidence.ordinal < AUTO_CONFIDENCE.ordinal ->
                "One device appeared at the right moment, but the match is only as good as " +
                    "\"${best.confidence.label.lowercase()}\". Worth a look before it is taken."

            else -> "The match is close enough to need a second opinion."
        }
    }

    /** The line under one option in the dialog: the numbers, not a verdict. */
    fun describe(option: Successor): String = buildString {
        append(
            when {
                option.gapMs < 0 -> "Started ${-option.gapMs} ms before the old one stopped"
                else -> "Appeared ${option.gapMs} ms after the old one stopped"
            },
        )
        append(", ")
        append(
            if (abs(option.rssiDeltaDb) < 1.0) {
                "at the same level"
            } else {
                String.format(
                    java.util.Locale.US,
                    "%+.0f dB",
                    option.rssiDeltaDb,
                )
            },
        )
        append(". Advertises every ")
        append(String.format(java.util.Locale.US, "%.0f ms", option.intervalSlots * 0.625))
        append(
            if (option.intervalsMatch) {
                " against the old one's " +
                    String.format(
                        java.util.Locale.US,
                        "%.0f ms, which agrees",
                        option.previousIntervalSlots * 0.625,
                    )
            } else {
                " against the old one's " +
                    String.format(
                        java.util.Locale.US,
                        "%.0f ms, which does not",
                        option.previousIntervalSlots * 0.625,
                    )
            },
        )
        append(". ${option.packets} packets so far.")
    }
}
