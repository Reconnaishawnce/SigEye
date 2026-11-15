package com.sigeye.core.diagnostics

import kotlin.math.sqrt

/**
 * Telling a deliberate shake from a phone being carried.
 *
 * Walking peaks around 1.3 g and a jog around 2 g. A shake is sharper and comes in quick
 * repeats, so this asks for three peaks above [THRESHOLD_G] inside [WINDOW_MS], each at least
 * [GAP_MS] apart so one jolt ringing for a few samples counts once. After a shake it ignores
 * everything for [COOLDOWN_MS], so shaking for two seconds opens one report and not three.
 *
 * Pure and Android-free.
 */
class ShakeDetector {

    private val peaks = ArrayDeque<Long>()
    private var lastPeakMs = Long.MIN_VALUE / 2
    private var quietUntilMs = Long.MIN_VALUE / 2

    /** Feed one accelerometer reading in m/s². Returns true on the reading that completes a shake. */
    fun reading(x: Float, y: Float, z: Float, atMs: Long): Boolean {
        if (atMs < quietUntilMs) return false
        val g = sqrt(x * x + y * y + z * z) / EARTH
        if (g < THRESHOLD_G || atMs - lastPeakMs < GAP_MS) return false

        lastPeakMs = atMs
        peaks.addLast(atMs)
        while (peaks.isNotEmpty() && atMs - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size < PEAKS) return false

        peaks.clear()
        quietUntilMs = atMs + COOLDOWN_MS
        return true
    }

    companion object {
        const val EARTH = 9.81f
        const val THRESHOLD_G = 2.7f
        const val PEAKS = 3
        const val WINDOW_MS = 1_000L
        const val GAP_MS = 120L
        const val COOLDOWN_MS = 3_000L
    }
}

/** One time the screen stopped responding. */
data class Stall(
    val atMs: Long,
    val durationMs: Long,
    val screen: String,
    /** What the main thread was doing half a second in. This is the useful part. */
    val stack: List<String>,
    /** And again two seconds in, if it was still stuck, to show whether it was making progress. */
    val laterStack: List<String> = emptyList(),
) {
    val frozen: Boolean get() = durationMs >= FROZEN_MS

    companion object {
        /** Long enough that a person notices the screen stopped. */
        const val NOTICEABLE_MS = 700L

        /** Long enough that a person thinks the app has crashed. */
        const val FROZEN_MS = 2_000L
    }
}

/**
 * The last few freezes, kept in memory for the next report.
 *
 * Bounded, because the app runs for hours and a phone that stalls every minute would
 * otherwise grow this forever. Synchronized, because the watchdog thread writes and the
 * report reads from the main thread.
 *
 * Pure and Android-free.
 */
class StallLog(private val keep: Int = 20) {
    private val stalls = ArrayDeque<Stall>()
    private var total = 0
    private var totalMs = 0L

    @Synchronized
    fun record(stall: Stall) {
        stalls.addLast(stall)
        while (stalls.size > keep) stalls.removeFirst()
        total++
        totalMs += stall.durationMs
    }

    @Synchronized
    fun recent(): List<Stall> = stalls.toList()

    @Synchronized
    fun count(): Int = total

    @Synchronized
    fun totalMs(): Long = totalMs

    /** The latest stall inside the last [withinMs], for the report dialog to mention. */
    @Synchronized
    fun latestWithin(nowMs: Long, withinMs: Long): Stall? =
        stalls.lastOrNull()?.takeIf { nowMs - it.atMs <= withinMs }
}

/** Rendered frames on one screen, sorted into fine, slow and frozen. */
data class FrameTally(
    val frames: Int = 0,
    /** Over [FrameStats.SLOW_MS]: a visible stutter. */
    val slow: Int = 0,
    /** Over [Stall.NOTICEABLE_MS]: the screen stopped. */
    val frozen: Int = 0,
    val worstMs: Long = 0L,
) {
    val slowPercent: Double get() = if (frames == 0) 0.0 else 100.0 * slow / frames
}

/**
 * How smoothly each screen draws.
 *
 * A freeze is the dramatic case and stutter is the common one: a screen that drops every
 * fifth frame never trips the watchdog and still feels broken. Kept per screen, because
 * "the radar stutters" and "everything stutters" are different bugs.
 *
 * Pure and Android-free.
 */
class FrameStats {
    private val byScreen = LinkedHashMap<String, FrameTally>()

    @Synchronized
    fun frame(screen: String, durationMs: Long) {
        val tally = byScreen[screen] ?: FrameTally()
        byScreen[screen] = tally.copy(
            frames = tally.frames + 1,
            slow = tally.slow + if (durationMs > SLOW_MS) 1 else 0,
            frozen = tally.frozen + if (durationMs > Stall.NOTICEABLE_MS) 1 else 0,
            worstMs = maxOf(tally.worstMs, durationMs),
        )
        // Screens are a short list, but a route carries an address, so bound it anyway.
        while (byScreen.size > MAX_SCREENS) byScreen.remove(byScreen.keys.first())
    }

    @Synchronized
    fun snapshot(): Map<String, FrameTally> = LinkedHashMap(byScreen)

    companion object {
        /** About three dropped frames at 60 Hz. */
        const val SLOW_MS = 50L
        const val MAX_SCREENS = 40
    }
}

/**
 * Removing anything that identifies a device around the user before a report leaves the phone.
 *
 * An app that records the people nearby must not mail their addresses to anybody, even its
 * own developer, even inside a log line. Addresses become a placeholder and screen routes lose
 * the address they sometimes carry.
 *
 * Pure and Android-free.
 */
object Redact {
    private val MAC = Regex("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")

    fun text(text: String): String = MAC.replace(text, "[address]")

    /** A route such as "locate:AA:BB:CC:DD:EE:FF" becomes "locate". */
    fun screen(route: String?): String {
        if (route.isNullOrBlank()) return "home"
        val colon = route.indexOf(':')
        return if (colon > 0) route.substring(0, colon) else route
    }
}
