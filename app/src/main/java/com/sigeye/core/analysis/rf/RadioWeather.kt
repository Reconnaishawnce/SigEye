package com.sigeye.core.analysis.rf

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** Which radio a signal arrived on. */
enum class Source(val label: String) {
    CELLULAR("Cellular"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
}

/**
 * One transmitter the phone can hear.
 *
 * [channelKey] exists for cellular and for nothing else. A modem reports the cell it is
 * camped on and the neighbours it can see, and several of those entries are frequently the
 * same physical transmission arriving once - a serving cell and its neighbours on one
 * channel, or one channel seen by two SIMs. Adding them up would count that transmission
 * two or three times. Where a channel is known, only the strongest on it is counted.
 */
data class Emitter(
    val source: Source,
    val label: String,
    val dbm: Int,
    val channelKey: String? = null,
)

/** How much radio is arriving, and what it is arriving from. */
data class Weather(
    val emitters: List<Emitter>,
    /** Total received power, in dBm. Null when nothing was heard at all. */
    val totalDbm: Double?,
    val perSource: Map<Source, Double>,
    val counts: Map<Source, Int>,
) {
    val loudest: Emitter? get() = emitters.maxByOrNull { it.dbm }

    /** Which radio is contributing the most power, which is rarely the one people expect. */
    val dominant: Source? get() = perSource.maxByOrNull { it.value }?.key

    /**
     * What share of the total comes from the dominant radio, 0 to 1.
     *
     * Worked in power rather than in decibels, because decibels do not add and a share of
     * a logarithm is not a share of anything.
     */
    val dominantShare: Double
        get() {
            val total = perSource.values.sumOf { RadioWeather.milliwatts(it) }
            if (total <= 0.0) return 0.0
            val top = perSource.values.maxOrNull() ?: return 0.0
            return RadioWeather.milliwatts(top) / total
        }

    val heard: Int get() = emitters.size
}

/**
 * How much radio is landing on this phone, and from what.
 *
 * Deliberately not called exposure, and the name is the design decision. What a phone can
 * measure is the power arriving at its own receiver from other people's transmitters. That
 * is a real and interesting quantity and it is not what anybody means by exposure:
 *
 *  - It ignores duty cycle entirely. An access point beaconing while idle and one running
 *    flat out both report the same level, and they are not the same amount of radio.
 *  - RSSI is uncalibrated. Antenna gain, orientation, the hand holding the phone and the
 *    manufacturer's own correction all move it by several decibels.
 *  - It cannot see the transmitter that matters most. A phone's own uplink, held against a
 *    head, is larger than everything on this screen put together by orders of magnitude,
 *    and no application programming interface on any phone will report it.
 *
 * The last one is the honest heart of it. The thing people install an EMF meter to worry
 * about is the one thing the meter cannot measure, and saying so is more useful than a
 * gauge with a needle on it.
 *
 * Pure and Android-free.
 */
object RadioWeather {

    /** Below this there is effectively nothing arriving. */
    const val FLOOR_DBM = -120.0

    /** Roughly the strongest a phone reads from a transmitter in the same room. */
    const val CEILING_DBM = -25.0

    /** Anything under this is rounding error and would show as a false total. */
    private const val FLOOR_MW = 1e-15

    fun milliwatts(dbm: Double): Double = 10.0.pow(dbm / 10.0)

    fun dbm(milliwatts: Double): Double = 10.0 * log10(milliwatts.coerceAtLeast(FLOOR_MW))

    /**
     * Adds transmitters up the only way they can be added.
     *
     * Decibels are logarithms and do not sum. Two signals at -70 dBm arriving together are
     * -67, not -140, and getting this wrong is the single most common error in anything
     * that claims to measure radio.
     */
    fun combine(emitters: List<Emitter>): Weather {
        if (emitters.isEmpty()) {
            return Weather(emptyList(), null, emptyMap(), emptyMap())
        }

        val perSource = Source.entries.mapNotNull { source ->
            val mine = emitters.filter { it.source == source }
            if (mine.isEmpty()) return@mapNotNull null
            source to dbm(powerOf(mine))
        }.toMap()

        val totalMw = perSource.values.sumOf { milliwatts(it) }

        return Weather(
            emitters = emitters.sortedByDescending { it.dbm },
            totalDbm = if (totalMw <= FLOOR_MW) null else dbm(totalMw),
            perSource = perSource,
            counts = emitters.groupingBy { it.source }.eachCount(),
        )
    }

    /**
     * Power from one radio, with co-channel transmissions counted once.
     *
     * The dedup is the piece worth having from Spectre: a modem reporting a serving cell
     * and three neighbours on the same channel is reporting one transmission four times,
     * and summing them inflates cellular into a total it never had. Anything with no known
     * channel cannot be deduped and is counted on its own.
     */
    fun powerOf(emitters: List<Emitter>): Double {
        val (keyed, unkeyed) = emitters.partition { it.channelKey != null }
        val keyedMw = keyed
            .groupBy { it.channelKey }
            .values
            .sumOf { group -> milliwatts(group.maxOf { it.dbm }.toDouble()) }
        val unkeyedMw = unkeyed.sumOf { milliwatts(it.dbm.toDouble()) }
        return keyedMw + unkeyedMw
    }

    /** Where a total sits between nothing and a transmitter in the same room, 0 to 1. */
    fun scale(dbm: Double?): Float {
        val value = dbm ?: return 0f
        return ((value - FLOOR_DBM) / (CEILING_DBM - FLOOR_DBM)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * What the reading is like, in words, and never in words about safety.
     *
     * A busy place and a quiet one differ by thirty or forty decibels, which is a factor of
     * thousands in power and still nowhere near any exposure limit. Describing it as busy
     * or quiet is accurate. Describing it as high or safe would be a claim this cannot make.
     */
    fun describe(dbm: Double?): String = when {
        dbm == null -> "Nothing heard yet"
        dbm < -85 -> "Very quiet. Not much transmitting nearby."
        dbm < -70 -> "Quiet. A home or a small office."
        dbm < -55 -> "Busy. A lot of transmitters, or a few close ones."
        dbm < -40 -> "Very busy. A station, an office floor, a shopping centre."
        else -> "Something is transmitting close to this phone."
    }

    /** One line about the shape of the reading, which is more interesting than the total. */
    fun shape(weather: Weather): String {
        val dominant = weather.dominant ?: return "Nothing is arriving yet."
        val share = weather.dominantShare
        val count = weather.counts[dominant] ?: 0

        return when {
            share >= 0.9 -> String.format(
                Locale.US,
                "%s is essentially all of it: %.0f%% of the power from %d %s.",
                dominant.label,
                share * 100,
                count,
                if (count == 1) "transmitter" else "transmitters",
            )

            share >= 0.6 -> String.format(
                Locale.US,
                "%s dominates at %.0f%% of the power, though the rest is not nothing.",
                dominant.label,
                share * 100,
            )

            else -> String.format(
                Locale.US,
                "No single radio dominates. %s is the largest at %.0f%%.",
                dominant.label,
                share * 100,
            )
        }
    }
}
