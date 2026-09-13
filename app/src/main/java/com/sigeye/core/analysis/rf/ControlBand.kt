package com.sigeye.core.analysis.rf

import kotlin.math.abs

/**
 * Two radios pinned for the length of a before-and-after, one in each band.
 *
 * Pinned by BSSID at the start, and then followed whatever happens, because the alternative
 * is the trap this control exists to avoid. Averaging "all the 2.4 GHz access points I can
 * hear right now" looks sensible and is backwards: switch the oven on, the weak ones stop
 * being heard, and the average of the survivors goes *up*. A fixed pair of radios has no
 * survivorship to bias it - if one stops being heard, that is the measurement.
 */
data class ControlRadios(
    /** The 2.4 GHz radio being watched. Null when nothing was audible there. */
    val low: Radio?,
    /** The 5 GHz radio being watched, which is the control proper. */
    val high: Radio?,
    /**
     * True when both radios belong to one box.
     *
     * Worth a great deal. Two radios in the same enclosure at the same distance through the
     * same walls differ only by frequency, so everything else cancels and what is left is
     * the band. Two different boxes in different corners are a much weaker control, and the
     * screen has to say which one it got.
     */
    val sameBox: Boolean,
) {
    val usable: Boolean get() = low != null && high != null

    fun describe(): String = when {
        !usable -> "No control pair - one of the two bands had nothing audible."
        sameBox -> "Watching both radios of ${label()}, which is one box, so everything " +
            "except the frequency cancels."
        else -> "Watching ${low?.ssid ?: low?.bssid} on 2.4 and ${high?.ssid ?: high?.bssid} " +
            "on 5. Different boxes, so this is a weaker control than one access point " +
            "with both radios."
    }

    fun label(): String = high?.ssid?.takeIf { it.isNotBlank() }
        ?: low?.ssid?.takeIf { it.isNotBlank() }
        ?: high?.bssid
        ?: low?.bssid
        ?: "nothing"
}

/** What the control said about the thing being tested. */
enum class ControlVerdict(val label: String) {
    /** Not enough to judge on: no pair, or too few Wi-Fi scans in a phase. */
    UNAVAILABLE("No control"),

    /** The finding. 2.4 took a hit and 5 did not, which is what a 2.45 GHz emitter does. */
    ONLY_LOW_FELL("2.4 GHz fell, 5 GHz did not"),

    /** Both fell, so something that is not frequency-specific changed. Usually you moved. */
    BOTH_FELL("Both bands fell"),

    /** Neither moved, so whatever happened to the advertisements was not the whole band. */
    NEITHER_FELL("Neither band moved"),

    /** 5 fell and 2.4 did not. Not a thing an oven does; worth saying so plainly. */
    ONLY_HIGH_FELL("5 GHz fell, 2.4 GHz did not"),
}

/**
 * A control band for a before-and-after in 2.4 GHz.
 *
 * Without one, "the oven flattened the band" is dramatic and unfalsifiable: every way a
 * measurement can go wrong - the phone moved, the source moved, somebody walked between
 * them, the source's battery gave out - produces exactly the same picture as a leaking
 * magnetron. A 5 GHz radio sitting in the same room is not affected by anything radiating
 * at 2.45 GHz, so it separates the two cases. *2.4 collapsed and 5 did not* is a finding.
 * *Both collapsed* is a failed experiment, and being told so is the point.
 */
object ControlBand {

    /**
     * What a pinned radio is recorded at when a scan does not contain it.
     *
     * Censored at a stated limit rather than dropped. Dropping it is the survivorship bug
     * again - the phase where the radio kept vanishing would be scored on only its good
     * scans - and inventing a plausible number is worse. This says "below what this phone
     * could hear", which is true, and the screen says it too.
     */
    const val FLOOR_DBM = -100.0

    /** Fewer Wi-Fi scans than this in a phase and there is nothing worth judging. */
    const val MIN_SCANS = 3

    /**
     * How far a band has to drop to count as having fallen.
     *
     * Wi-Fi RSSI from a handheld phone wanders by a couple of dB with nothing happening at
     * all, so anything under this is noise. Three dB is also half the power, which is a
     * comfortable thing to have to justify.
     */
    const val DROP_DB = 3.0

