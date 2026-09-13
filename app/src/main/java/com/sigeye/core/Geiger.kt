package com.sigeye.core

/** What the pocket should be doing right now. */
sealed interface Beat {

    /** Nothing to track. */
    data object Idle : Beat

    /**
     * A pulse, and how long to wait before the next one.
     *
     * [strength] runs 0 to 1 with closeness, and it is not decoration. Rate alone is hard
     * to read through a coat: a pulse that also gets firmer as it gets faster is legible
     * with the phone in a pocket, which is the entire point of the mode.
     */
    data class Pulse(val intervalMs: Long, val strength: Double) : Beat

    /** Heard nothing for a while. A distinct pattern, because it is a distinct fact. */
    data class Lost(val silentForMs: Long) : Beat
}

/**
 * Following something by feel, with the phone in a pocket.
 *
 * The screen is the worst part of this app in the field. Following somebody while looking
 * at a phone is conspicuous, it is bad tradecraft, and it means the one moment worth
 * watching is the one nobody is watching. A pulse that speeds up as the target gets nearer
 * turns the whole thing into something you can carry.
 *
 * The mapping is linear in decibels rather than in power. Power spans five orders of
 * magnitude across a room, which as a pulse rate would be unusable at one end and
 * indistinguishable at the other; decibels are the scale the reading is already on and the
 * one the rate should follow.
 *
 * It is not distance and the screen says so. Signal strength through a body, a bag and a
 * wall is not a metre reading, and somebody who forgot that would walk straight past.
 *
 * Pure and Android-free.
 */
object Geiger {

    /** At or above this, the pulse is as fast as it gets. Arm's length in open air. */
    const val NEAR_DBM = -45.0

    /** At or below this, as slow as it gets. Near the edge of hearing. */
    const val FAR_DBM = -95.0

    /** Fastest pulse. Below about this, separate buzzes stop being separate. */
    const val FASTEST_MS = 110L

    /** Slowest pulse. Longer than this and it reads as having stopped. */
    const val SLOWEST_MS = 1_800L

    /** Silence past this and the beat says lost rather than far away. */
    const val STALE_MS = 6_000L

    /** How often to pulse once it is lost. Slow, and unmistakably not the tracking beat. */
    const val LOST_INTERVAL_MS = 3_000L

    /**
     * What to do, given the level and how long since the last packet.
     *
     * Silence is reported as its own thing rather than as a very weak signal. Those feel
     * identical through a coat and mean completely different things: one is the target
     * getting further away, the other is the target having gone.
     */
    fun beat(recentRssi: Double?, silentForMs: Long): Beat {
        val level = recentRssi ?: return Beat.Idle
        if (silentForMs >= STALE_MS) return Beat.Lost(silentForMs)
        return Beat.Pulse(intervalMs(level), strength(level))
    }

    /** Where a level sits between far and near, 0 to 1. */
    fun closeness(rssi: Double): Double =
        ((rssi - FAR_DBM) / (NEAR_DBM - FAR_DBM)).coerceIn(0.0, 1.0)

    fun intervalMs(rssi: Double): Long {
        val near = closeness(rssi)
        return (SLOWEST_MS - near * (SLOWEST_MS - FASTEST_MS)).toLong()
    }

    /**
     * How hard to pulse.
     *
     * Never zero. A pulse you cannot feel is the same as no pulse, and at the far end this
     * mode still has to be saying something rather than appearing to have switched itself
     * off.
     */
    fun strength(rssi: Double): Double = 0.25 + closeness(rssi) * 0.75

    /** The line under the button, which has to keep saying what this is not. */
    fun describe(rssi: Double?): String = when {
        rssi == null -> "Nothing to follow yet."
        else -> "Faster and firmer as the signal gets stronger, about " +
            "${1_000 / intervalMs(rssi)} a second now. Signal strength through a body, a " +
            "bag and a wall is not a distance, so this points at loudness rather than at " +
            "metres."
    }
}
