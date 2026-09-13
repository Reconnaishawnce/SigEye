package com.sigeye.core.analysis.rf

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One transmitter's footprint on the band.
 *
 * Wi-Fi channels are not slots, they are overlapping blocks of spectrum - which is the
 * whole reason 1, 6 and 11 exist as a convention - so a transmitter has to be described by
 * where its center is and how wide it is, not by a channel number.
 */
data class Occupant(
    val centerMhz: Int,
    val widthMhz: Int,
    val rssi: Int,
    val label: String? = null,
) {
    val lowMhz: Int get() = centerMhz - widthMhz / 2
    val highMhz: Int get() = centerMhz + widthMhz / 2

    /**
     * How much of this transmitter lands inside a span, from none to all of it.
     *
     * A 40 MHz access point half over a 20 MHz channel is putting half its power there,
     * and counting it as a whole neighbor would overstate the damage by the same factor
     * as ignoring it understates it.
     */
    fun overlapFraction(lowMhz: Int, highMhz: Int): Double {
        if (widthMhz <= 0) return 0.0
        val from = maxOf(this.lowMhz, lowMhz)
        val to = minOf(this.highMhz, highMhz)
        val shared = (to - from).coerceAtLeast(0)
        return shared.toDouble() / widthMhz
    }
}

/** What is sitting on one 20 MHz channel. */
data class ChannelLoad(
    val channel: Int,
    val centerMhz: Int,
    /** Transmitters putting any power at all into this channel. */
    val occupants: Int,
    /** Everything overlapping it, added up as power rather than as decibels. */
    val loadDbm: Double?,
    val strongestDbm: Int?,
) {
    val busy: Boolean get() = loadDbm != null && loadDbm >= Spectrum.BUSY_DBM
}

/** Where a Bluetooth advertising channel sits, and what is on top of it. */
data class AdvertChannelLoad(
    val channel: Int,
    val frequencyMhz: Int,
    val loadDbm: Double?,
    val occupants: Int,
) {
    val clear: Boolean get() = loadDbm == null || loadDbm < Spectrum.BUSY_DBM
}

/**
 * Who is using the 2.4 GHz band, and whether Bluetooth has anywhere left to shout.
 *
 * Bluetooth Low Energy advertises on three fixed frequencies, and they were not chosen at
 * random: 2402 and 2480 sit at the two edges of the band, and 2426 sits in the gap between
 * Wi-Fi channels 1 and 6. On a band where everyone follows the 1/6/11 convention all three
 * are in the clear. On a band where someone has parked on channel 3 or 9 - or run a 40 MHz
 * access point - they are not, and that is a direct, visible explanation for a link that
 * keeps stuttering.
 *
 * Power is summed linearly and only then converted back to decibels. Adding decibels
 * directly is the most common mistake in this kind of arithmetic and it is not a small
 * one: two equal signals are 3 dB together, not twice the number.
 *
 * Pure and Android-free.
 */
object Spectrum {

    /** Bluetooth Low Energy's three advertising frequencies, in MHz. */
    const val ADVERT_37_MHZ = 2402
    const val ADVERT_38_MHZ = 2426
    const val ADVERT_39_MHZ = 2480

    val ADVERT_CHANNELS: Map<Int, Int> = linkedMapOf(
        37 to ADVERT_37_MHZ,
        38 to ADVERT_38_MHZ,
        39 to ADVERT_39_MHZ,
    )

    /** A Bluetooth receiver's bandwidth: 2 MHz for LE, 1 MHz for the original. */
    const val ADVERT_WIDTH_MHZ = 2

    /**
     * Above this, summed neighbor power is loud enough to matter.
     *
     * Not a hard physical threshold - it is a judgement, set where a neighbor is strong
     * enough to force retries rather than merely be audible. Roughly the level at which a
     * phone would happily associate with the interferer itself.
     */
    const val BUSY_DBM = -70.0

    /** The 2.4 GHz channels in use somewhere in the world, in channel numbers. */
    val CHANNELS_24: List<Int> = (1..13) + listOf(14)

    /** Center frequency of a 2.4 GHz channel. Fourteen is the odd one out, as ever. */
    fun centerOf(channel: Int): Int = if (channel == 14) 2484 else 2407 + channel * 5

    /**
     * Everything overlapping a span, added as power and returned as dBm.
     *
     * Null when nothing at all overlaps, which is a different answer from "quiet" and is
     * worth keeping distinct - an empty band and an unmeasured one look identical once
     * flattened to a number.
     */
    fun loadOver(lowMhz: Int, highMhz: Int, occupants: List<Occupant>): Double? {
        var milliwatts = 0.0
        var counted = 0
        occupants.forEach { occupant ->
            val fraction = occupant.overlapFraction(lowMhz, highMhz)
            if (fraction <= 0.0) return@forEach
            counted++
            milliwatts += 10.0.pow(occupant.rssi / 10.0) * fraction
        }
        if (counted == 0 || milliwatts <= 0.0) return null
        return 10.0 * log10(milliwatts)
    }

    /** How many transmitters put any power into a span. */
    fun occupantsOver(lowMhz: Int, highMhz: Int, occupants: List<Occupant>): List<Occupant> =
        occupants.filter { it.overlapFraction(lowMhz, highMhz) > 0.0 }

