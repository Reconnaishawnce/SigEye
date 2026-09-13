package com.sigeye.core.analysis.identity

/** Why a device was not followed to a new address. Always reported, never swallowed. */
enum class FollowRefusal(val reason: String) {
    NOT_RANDOM(
        "This address is fixed, so it has no rotation to follow. Nothing to do.",
    ),
    TOO_PLAIN(
        "This device's advertisement carries almost nothing distinctive, so a match " +
            "would be a coincidence rather than a link. It will not be followed.",
    ),
    STILL_TALKING("Still on the air."),
    NO_CANDIDATE("Nothing has appeared that matches."),
    AMBIGUOUS(
        "More than one device matches equally well, so there is no way to tell which " +
            "one it is. Following stops here rather than guessing.",
    ),
    LOST(
        "Out of range too long. The trail is cold, and picking it up now would be a " +
            "guess about a device that left.",
    ),
}

/** What to do about one followed device, right now. */
sealed interface FollowDecision {
    /** The device has put on a new address, and it is safe to say which. */
    data class Reacquired(val address: String, val score: LinkScore) : FollowDecision

    data class Refused(val refusal: FollowRefusal, val detail: String? = null) : FollowDecision {
        val message: String
            get() = detail?.let { "${refusal.reason} $it" } ?: refusal.reason
    }
}

/** A name moving from one address to the next, recorded so it can be read and undone. */
data class Handover(
    val atMs: Long,
    val label: String,
    val fromAddress: String,
    val toAddress: String,
    val points: Int,
    val reasons: List<String>,
) {
    val id: String get() = "$fromAddress>$toAddress@$atMs"
}

/**
 * Keeping a name attached to a device after the device changes its address.
 *
 * Nicknames and lists are stored against an address, which is fine for a speaker and
 * useless for a phone: the name goes stale every quarter of an hour and the device the
 * user actually cared about reappears as a stranger. This follows it across, using the
 * same fingerprinting the Defeating Randomization experiment demonstrates, so Signal
 * Watch, Discovery and Traveling Companions all keep their subject.
 *
 * The failure mode is the entire design problem. Losing a device is an inconvenience;
 * moving somebody's name onto a stranger's phone is a false statement that then gets
 * treated as fact by every other screen in the app, and nothing later will correct it.
 * So this is deliberately, obstructively conservative:
 *
 *  - the strongest confidence only, never merely likely;
 *  - a refusal when two candidates match equally well, rather than the better of the two,
 *    because a room full of identical phones is exactly when a guess would be wrong;
 *  - a refusal when the advertisement is too plain to be distinctive at all;
 *  - candidates must have *appeared* around the handover, so something already on the air
 *    beforehand is never adopted;
 *  - every move is recorded, shown, and reversible.
 *
 * Pure and Android-free.
 */
object Following {

    /** Nothing below this is ever acted on. */
    val REQUIRED_CONFIDENCE = LinkConfidence.STRONG

    /** How long an address must be silent before it counts as gone rather than quiet. */
    const val SILENCE_MS = 20_000L

    /**
     * After this, the trail is cold.
     *
     * A device out of range for ten minutes has probably left the building, and whatever
     * turns up afterwards carrying a similar advertisement is far more likely to be
     * another one of the same model than the one that went.
     */
    const val GIVE_UP_MS = 10 * 60_000L

    /**
     * How long before the handover a candidate may already have been advertising.
     *
     * A rotation produces an address that did not exist a moment earlier. Something that
     * has been broadcasting happily for five minutes is a different device that happens to
     * share a firmware, and adopting it is the mistake this whole file exists to avoid.
     */
    const val APPEARED_WITHIN_MS = 5_000L

    fun decide(
        previous: Identity,
        candidates: List<Identity>,
        nowMs: Long,
    ): FollowDecision {
        if (!previous.isRandom) {
            return FollowDecision.Refused(FollowRefusal.NOT_RANDOM)
        }
        if (previous.shape.tooPlainToMatchOn) {
            return FollowDecision.Refused(FollowRefusal.TOO_PLAIN)
        }

        val silence = nowMs - previous.lastSeenMs
        if (silence < SILENCE_MS) {
            return FollowDecision.Refused(FollowRefusal.STILL_TALKING)
        }
        if (silence > GIVE_UP_MS) {
            return FollowDecision.Refused(FollowRefusal.LOST)
        }

        val matches = candidates
            .filter { it.address != previous.address }
            .filter { it.firstSeenMs >= previous.lastSeenMs - APPEARED_WITHIN_MS }
            .map { it to Fingerprint.score(previous, it) }
            .filter { it.second.confidence.ordinal >= REQUIRED_CONFIDENCE.ordinal }

        return when (matches.size) {
            0 -> FollowDecision.Refused(FollowRefusal.NO_CANDIDATE)
            1 -> FollowDecision.Reacquired(matches.first().first.address, matches.first().second)
            else -> FollowDecision.Refused(
                FollowRefusal.AMBIGUOUS,
                "${matches.size} candidates were equally convincing.",
            )
        }
    }

    /** Turns an accepted decision into the record that gets shown and can be undone. */
    fun record(
        label: String,
        fromAddress: String,
        decision: FollowDecision.Reacquired,
        atMs: Long,
    ): Handover = Handover(
        atMs = atMs,
        label = label,
        fromAddress = fromAddress,
        toAddress = decision.address,
        points = decision.score.points,
        reasons = decision.score.supporting.map { it.text },
    )
}
