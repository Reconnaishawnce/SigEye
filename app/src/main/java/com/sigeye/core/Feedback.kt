package com.sigeye.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** How an experiment should announce something while you are looking elsewhere. */
enum class AlertStyle(val label: String, val hint: String) {
    SILENT("Silent", "Screen only. Useful when you are watching it anyway."),
    BUZZ("Vibrate", "A short buzz. Good when the phone is in your hand or pocket."),
    SOUND("Beep", "An audible tone. Carries across a room."),
    BOTH("Buzz and beep", "Both, for when you are not near the phone."),
    ;

    val vibrates: Boolean get() = this == BUZZ || this == BOTH
    val beeps: Boolean get() = this == SOUND || this == BOTH
}

/**
 * Haptics and tones, in one place.
 *
 * Several experiments need to tell you something while you are looking at the room rather
 * than the screen - a locator warming up, a motion trigger, a burst going past. Doing that
 * per screen meant it was done once and forgotten everywhere else.
 *
 * Everything here fails quietly. A phone with no vibrator, or a tone generator the system
 * refuses to hand over, should cost an experiment nothing.
 */
class Feedback(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private var tones: ToneGenerator? = null

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /** A single short pulse. [strength] 0..1 scales the duration. */
    fun buzz(strength: Double = 0.6) {
        val device = vibrator ?: return
        val millis = (25 + strength.coerceIn(0.0, 1.0) * 95).toLong()
        runCatching {
            device.vibrate(
                VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    /** Two quick pulses, for something worth looking up at. */
    fun doubleBuzz() {
        val device = vibrator ?: return
        runCatching {
            device.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 120), -1),
            )
        }
    }

    fun beep(durationMs: Int = 150) {
        runCatching {
            val generator = tones ?: ToneGenerator(
                AudioManager.STREAM_NOTIFICATION,
                TONE_VOLUME,
            ).also { tones = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        }
    }

    /** Announce an event in whichever style the experiment is configured for. */
    fun alert(style: AlertStyle, urgent: Boolean = false) {
        if (style.vibrates) {
            if (urgent) doubleBuzz() else buzz(0.8)
        }
        if (style.beeps) beep(if (urgent) 220 else 140)
    }

    /** Release the tone generator. Safe to call more than once. */
    fun release() {
        runCatching { tones?.release() }
        tones = null
    }

    private companion object {
        /** Loud enough to hear across a room, quiet enough not to startle. */
        const val TONE_VOLUME = 80
    }
}