    /**
     * Picks the pair to watch.
     *
     * One box with two radios beats two boxes every time, so a same-hardware pair is taken
     * first and the strongest one at that. Failing that, the loudest radio in each band,
     * which is a weaker control and is reported as one.
     */
    fun choose(radios: List<Radio>): ControlRadios {
        val pair = DualBand.pair(radios)
            .filter { it.sameHardware }
            .maxByOrNull { it.high.rssi }
        if (pair != null) return ControlRadios(pair.low, pair.high, sameBox = true)

        return ControlRadios(
            low = radios.filterNot { it.isHighBand }.maxByOrNull { it.rssi },
            high = radios.filter { it.isHighBand }.maxByOrNull { it.rssi },
            sameBox = false,
        )
    }

    /** One scan's reading for a pinned radio, censored at the floor when it is absent. */
    fun levelOf(bssid: String?, scan: Map<String, Int>): Double? {
        if (bssid == null) return null
        return scan[bssid]?.toDouble() ?: FLOOR_DBM
    }

    /** True when a phase pair has enough scans in it to be worth reading. */
    fun enough(result: AbResult?): Boolean =
        result != null && result.baseline.samples >= MIN_SCANS && result.test.samples >= MIN_SCANS

    private fun fell(result: AbResult?): Boolean =
        result != null && enough(result) && result.delta <= -DROP_DB

    fun judge(low: AbResult?, high: AbResult?): ControlVerdict {
        if (!enough(low) || !enough(high)) return ControlVerdict.UNAVAILABLE
        val lowFell = fell(low)
        val highFell = fell(high)
        return when {
            lowFell && !highFell -> ControlVerdict.ONLY_LOW_FELL
            lowFell && highFell -> ControlVerdict.BOTH_FELL
            !lowFell && highFell -> ControlVerdict.ONLY_HIGH_FELL
            else -> ControlVerdict.NEITHER_FELL
        }
    }

    /** The verdict in words, including what to do about it. */
    fun explain(verdict: ControlVerdict, low: AbResult?, high: AbResult?): String {
        val lowDrop = low?.delta
        val highDrop = high?.delta
        return when (verdict) {
            ControlVerdict.UNAVAILABLE ->
                "No control yet. This needs a 2.4 GHz and a 5 GHz access point audible " +
                    "throughout, and at least $MIN_SCANS Wi-Fi scans in each phase - which " +
                    "means running each phase for a minute rather than the thirty seconds " +
                    "the advertisement count needs."

            ControlVerdict.ONLY_LOW_FELL ->
                "2.4 GHz dropped ${magnitude(lowDrop)} while 5 GHz moved ${signed(highDrop)}. " +
                    "That is the finding, and it is the shape only a 2.45 GHz emitter makes. " +
                    "Anything that moved the phone or the room would have taken both bands " +
                    "down together."

            ControlVerdict.BOTH_FELL ->
                "Both bands fell - 2.4 by ${magnitude(lowDrop)} and 5 by ${magnitude(highDrop)}. " +
                    "An oven cannot do that: it radiates at 2.45 GHz and 5 GHz is nowhere " +
                    "near it. Something else changed between the two phases, and the " +
                    "likeliest thing is that the phone moved. Run it again without " +
                    "touching it."

            ControlVerdict.NEITHER_FELL ->
                "Neither band moved. Whatever happened to the advertisement count, it was " +
                    "not the whole of 2.4 GHz going quiet - so either the oven is well " +
                    "sealed, or the source you were listening to was the only thing affected."

            ControlVerdict.ONLY_HIGH_FELL ->
                "5 GHz fell by ${magnitude(highDrop)} and 2.4 GHz did not. Nothing about an " +
                    "oven does that. Most likely the 5 GHz radio was marginal to begin with " +
                    "and drifted below what this phone can hear."
        }
    }

    /** How far it moved, without a sign, for sentences that already say which way. */
    private fun magnitude(db: Double?): String =
        if (db == null) "nothing" else "%.1f dB".format(java.util.Locale.US, abs(db))

    /** With a sign, for sentences that do not. */
    private fun signed(db: Double?): String =
        if (db == null) "nothing" else "%+.1f dB".format(java.util.Locale.US, db)
}
