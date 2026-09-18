package com.sigeye.core.ble

import kotlin.math.roundToInt

/**
 * How briskly a device is being heard, and what that means for somebody walking after it.
 *
 * A locator is a feedback loop: take a step, read the signal, decide. The loop only works
 * if the readings arrive faster than the steps. When a device is heard every ten seconds
 * the loop is broken, and no amount of smoothing fixes it - the number on the screen is
 * describing where somebody was standing several paces ago.
 *
 * Two different things produce a slow arrival rate and it is worth being able to tell them
 * apart. The device may genuinely be advertising slowly, which happens when a phone is
 * asleep in a pocket and there is nothing to be done about it but walk slower. Or the air
 * may be too busy for its packets to be heard, which is a thing the app can act on. The
 * measurement here is the same either way; [ScanHealth.crowded] is what separates them.
 *
 * Pure and Android-free.
 */
object Arrivals {

    /** Arrivals kept. Enough to smooth out one unlucky gap, short enough to stay current. */
    const val WINDOW = 24

    /** Below this, readings arrive faster than somebody can take a step. */
    const val QUICK_MS = 1_500L

    /** Above this, the screen is describing the past rather than the present. */
    const val SLOW_MS = 4_000L

    /** Gaps needed before saying anything. Two arrivals make one gap, which proves nothing. */
    const val MIN_GAPS = 3

    enum class Pace { UNKNOWN, QUICK, WORKABLE, SLOW }

    /** Keeps the last [WINDOW] arrivals, oldest first. */
    fun record(arrivalsMs: List<Long>, atMs: Long): List<Long> =
        (arrivalsMs + atMs).takeLast(WINDOW)

    /**
     * The typical gap between arrivals, as a median.
     *
     * A median rather than a mean because one long gap is exactly what happens when a
     * packet is lost, and an average would let that single miss describe the whole device.
     */
    fun typicalGapMs(arrivalsMs: List<Long>): Long? {
        if (arrivalsMs.size <= MIN_GAPS) return null
        val gaps = arrivalsMs.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
        if (gaps.size < MIN_GAPS) return null
        val middle = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2
    }

    fun paceOf(gapMs: Long?): Pace = when {
        gapMs == null -> Pace.UNKNOWN
        gapMs <= QUICK_MS -> Pace.QUICK
        gapMs < SLOW_MS -> Pace.WORKABLE
        else -> Pace.SLOW
    }

    /** The rate itself, in the plainest words available. */
    fun describe(gapMs: Long?): String = when {
        gapMs == null -> "Still counting how often this one speaks."
        gapMs < 400L -> "Heard several times a second."
        gapMs < 1_500L -> "Heard about once a second."
        gapMs < 90_000L -> "Heard about every ${(gapMs / 1000.0).roundToInt()} seconds."
        else -> "Heard about every ${(gapMs / 60_000.0).roundToInt()} minutes."
    }

    /**
     * What to do about it, which is the part worth putting on a screen.
     *
     * A slow device is not a broken app, and the difference between those two readings of
     * the same symptom is the difference between somebody walking slower and somebody
     * giving up.
     */
    fun advice(gapMs: Long?, crowded: Boolean): String? {
        val pace = paceOf(gapMs)
        return when {
            pace == Pace.SLOW && crowded ->
                "This is a busy place and this device is quiet. SigEye has put it under a " +
                    "filter of its own so the crowd cannot drown it out, but it still only " +
                    "speaks now and then. Take a few steps, stand still, and wait for the " +
                    "reading to catch up before deciding which way to go."

            pace == Pace.SLOW ->
                "This device only speaks now and then, which is normal for a phone asleep " +
                    "in a pocket. Move a few steps at a time and pause. Walking at normal " +
                    "pace will leave the reading behind you."

            pace == Pace.WORKABLE && crowded ->
                "Busy air. The reading lags a step or two behind you, so pause after each " +
                    "move rather than sweeping."

            else -> null
        }
    }
}
