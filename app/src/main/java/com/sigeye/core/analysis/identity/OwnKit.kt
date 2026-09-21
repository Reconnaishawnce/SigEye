package com.sigeye.core.analysis.identity

/** A whitelisted device's address being carried forward onto its replacement. */
data class Adoption(
    val fromAddress: String,
    val toAddress: String,
    val label: String,
    val atMs: Long,
    val confidence: LinkConfidence,
    val why: String,
    val rssi: Int,
)

/** Why an address change was watched and not taken. Kept so the screen can show its working. */
data class NotAdopted(val fromAddress: String, val why: String)

/**
 * Carrying a whitelist across the address changes that would otherwise empty it.
 *
 * A list of "these are mine" keyed on addresses that rotate every fifteen minutes is
 * useless by lunchtime. The watch on your wrist becomes a stranger twice an hour, rejoins
 * every count in the app, and reappears at the top of every follow, and somebody has to
 * keep marking it again. So a whitelisted device is watched for its own rotation and the
 * successor inherits the mark.
 *
 * **This is the most dangerous automatic decision in the app and it is worth being explicit
 * about why.** Everywhere else, following a rotation wrongly produces a visible mistake:
 * the follow leads somewhere silly, and somebody standing there notices within a minute.
 * Here the consequence of a wrong link is that a stranger's phone is quietly excluded from
 * every count, every follow and every baseline, indefinitely, with nobody told. A silent
 * wrong exclusion is the one kind of error nobody ever goes looking for.
 *
 * So the bar is higher than [Handoffs] uses, in three ways.
 *
 * It will not ask. [Handoffs] treats asking as a first-class outcome, because during a
 * follow there is an operator watching the person and they can break a tie in a second.
 * Nothing is watching here. An ambiguous change is declined, the mark lapses, and somebody
 * re-marks the device in ten seconds using the screen built for it. Losing a whitelist
 * entry costs a button press. Gaining a wrong one costs the truth of every number.
 *
 * It requires more than a winner. [Handoffs] adopts at a 0.72 share because a follow that
 * refuses at every rotation ends at the first one. Nothing here is time-limited in that
 * way, so a candidate has to be nearly alone in fitting.
 *
 * And it uses a constraint no follow has: **your own kit is always loud.** Whatever a
 * fingerprint says, a candidate heard across a room is not the watch on your wrist. That
 * single test removes almost everything a shape match could otherwise reach, and it costs
 * nothing, because a device that is genuinely yours and genuinely out of earshot is not
 * affecting any count anyway.
 *
 * Pure and Android-free.
 */
object OwnKit {

    /**
     * How loud a successor has to be to be a thing you are carrying.
     *
     * The same line [MyKit] draws when suggesting devices in the first place, and for the
     * same reason. Kept as its own constant rather than shared, because the two answer
     * different questions - one is "does this look like it is on you", the other is "could
     * this possibly be the device that just went quiet on you" - and a future reason to
     * move one is not a reason to move the other.
     */
    const val ON_PERSON_DBM = -65

    /** Share of the evidence a candidate must hold. Well above the 0.72 a follow uses. */
    const val MIN_SHARE = 0.9

    /** And the confidence it must reach on its own. */
    val MIN_CONFIDENCE = LinkConfidence.STRONG

    /**
     * Whether the device that just went quiet has turned up under a new address.
     *
     * @param previous the whitelisted device, as last heard.
     * @param departure how its last few seconds looked, from [Handoffs.classify].
     * @param candidates addresses that appeared around the time it went silent.
     * @return the adoption to make, or null with a reason in [NotAdopted].
     */
    fun consider(
        previous: Identity,
        departure: Departure,
        candidates: List<Identity>,
        label: String,
        nowMs: Long,
    ): Pair<Adoption?, NotAdopted?> {
        // Your own kit does not walk away from you. A whitelisted device whose signal
        // faded out is one you left somewhere, and the right answer is to let the mark
        // lapse rather than to hunt for whatever is loud nearby now.
        if (departure.exit == Exit.FADED) {
            return null to NotAdopted(
                previous.address,
                "It faded out rather than stopping, so it went somewhere rather than " +
                    "changing address. The mark has lapsed.",
            )
        }

        val reachable = candidates.filter { it.recentRssi >= ON_PERSON_DBM }
        if (reachable.isEmpty()) {
            return null to NotAdopted(
                previous.address,
                "Nothing near enough to be on you turned up when it went quiet.",
            )
        }

        return when (val handoff = Handoffs.decide(previous, departure, reachable, nowMs)) {
            is Handoff.Rotated -> verdict(
                previous = previous,
                to = handoff.to,
                heardAt = reachable.first { it.address == handoff.to.address },
                label = label,
                nowMs = nowMs,
            )

            is Handoff.Ask -> null to NotAdopted(
                previous.address,
                "${handoff.options.size} devices fit and nobody is watching, so none was " +
                    "taken. Mark it again if it is still with you.",
            )

            is Handoff.Gone -> null to NotAdopted(previous.address, handoff.why)

            Handoff.Waiting -> null to null
        }
    }

    private fun verdict(
        previous: Identity,
        to: Successor,
        heardAt: Identity,
        label: String,
        nowMs: Long,
    ): Pair<Adoption?, NotAdopted?> {
        if (to.score.confidence < MIN_CONFIDENCE) {
            return null to NotAdopted(
                previous.address,
                "The best match was only \"${to.score.confidence.label.lowercase()}\", " +
                    "which is enough to follow somebody with and not enough to change a " +
                    "list nobody is looking at.",
            )
        }
        if (to.share < MIN_SHARE) {
            return null to NotAdopted(
                previous.address,
                "One candidate led but others fitted too, and an unattended decision needs " +
                    "a candidate that is close to alone.",
            )
        }

        return Adoption(
            fromAddress = previous.address,
            toAddress = to.address,
            label = label,
            atMs = nowMs,
            confidence = to.score.confidence,
            why = describe(to),
            rssi = heardAt.recentRssi.toInt(),
        ).let { it to null }
    }

    /** What happened, in the terms somebody would want to audit it in. */
    fun describe(to: Successor): String = buildString {
        append("Appeared ")
        append(if (to.gapMs < 0) "overlapping by ${-to.gapMs} ms" else "${to.gapMs} ms after")
        append(", ")
        append("%.0f".format(kotlin.math.abs(to.rssiDeltaDb)))
        append(" dB from where the old one left off")
        if (to.intervalSlots > 0 && to.intervalSlots == to.previousIntervalSlots) {
            append(", same ${to.intervalSlots}-slot advertising interval")
        }
        append(".")
    }
}
