package com.sigeye.core.analysis.rf

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** One radio of an access point, averaged over however many scans it has been seen in. */
data class Radio(
    val bssid: String,
    val ssid: String?,
    val frequencyMhz: Int,
    val rssi: Double,
    val scans: Int = 1,
) {
    val isHighBand: Boolean get() = frequencyMhz >= 4900

    /** The first five octets, which most vendors keep identical across a node's radios. */
    val stem: String get() = bssid.uppercase(Locale.US).substringBeforeLast(':')

    val oui: String get() = bssid.uppercase(Locale.US).split(":").take(3).joinToString(":")
}

/** The same access point's two radios, matched up. */
data class BandPair(
    val ssid: String?,
    val low: Radio,
    val high: Radio,
    /**
     * True when the two BSSIDs differ only in the last octet.
     *
     * That is one physical box saying so. Matching on network name alone is a guess, and
     * in a building with mesh nodes or repeaters it is frequently the wrong guess - so the
     * difference is carried through to the screen rather than being smoothed over.
     */
    val sameHardware: Boolean,
) {
    val key: String get() = "${low.bssid}|${high.bssid}"

    /** How much weaker the 5 GHz radio is here, in dB. Positive means 5 GHz is quieter. */
    val gapDb: Double get() = low.rssi - high.rssi

    /**
     * The part of that gap that is frequency alone.
     *
     * Two co-located antennas at the same distance still differ, because the effective
     * area of a receiving antenna falls with the square of frequency. Twenty log of the
     * ratio - about 6.6 dB between 2.4 and 5.2 GHz - and it is there in a field with
     * nothing in it at all.
     */
    val freeSpaceGapDb: Double
        get() = 20.0 * log10(high.frequencyMhz.toDouble() / low.frequencyMhz.toDouble())

    val scans: Int get() = minOf(low.scans, high.scans)

    fun label(): String = ssid?.takeIf { it.isNotBlank() } ?: low.bssid
}

/**
 * What one pair looks like from somewhere, measured against a baseline taken elsewhere.
 *
 * The gap on its own is not the measurement. An access point does not transmit at the same
 * power on both radios - the regulations differ, and so does whatever the vendor chose -
 * and this phone does not receive both bands with the same sensitivity either. All of that
 * is a constant, so it cancels the moment you compare two places instead of reading one.
 */
data class Penetration(
    val pair: BandPair,
    val baselineGapDb: Double?,
) {
    /** Extra loss the 5 GHz radio has taken since the baseline, in dB. */
    val excessDb: Double?
        get() = baselineGapDb?.let { pair.gapDb - it }

    val usable5: Boolean get() = pair.high.rssi >= DualBand.USABLE_DBM
    val usable24: Boolean get() = pair.low.rssi >= DualBand.USABLE_DBM

    /** The honest headline: what this excess means for whether to stay on 5 GHz. */
    fun verdict(): String? {
        val excess = excessDb ?: return null
        return when {
            excess < DualBand.NOTHING_IN_THE_WAY ->
                "Nothing much between here and the baseline. The gap is all frequency and " +
                    "hardware, which is what it was at the baseline too."
            !usable5 && usable24 ->
                "5 GHz has lost the argument here. It is down to " +
                    "${pair.high.rssi.roundToInt()} dBm while 2.4 is still at " +
                    "${pair.low.rssi.roundToInt()}, so a phone clinging to the faster " +
                    "band will be slower than one that gives up on it. Move a room " +
                    "closer, or set this device to prefer 2.4 GHz here."
            excess >= DualBand.SERIOUS_DB ->
                String.format(
                    Locale.US,
                    "%.0f dB of extra loss on 5 GHz alone. That is a solid obstruction " +
                        "rather than a partition - 5 GHz is still up at " +
                        "%.0f dBm, so it is working, but this is where the band starts " +
                        "costing you.",
                    excess,
                    pair.high.rssi,
                )
            else -> null
        }
    }
}

/**
 * How much worse 5 GHz is than 2.4 GHz, in the place you are standing.
 *
 * Everyone has heard that 5 GHz does not go through walls as well. It is true, and it is
 * measurable with nothing but a phone - but not by reading one number. The two radios of a
 * dual-band access point differ by a fixed amount before any wall is involved: the effective
 * area of an antenna falls with the square of frequency, worth about 6.6 dB on its own, and
 * on top of that the regulations permit different transmit powers in the two bands, the
 * vendor may have chosen different ones again, and the phone's own two receive chains are
 * not equally sensitive.
 *
 * All of that is constant. So the experiment is to measure the gap in one place, walk
 * somewhere else, and measure how much the gap *changed* - which is the part the building
 * is responsible for, and nothing else.
 *
 * Pure and Android-free.
 */
object DualBand {

    /** Below this a link is technically alive and practically not worth having. */
    const val USABLE_DBM = -75.0

    /** Under this much excess, the two bands are being treated the same by the building. */
    const val NOTHING_IN_THE_WAY = 3.0

    /** Enough extra loss that the obstruction is structural rather than a partition. */
    const val SERIOUS_DB = 10.0

    /** Scans needed before a radio's average is worth comparing against another place. */
    const val MIN_SCANS = 3

