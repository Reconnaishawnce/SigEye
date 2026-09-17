package com.sigeye.core.analysis.gnss

import java.util.Locale
import kotlin.math.abs

/** Who put the satellite up there. */
enum class Constellation(val label: String, val short: String) {
    GPS("GPS", "G"),
    GLONASS("GLONASS", "R"),
    GALILEO("Galileo", "E"),
    BEIDOU("BeiDou", "C"),
    QZSS("QZSS", "J"),
    NAVIC("NavIC", "I"),
    SBAS("SBAS", "S"),
    UNKNOWN("Unknown", "?"),
}

/**
 * Which frequency a satellite is being heard on.
 *
 * This is the part worth caring about. Civilian receivers spent thirty years on one
 * frequency each, and a single frequency cannot tell a signal that came straight down from
 * one that bounced off the building opposite. A phone hearing the same satellite on two
 * widely separated frequencies can compare them, and that is most of why a modern handset
 * is metres better in a city than an old one.
 */
enum class Band(val label: String, val centreMhz: Double, val modern: Boolean) {
    /** The original civilian signal, and still the one everything falls back to. */
    L1("L1 / E1 / B1C", 1575.42, false),

    /** BeiDou's own first civil signal, near but not on L1. */
    B1I("B1I", 1561.098, false),

    /** GLONASS, which splits its satellites across nearby channels rather than sharing one. */
    G1("G1", 1602.0, false),

    /** The one that matters. Wider, cleaner, and far harder to fool with a reflection. */
    L5("L5 / E5a / B2a", 1176.45, true),

    /** Galileo's other half of the E5 pair. */
    E5B("E5b", 1207.14, true),

    L2("L2 / G2", 1227.60, true),

    UNKNOWN("Unknown", 0.0, false),
}

/** One satellite, as the phone hears it right now. */
data class Satellite(
    val constellation: Constellation,
    /** The satellite's number within its constellation. */
    val id: Int,
    val band: Band,
    /** Carrier to noise density, in dB-Hz. Not RSSI, and it does not mean the same thing. */
    val cn0DbHz: Float,
    /** Degrees above the horizon, or null while the receiver does not know the orbit. */
    val elevationDeg: Float?,
    /** Degrees clockwise from north, or null for the same reason. */
    val azimuthDeg: Float?,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
) {
    val name: String get() = "${constellation.short}$id"

    /** Whether there is anywhere to draw it. */
    val placed: Boolean get() = elevationDeg != null && azimuthDeg != null

    /**
     * Strong enough to be worth having.
     *
     * Thirty-five dB-Hz is roughly where a receiver starts trusting a satellite. Below the
     * mid twenties it is being heard rather than used.
     */
    val strong: Boolean get() = cn0DbHz >= GOOD_CN0

    val overhead: Boolean get() = (elevationDeg ?: 0f) >= 60f

    companion object {
        const val GOOD_CN0 = 35f
    }
}

/** What the whole sky adds up to. */
data class SkyView(
    val satellites: List<Satellite>,
    val atMs: Long,
) {
    val visible: Int get() = satellites.size

    val used: List<Satellite> get() = satellites.filter { it.usedInFix }

    val constellations: List<Constellation>
        get() = satellites.map { it.constellation }.distinct().sortedBy { it.ordinal }

    val bands: List<Band>
        get() = satellites.map { it.band }.filter { it != Band.UNKNOWN }.distinct()

    /** Whether this phone is listening on a second, modern frequency. */
    val dualFrequency: Boolean get() = bands.any { it.modern }

    /** Satellites heard on both an old and a new band, which is where the accuracy comes from. */
    val onTwoBands: List<String>
        get() = satellites
            .groupBy { it.name }
            .filterValues { group -> group.map { it.band }.distinct().size > 1 }
            .keys
            .sorted()

    val medianCn0: Float?
        get() {
            val sorted = satellites.map { it.cn0DbHz }.filter { it > 0f }.sorted()
            if (sorted.isEmpty()) return null
            return if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
            }
        }

    /**
     * Whether a position is even arithmetically possible.
     *
     * Four, not three. Three would fix a point in space if the receiver's clock were
     * perfect, and it is a cheap crystal that drifts by microseconds, which is hundreds of
     * metres of light. The fourth satellite solves for the clock error, which is why a
     * phone with three satellites has no position at all rather than a poor one.
     */
    val canFix: Boolean get() = used.size >= Sky.MIN_FOR_FIX

    /**
     * How well spread the satellites being used are, 0 to 1.
     *
     * Four satellites clustered in one corner of the sky give a far worse position than
     * four spread around it, however loud they are. This is a crude stand-in for the
     * dilution of precision a receiver computes properly.
     */
    val spread: Double
        get() {
            val placed = used.mapNotNull { it.azimuthDeg }
            if (placed.size < 2) return 0.0
            val octants = placed.map { ((it % 360f + 360f) % 360f / 45f).toInt() }.distinct()
            return octants.size / 8.0
        }
}

