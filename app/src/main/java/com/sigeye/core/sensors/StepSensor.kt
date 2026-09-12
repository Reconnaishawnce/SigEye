package com.sigeye.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Steps taken since a walk was started. */
data class StepCount(val steps: Int, val available: Boolean, val note: String?)

/**
 * How far you have actually walked, which is the hard part of measuring path loss.
 *
 * Everything in this app that talks about distance inverts a model with a guessed
 * exponent. Measuring the exponent instead needs a real distance, and a phone's honest
 * answer to "how far have I gone" is its step counter multiplied by a stride.
 *
 * [Sensor.TYPE_STEP_COUNTER] is used rather than [Sensor.TYPE_STEP_DETECTOR] because the
 * counter is maintained by dedicated low-power hardware and does not miss steps while the
 * app is busy. It counts from the last reboot, so the first reading is a baseline to
 * subtract rather than a count of anything.
 *
 * The permission is ACTIVITY_RECOGNITION on Android 10 and later. Without it the counter
 * silently returns nothing, which the screen has to be able to say out loud.
 */
class StepSensor(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _steps = MutableStateFlow(
        StepCount(
            steps = 0,
            available = counter != null,
            note = if (counter == null) {
                "This phone reports no step counter, so distance has to be entered by hand."
            } else {
                null
            },
        ),
    )
    val steps: StateFlow<StepCount> = _steps

    /** Counter value when the walk began. The sensor counts from boot, not from now. */
    private var baseline: Float? = null
    private var started = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val total = event.values.firstOrNull() ?: return
            val start = baseline ?: total.also { baseline = it }
            _steps.value = _steps.value.copy(
                steps = (total - start).toInt().coerceAtLeast(0),
                note = null,
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val sensorManager = manager ?: return
        val sensor = counter ?: return
        if (started) return
        started = true
        baseline = null
        _steps.value = _steps.value.copy(steps = 0)
        runCatching {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    /** Zeroes the count without unregistering, for starting a fresh walk. */
    fun zero() {
        baseline = null
        _steps.value = _steps.value.copy(steps = 0)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager?.unregisterListener(listener) }
    }

    val available: Boolean get() = counter != null
}