    /**
     * Matches each access point's two radios.
     *
     * Two passes, deliberately in this order. First, radios whose BSSIDs differ only in
     * the last octet: that is one box telling you its two radios are one box, and it is
     * right far more often than any other signal available here. Only what is left over
     * falls back to matching on network name and vendor prefix, which in a house with one
     * router is the same answer and in an office with forty mesh nodes is a coin toss - so
     * those pairs are marked and the screen says so.
     */
    fun pair(radios: List<Radio>): List<BandPair> {
        val low = radios.filter { !it.isHighBand }
        val high = radios.filter { it.isHighBand }
        val taken = mutableSetOf<String>()
        val pairs = mutableListOf<BandPair>()

        low.sortedByDescending { it.rssi }.forEach { lowRadio ->
            val match = high
                .filter { it.bssid !in taken && it.stem == lowRadio.stem }
                .maxByOrNull { it.rssi }
                ?: return@forEach
            taken += match.bssid
            pairs += BandPair(
                ssid = lowRadio.ssid ?: match.ssid,
                low = lowRadio,
                high = match,
                sameHardware = true,
            )
        }

        low.sortedByDescending { it.rssi }.forEach { lowRadio ->
            if (pairs.any { it.low.bssid == lowRadio.bssid }) return@forEach
            val name = lowRadio.ssid?.takeIf { it.isNotBlank() } ?: return@forEach
            val match = high
                .filter {
                    it.bssid !in taken &&
                        it.oui == lowRadio.oui &&
                        it.ssid?.takeIf { name1 -> name1.isNotBlank() } == name
                }
                .maxByOrNull { it.rssi }
                ?: return@forEach
            taken += match.bssid
            pairs += BandPair(
                ssid = name,
                low = lowRadio,
                high = match,
                sameHardware = false,
            )
        }

        return pairs.sortedByDescending { maxOf(it.low.rssi, it.high.rssi) }
    }

    /**
     * Averages readings in decibels rather than in power.
     *
     * The right choice for this: shadowing from walls and furniture is close to Gaussian in
     * dB, which is exactly the quantity being compared between two places. Averaging in
     * linear power instead would let one lucky constructive fade pull the mean up and
     * quietly make a wall look thinner than it is.
     */
    fun mean(readings: List<Int>): Double? =
        if (readings.isEmpty()) null else readings.average()

    /** Builds the comparison for every pair that has a baseline to compare against. */
    fun penetration(pairs: List<BandPair>, baseline: Map<String, Double>): List<Penetration> =
        pairs.map { Penetration(it, baseline[it.key]) }

    /**
     * One number for the whole place: the typical extra loss across every pair measured.
     *
     * The median rather than the mean, because one access point behind a lift shaft should
     * not be allowed to describe a building.
     */
    fun typicalExcessDb(penetrations: List<Penetration>): Double? {
        val values = penetrations.mapNotNull { it.excessDb }.sorted()
        if (values.isEmpty()) return null
        return values[values.size / 2]
    }

    /**
     * Whether two places actually differ, or whether this is scan noise.
     *
     * A single Wi-Fi reading wanders a few dB with nothing moving at all - the Multipath
     * Fading experiment is a whole screen about that - so a difference smaller than the
     * noise is not a thinner wall, it is the same wall measured twice.
     */
    fun meaningful(excessDb: Double?): Boolean = excessDb != null && abs(excessDb) >= 3.0

    /**
     * Every pair, with the gap here and the gap at the baseline side by side.
     *
     * Both are in the file on purpose. The excess is the answer, but it is a subtraction,
     * and a reader who cannot see what was subtracted has to take the answer on trust.
     */
    fun csv(penetrations: List<Penetration>, baselineSet: Boolean): String = buildString {
        appendLine(
            "# pairs=${penetrations.size} baseline=" +
                (if (baselineSet) "set" else "not set") +
                " typical_excess_db=" +
                (typicalExcessDb(penetrations)?.let { String.format(Locale.US, "%.2f", it) } ?: ""),
        )
        appendLine("# excess_db = gap_db - baseline_gap_db, and is the part the building did")
        appendLine(
            "ssid,bssid_24,bssid_5,freq_24_mhz,freq_5_mhz,rssi_24_dbm,rssi_5_dbm," +
                "gap_db,free_space_gap_db,baseline_gap_db,excess_db,same_hardware,usable_5",
        )
        penetrations.forEach { penetration ->
            val pair = penetration.pair
            appendLine(
                String.format(
                    Locale.US,
                    "%s,%s,%s,%d,%d,%.1f,%.1f,%.2f,%.2f,%s,%s,%b,%b",
                    pair.ssid?.replace(',', ' ') ?: "",
                    pair.low.bssid,
                    pair.high.bssid,
                    pair.low.frequencyMhz,
                    pair.high.frequencyMhz,
                    pair.low.rssi,
                    pair.high.rssi,
                    pair.gapDb,
                    pair.freeSpaceGapDb,
                    penetration.baselineGapDb?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    penetration.excessDb?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    pair.sameHardware,
                    penetration.usable5,
                ),
            )
        }
    }

    fun describe(excessDb: Double?): String = when {
        excessDb == null -> "no baseline yet"
        !meaningful(excessDb) -> "same as the baseline"
        excessDb > 0 -> String.format(Locale.US, "%.1f dB worse on 5 GHz", excessDb)
        else -> String.format(Locale.US, "%.1f dB better on 5 GHz", -excessDb)
    }
}