    /** The 2.4 GHz band as twenty-megahertz channels, loudest neighbor and all. */
    fun channels24(occupants: List<Occupant>): List<ChannelLoad> = CHANNELS_24.map { channel ->
        val center = centerOf(channel)
        val low = center - 10
        val high = center + 10
        val over = occupantsOver(low, high, occupants)
        ChannelLoad(
            channel = channel,
            centerMhz = center,
            occupants = over.size,
            loadDbm = loadOver(low, high, occupants),
            strongestDbm = over.maxOfOrNull { it.rssi },
        )
    }

    /** What is sitting on top of each Bluetooth advertising channel. */
    fun advertChannels(occupants: List<Occupant>): List<AdvertChannelLoad> =
        ADVERT_CHANNELS.map { (channel, frequency) ->
            val low = frequency - ADVERT_WIDTH_MHZ / 2
            val high = frequency + ADVERT_WIDTH_MHZ / 2
            AdvertChannelLoad(
                channel = channel,
                frequencyMhz = frequency,
                loadDbm = loadOver(low, high, occupants),
                occupants = occupantsOver(low, high, occupants).size,
            )
        }

    /**
     * The emptiest of 1, 6 and 11.
     *
     * Deliberately restricted to those three. Recommending channel 4 because it happens to
     * be quiet right now is how a band ends up with nothing but partial overlaps, where
     * every network degrades every other network instead of taking turns politely.
     */
    fun quietestOfOneSixEleven(occupants: List<Occupant>): ChannelLoad? =
        channels24(occupants)
            .filter { it.channel == 1 || it.channel == 6 || it.channel == 11 }
            .minByOrNull { it.loadDbm ?: Double.NEGATIVE_INFINITY }

    /**
     * Access points sitting somewhere other than 1, 6 or 11.
     *
     * These are the ones that turn a band with three lanes into a band with none, and they
     * are also what puts power onto advertising channel 38.
     */
    fun offGrid(occupants: List<Occupant>, minRssi: Int = -85): List<Occupant> =
        occupants.filter {
            it.centerMhz in 2400..2500 &&
                it.rssi >= minRssi &&
                it.centerMhz != 2412 && it.centerMhz != 2437 && it.centerMhz != 2462
        }

    /**
     * A rough share of the band that is genuinely in use, for a single headline number.
     *
     * The fraction of twenty-megahertz channels carrying a neighbor above [BUSY_DBM],
     * counted over the thirteen channels anyone actually uses. Not an airtime measurement -
     * a phone cannot measure airtime - and the wording in the UI says so.
     */
    fun busyFraction(occupants: List<Occupant>): Float {
        val channels = channels24(occupants).filter { it.channel in 1..13 }
        if (channels.isEmpty()) return 0f
        return channels.count { it.busy }.toFloat() / channels.size
    }

    /** dBm to a 0..1 bar height, clamped to a range a room actually produces. */
    fun barHeight(dbm: Double?, floor: Double = -95.0, ceiling: Double = -35.0): Float {
        val value = dbm ?: return 0f
        return (((value - floor) / (ceiling - floor)).coerceIn(0.0, 1.0)).toFloat()
    }

    /** Channel width from Android's ScanResult constants, which are an enum of widths. */
    fun widthFromAndroid(constant: Int): Int = when (constant) {
        0 -> 20
        1 -> 40
        2 -> 80
        3 -> 160
        4 -> 80 // 80+80, two disjoint blocks; treated as the one we were told the center of
        5 -> 320
        else -> 20
    }

    fun formatDbm(dbm: Double?): String = dbm?.let { "${it.roundToInt()} dBm" } ?: "quiet"

    /**
     * The band as measured: every channel, every advertising frequency, every occupant.
     *
     * Three sections in one file rather than three files. Someone reading it later wants
     * to see the occupant that explains the channel load in the same place as the load,
     * and a spreadsheet skips the hash-commented section headers happily.
     */
    fun csv(occupants: List<Occupant>): String = buildString {
        val band = occupants.filter { it.centerMhz in 2400..2500 }
        appendLine(
            "# occupants_24ghz=${band.size} busy_fraction=" +
                String.format(Locale.US, "%.2f", busyFraction(band)) +
                " busy_threshold_dbm=$BUSY_DBM",
        )
        appendLine("# section=wifi_channels")
        appendLine("channel,center_mhz,occupants,load_dbm,strongest_dbm,busy")
        channels24(band).forEach {
            appendLine(
                "${it.channel},${it.centerMhz},${it.occupants}," +
                    (it.loadDbm?.let { load -> String.format(Locale.US, "%.2f", load) } ?: "") +
                    ",${it.strongestDbm ?: ""},${it.busy}",
            )
        }
        appendLine("# section=ble_advertising_channels")
        appendLine("channel,frequency_mhz,occupants,load_dbm,clear")
        advertChannels(band).forEach {
            appendLine(
                "${it.channel},${it.frequencyMhz},${it.occupants}," +
                    (it.loadDbm?.let { load -> String.format(Locale.US, "%.2f", load) } ?: "") +
                    ",${it.clear}",
            )
        }
        appendLine("# section=occupants")
        appendLine("label,center_mhz,width_mhz,rssi_dbm")
        band.sortedByDescending { it.rssi }.forEach {
            appendLine(
                "${it.label?.replace(',', ' ') ?: ""},${it.centerMhz},${it.widthMhz},${it.rssi}",
            )
        }
    }
}
