package com.sigeye.core.analysis.presence

/**
 * How busy the airwaves are where you are standing.
 *
 * Several experiments have a number in them that is genuinely a function of density rather
 * than of physics. How long Discovery should learn a room before it starts reporting
 * arrivals; how big a burst has to be before Train Spotter calls it a train; how long
 * something must linger before Dwell Time calls it a resident. A value that is right in a
 * cottage is wrong on a concourse and vice versa, and neither is more correct.
 *
 * Physics is not in here and never will be. dB thresholds, the free-space path loss term,
 * the specification's rotation timeout - those do not care where you are, and a profile
 * that quietly moved them would be the app lying about a measurement.
 */
enum class Density(val label: String, val blurb: String) {
    QUIET(
        "Quiet",
        "A house in the country, a workshop, a field. A handful of devices and most of " +
            "them yours.",
    ),
    SUBURBAN(
        "Suburban",
        "A street of houses, a small office. Neighbors audible through the walls.",
    ),
    URBAN(
        "Urban",
        "A flat in a city, a busy office, a high street. Dozens of strangers in range at " +
            "any moment.",
    ),
    CROWDED(
        "Crowded",
        "A train, a concourse, a shopping center. Hundreds of devices, most of them " +
            "passing through.",
    ),
}

/** The numbers a [Density] sets, and which experiment each one belongs to. */
data class Tuning(
    /** Discovery: how long to learn a room before reporting anything as new. */
    val baselineSeconds: Int,
    /** Train Spotter: multiple of the rolling baseline that counts as a burst. */
    val burstMultiple: Double,
    /** Train Spotter: never alert below this many devices, however quiet it is. */
    val minimumBurst: Int,
    /** Dwell Time: minutes present before something counts as living here. */
    val residentMinutes: Int,
    /** Crowd Counter: how many advertising devices one person tends to carry. */
    val devicesPerPerson: Double,
    /** Pickers and live lists: how long since a packet before a device drops off. */
    val freshnessSeconds: Int,
)

/**
 * Picking the profile rather than asking for it.
 *
 * Asking a user to classify where they are is a worse version of a measurement the app can
 * make for itself: it already counts how many distinct addresses arrive per minute, which
 * is the quantity the profiles are really about.
 *
 * The honest caveat, which the screen says too: this measures addresses and not people. A
 * street where every phone rotates every fifteen minutes produces more distinct addresses
 * than one where nothing does, at identical human density, so the reading is of radio
 * business rather than of crowd size. For choosing how long to spend learning a room, that
 * is the right quantity anyway.
 *
 * Pure and Android-free.
 */
object Environment {

    /** Distinct addresses per minute at the boundary between each pair of profiles. */
    const val SUBURBAN_FROM = 8.0
    const val URBAN_FROM = 25.0
    const val CROWDED_FROM = 70.0

    /** Below this many seconds of listening, a rate is noise and no profile is offered. */
    const val MINIMUM_SAMPLE_MS = 45_000L

    val DEFAULT = Density.SUBURBAN

    fun tuningFor(density: Density): Tuning = when (density) {
        Density.QUIET -> Tuning(
            baselineSeconds = 20,
            burstMultiple = 2.5,
            minimumBurst = 3,
            residentMinutes = 10,
            devicesPerPerson = 1.5,
            freshnessSeconds = 30,
        )
        Density.SUBURBAN -> Tuning(
            baselineSeconds = 30,
            burstMultiple = 3.0,
            minimumBurst = 4,
            residentMinutes = 20,
            devicesPerPerson = 2.0,
            freshnessSeconds = 20,
        )
        Density.URBAN -> Tuning(
            baselineSeconds = 60,
            burstMultiple = 3.5,
            minimumBurst = 8,
            residentMinutes = 30,
            devicesPerPerson = 2.5,
            freshnessSeconds = 15,
        )
        Density.CROWDED -> Tuning(
            baselineSeconds = 120,
            burstMultiple = 4.0,
            minimumBurst = 15,
            residentMinutes = 45,
            devicesPerPerson = 2.5,
            freshnessSeconds = 12,
        )
    }

    /** Distinct addresses heard per minute. */
    fun ratePerMinute(distinctAddresses: Int, overMs: Long): Double =
        if (overMs <= 0) 0.0 else distinctAddresses * 60_000.0 / overMs

    /**
     * Which profile fits what has been heard, or null if it is too early to say.
     *
     * Null rather than a guess: forty-five seconds is the shortest window in which a rate
     * means anything, and offering "Quiet" to somebody who has been listening for six
     * seconds would be confidently wrong at the moment they are most likely to believe it.
     */
    fun detect(distinctAddresses: Int, overMs: Long): Density? {
        if (overMs < MINIMUM_SAMPLE_MS) return null
        val rate = ratePerMinute(distinctAddresses, overMs)
        return when {
            rate >= CROWDED_FROM -> Density.CROWDED
            rate >= URBAN_FROM -> Density.URBAN
            rate >= SUBURBAN_FROM -> Density.SUBURBAN
            else -> Density.QUIET
        }
    }

    /** What the detector saw, for a screen that should show its working. */
    fun describe(distinctAddresses: Int, overMs: Long): String {
        if (overMs <= 0) return "nothing heard yet"
        val rate = ratePerMinute(distinctAddresses, overMs)
        return String.format(
            java.util.Locale.US,
            "%.0f new addresses a minute over %d seconds",
            rate,
            overMs / 1000,
        )
    }
}
