package com.sigeye.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is being replayed, for anything that needs to say so. */
data class Replaying(
    val label: String,
    val speed: Double,
    val progress: Float,
    val packets: Int,
    val spanMs: Long,
) {
    /** The speed as somebody would say it. */
    val speedLabel: String get() = if (speed == 1.0) "real time" else "${speed.toInt()}x"
}

/**
 * Whether the app is showing a recording, held where every screen can see it.
 *
 * This exists because of the one genuinely dangerous property of the replay feature: it
 * works too well. Every experiment reads the same flow and none of them can tell where the
 * packets came from, which is exactly the point and also means a crowd count from a train
 * last Tuesday renders identically to the one in the room you are standing in. Nothing on
 * screen said otherwise.
 *
 * An app whose whole argument is that inference should not be dressed up as measurement
 * cannot quietly present recorded data as live. So the banner is mounted once, at the top
 * of everything, the way the pinned target is - putting it in each experiment would be
 * thirty places to forget it, and the one that got forgotten would be the one somebody
 * screenshotted.
 */
object Replay {

    private val _state = MutableStateFlow<Replaying?>(null)
    val state: StateFlow<Replaying?> = _state

    val active: Boolean get() = _state.value != null

    fun began(label: String, clock: ReplayClock, packets: Int) {
        Clock.useReplay(clock)
        _state.value = Replaying(
            label = label,
            speed = clock.speed,
            progress = 0f,
            packets = packets,
            spanMs = clock.spanMs,
        )
    }

    fun progressed(progress: Float) {
        _state.value = _state.value?.copy(progress = progress)
    }

    fun ended() {
        Clock.useReplay(null)
        _state.value = null
    }

    /** Speeds worth offering. See [ReplayClock] for why these do not corrupt the readings. */
    val SPEEDS = listOf(1.0, 4.0, 15.0, 60.0)

    /**
     * What a given speed costs, in words.
     *
     * Nothing about the packets changes, because they carry their own timestamps. What
     * changes is what a person can do while it runs: at real time you can watch a screen
     * react, and at sixty times an overnight capture is done before the kettle boils but
     * the display is a blur.
     */
    fun describe(speed: Double, spanMs: Long): String {
        val realMs = if (speed <= 0.0) spanMs else (spanMs / speed).toLong()
        val minutes = realMs / 60_000
        val took = when {
            realMs < 60_000 -> "${realMs / 1000} seconds"
            minutes < 60 -> "$minutes minutes"
            else -> "${minutes / 60} hours"
        }
        return if (speed == 1.0) {
            "Plays at the pace it happened, taking $took. Watch a screen react as it did."
        } else {
            "Plays ${speed.toInt()} times faster, taking $took. Gaps between packets are " +
                "still the real ones, because each packet carries its own timestamp - it " +
                "is the watching that is sped up, not the measurements."
        }
    }
}