/**
 * Reading a sky.
 *
 * The satellites are thirty times further away than anything else this app listens to, and
 * they arrive weaker than the thermal noise of the receiver hearing them. Everything else
 * in SigEye measures transmitters somebody in the room owns; this one measures a system
 * that has been overhead the whole time and is the only reason the rest of the phone knows
 * where it is.
 *
 * Pure and Android-free.
 */
object Sky {

    /** Satellites needed before a position exists. Three, plus one for the receiver's clock. */
    const val MIN_FOR_FIX = 4

    /** How far a reported carrier frequency may sit from a band's centre and still be it. */
    const val BAND_TOLERANCE_MHZ = 12.0

    /**
     * Which band a carrier frequency belongs to.
     *
     * GLONASS is the awkward one: it gives each satellite its own slightly different
     * frequency rather than sharing one, so its band is a range rather than a line and the
     * tolerance has to be generous enough to hold all of it.
     */
    fun bandFor(hz: Double?): Band {
        val mhz = hz?.takeIf { it > 0.0 }?.div(1_000_000.0) ?: return Band.UNKNOWN
        return Band.entries
            .filter { it != Band.UNKNOWN }
            .firstOrNull { abs(mhz - it.centreMhz) <= BAND_TOLERANCE_MHZ }
            ?: Band.UNKNOWN
    }

    /** One line saying what the sky over this spot is like. */
    fun verdict(view: SkyView): String = when {
        view.visible == 0 ->
            "Nothing heard yet. Satellite signals are weaker than the receiver's own noise " +
                "and it takes a moment to find them, longer indoors, and sometimes forever."

        !view.canFix -> String.format(
            Locale.US,
            "%d satellites heard but only %d usable, and a position needs %d. Three would " +
                "place a point in space if the phone's clock were perfect; the fourth is " +
                "what solves for the clock being wrong.",
            view.visible,
            view.used.size,
            MIN_FOR_FIX,
        )

        view.spread < 0.4 -> String.format(
            Locale.US,
            "%d satellites in the fix, but bunched in one part of the sky. That gives a " +
                "worse position than the same number spread around, which is why a street " +
                "between tall buildings is hard even with plenty overhead.",
            view.used.size,
        )

        view.dualFrequency -> String.format(
            Locale.US,
            "%d satellites in the fix across %d constellations, and this phone is hearing " +
                "the modern second frequency. That is what lets it tell a signal that came " +
                "straight down from one that bounced off a building.",
            view.used.size,
            view.constellations.size,
        )

        else -> String.format(
            Locale.US,
            "%d satellites in the fix across %d constellations, all on the original civil " +
                "frequency. Serviceable, and worse among tall buildings than a phone that " +
                "hears L5.",
            view.used.size,
            view.constellations.size,
        )
    }

    /**
     * What a carrier to noise figure means, in words.
     *
     * Deliberately not called signal strength. C/N0 is power against noise in one hertz of
     * bandwidth, and it is not comparable to the dBm readings everywhere else in this app -
     * a satellite at 45 dB-Hz is arriving at about -155 dBm, which would be silence to any
     * of the other experiments here.
     */
    fun describeCn0(cn0DbHz: Float): String = when {
        cn0DbHz <= 0f -> "not heard"
        cn0DbHz < 20f -> "barely there"
        cn0DbHz < 28f -> "weak, probably obstructed"
        cn0DbHz < 35f -> "usable"
        cn0DbHz < 45f -> "strong"
        else -> "very strong, a clear view"
    }

    /** Compass point for an azimuth, which is easier to check against the actual sky. */
    fun compass(azimuthDeg: Float): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((azimuthDeg % 360f) + 360f) % 360f
        return points[((normalized + 22.5f) / 45f).toInt() % 8]
    }
}
