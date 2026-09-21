package com.sigeye.core

/**
 * Where a replay has got to, as a pure mapping from real elapsed time to capture time.
 *
 * Separated from the machinery so the arithmetic that everything downstream depends on can
 * be tested without a radio, a file or a coroutine.
 */
data class ReplayClock(
    /** Wall clock reading when the replay started. */
    val startedAtMs: Long,
    /** Timestamp of the first packet in the capture. */
    val firstPacketMs: Long,
    /** Timestamp of the last packet in the capture. */
    val lastPacketMs: Long,
    /** How much faster than life. 1.0 is the original pace. */
    val speed: Double = 1.0,
) {
    val spanMs: Long get() = (lastPacketMs - firstPacketMs).coerceAtLeast(0L)

    /** How long this will take to play at [speed]. */
    val realDurationMs: Long
        get() = if (speed <= 0.0) spanMs else (spanMs / speed).toLong()

    /**
     * Where the capture has got to, given the wall clock.
     *
     * The anchor of the whole design. A packet is emitted carrying a timestamp on this same
     * timeline, so the difference between any two packets is exactly what it was when they
     * were recorded, whatever speed the replay is running at. Compressing the timestamps to
     * match the wall clock instead would make every interval in the app wrong by the speed
     * factor, and an advertising interval is a fingerprint here.
     */
    fun nowMs(wallMs: Long): Long {
        val elapsed = (wallMs - startedAtMs).coerceAtLeast(0L)
        return firstPacketMs + (elapsed * speed).toLong()
    }

    /** Wall clock reading at which a packet from [packetMs] is due to be emitted. */
    fun dueAtMs(packetMs: Long): Long {
        val into = (packetMs - firstPacketMs).coerceAtLeast(0L)
        return startedAtMs + if (speed <= 0.0) into else (into / speed).toLong()
    }

    /** How far through, from 0 to 1. */
    fun progress(wallMs: Long): Float {
        if (spanMs <= 0L) return 1f
        val into = nowMs(wallMs) - firstPacketMs
        return (into.toDouble() / spanMs).coerceIn(0.0, 1.0).toFloat()
    }

    /** Whether the capture has run out. */
    fun finished(wallMs: Long): Boolean = nowMs(wallMs) >= lastPacketMs
}

/**
 * The time every measurement in this app should be taken against.
 *
 * There are two clocks in a program that can replay a recording, and confusing them is the
 * bug that makes a replay useless. One is what the wall says. The other is where the
 * recording has got to. A packet captured last Tuesday is three seconds after the packet
 * before it, forever, and that three seconds is the measurement - it does not become a
 * tenth of a second because somebody is playing the file back at thirty times speed.
 *
 * So a replay emits packets carrying their original spacing, and this reports the capture's
 * own time while one is running. Anything asking how long ago a packet arrived, how stale a
 * reading is, or how far apart two advertisements were gets an answer that means the same
 * thing live and replayed.
 *
 * Wall clock is still the right answer for some things and they should keep asking for it
 * directly: when a file was written, how long a progress bar has been spinning, what to put
 * on a timestamp somebody will read. Those are facts about now rather than about the data.
 */
object Clock {

    @Volatile
    private var replay: ReplayClock? = null

    /** Called by the replay machinery. Null puts everything back on the wall clock. */
    fun useReplay(clock: ReplayClock?) {
        replay = clock
    }

    /** Where the data being read has got to. Wall clock when nothing is being replayed. */
    fun nowMs(): Long {
        val running = replay ?: return System.currentTimeMillis()
        return running.nowMs(System.currentTimeMillis())
    }

    /** Whether readings are coming from a recording rather than the radio. */
    val replaying: Boolean get() = replay != null

    /** The clock in force, for a screen that wants to show progress. */
    val current: ReplayClock? get() = replay
}
