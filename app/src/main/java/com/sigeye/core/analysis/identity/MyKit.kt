package com.sigeye.core.analysis.identity

import kotlin.math.abs

/** One advertiser as heard over a short window, before anything has been decided about it. */
data class Nearby(
    val address: String,
    val name: String? = null,
    val vendor: String? = null,
    val rssis: List<Int> = emptyList(),
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
) {
    val packets: Int get() = rssis.size
    val spanMs: Long get() = (lastSeenMs - firstSeenMs).coerceAtLeast(0L)
}

/** Something that behaves like it is being carried by whoever is holding this phone. */
data class Owned(
    val address: String,
    val label: String,
    val medianRssi: Int,
    val why: String,
)

/**
 * Working out which devices belong to the person holding the phone.
 *
 * Every experiment in this app that counts or follows strangers is quietly wrong by a few,
 * because the watch on your wrist, the earbuds in your pocket and the car you are sitting
 * in are all in range and none of them is a stranger. In a follow it is worse than a
 * counting error: your own kit never drops out of the pool, because it goes everywhere you
 * go, which is precisely the test the pool is applying. It survives to the end and sits
 * there looking like the best answer.
 *
 * Asking somebody to type in their own MAC addresses is not a serious answer. Android will
 * not even tell an app its own Bluetooth address, so the phone cannot look them up, and
 * nobody knows what their earbuds advertise as.
 *
 * What it can do is notice the difference between a device on your person and a device in
 * the room. Three things separate them, and none of them is the loudness alone:
 *
 *  - it is loud, because it is inches away rather than across a cafe;
 *  - it stays loud, because it moves exactly when you move, so the level barely wanders
 *    while everything else in the room fades and swells as people walk between you;
 *  - it is there the whole time, with no gaps, because nothing ever comes between you.
 *
 * The middle one does the real work. A loud device across a small room is common. A device
 * whose level does not move while you turn around is on you.
 *
 * Pure and Android-free.
 */
object MyKit {

    /**
     * Loud enough to be within arm's reach.
     *
     * Deliberately not close to the strongest readings a phone produces. Earbuds in a
     * pocket with a body between them and the phone can sit well below what the same
     * earbuds read on a desk, and missing somebody's own kit is the failure that matters.
     */
    const val ON_PERSON_DBM = -60

    /** Below this many readings the numbers below mean nothing. */
    const val MIN_PACKETS = 12

    /** A window shorter than this has not seen you turn around yet. */
    const val MIN_SPAN_MS = 20_000L

    /**
     * How much the level may wander and still count as attached to you.
     *
     * The discriminator. Something in the room swings much wider than this as people pass
     * between it and the phone; something clipped to you swings very little, because the
     * geometry between the two radios does not change when you both move together.
     */
    const val MAX_WANDER_DB = 10

    /** Of the window, the share a device has to be present for. Yours never takes a break. */
    const val MIN_PRESENCE = 0.8

    /**
     * Devices that look like they belong to whoever is holding the phone.
     *
     * Ordered loudest first, because that is the order somebody will recognize them in.
     * This suggests; it never marks anything on its own. Guessing wrong here would mute a
     * stranger's phone from every count in the app, silently, which is exactly the kind of
     * error nobody would ever find.
     */
    fun candidates(seen: List<Nearby>, windowMs: Long): List<Owned> =
        seen.mapNotNull { judge(it, windowMs) }.sortedByDescending { it.medianRssi }

    private fun judge(device: Nearby, windowMs: Long): Owned? {
        if (device.packets < MIN_PACKETS) return null
        if (device.spanMs < MIN_SPAN_MS) return null
        if (windowMs > 0 && device.spanMs.toDouble() / windowMs < MIN_PRESENCE) return null

        val middle = medianOf(device.rssis) ?: return null
        if (middle < ON_PERSON_DBM) return null

        val wander = wanderOf(device.rssis)
        if (wander > MAX_WANDER_DB) return null

        return Owned(
            address = device.address,
            label = device.name?.takeIf { it.isNotBlank() } ?: device.vendor ?: device.address,
            medianRssi = middle,
            why = "$middle dBm and steady to within $wander dB for the whole window. " +
                "Something across a room does not hold that still while you move.",
        )
    }

    fun medianOf(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    /**
     * How far the level moves, ignoring the worst of it.
     *
     * A plain range would be decided by one unlucky packet, and a single deep null is
     * exactly what a hand passing over a pocket produces. The middle eighty percent
     * describes the device; the tails describe the moment.
     */
    fun wanderOf(values: List<Int>): Int {
        if (values.size < 3) return 0
        val sorted = values.sorted()
        val cut = (sorted.size * 0.1).toInt()
        val low = sorted[cut]
        val high = sorted[sorted.size - 1 - cut]
        return abs(high - low)
    }
}
