package com.sigeye.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How far the phone has been rolled about its long axis, and whether that is trustworthy. */
data class Roll(
    val degrees: Float,
    val available: Boolean,
    /** True while the phone is stood on end, where roll stops being measurable. */
    val degenerate: Boolean = false,
)

/**
 * Rotation about the phone's own long axis, for polarization sweeps.
 *
 * Deliberately not the compass. Body Absorption plots against heading and has spent three
 * separate attempts fighting magnetometer calibration - a sensor that returns a confident
 * wrong number near anything metal, and reports its own accuracy inconsistently across
 * vendors. Roll comes from the rotation vector's gravity component, which is the
 * accelerometer and the gyroscope, and has none of those problems: gravity is always there
 * and nothing in a room distorts it.
 *
 * The one failure mode is geometric rather than magnetic. Roll is recovered by watching
 * which way gravity leans across the phone's short axis and its face, so it needs gravity
 * to have some component there to lean. Stand the phone on end and gravity runs straight
 * down the long axis instead, leaving nothing across it: the maths then produces a number
 * that spins wildly for no movement at all. That is detected and reported rather than
 * plotted.
 */
class RollSensor(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _roll = MutableStateFlow(
        Roll(0f, available = rotationVector != null || accelerometer != null),
    )
    val roll: StateFlow<Roll> = _roll

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private var hasSmoothed = false
    private var lastPublishMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    // orientation[2] is roll, orientation[1] is pitch. Pitch is zero with
                    // the phone level and plus or minus ninety with it stood on end, which
                    // is exactly where roll about the long axis stops being measurable.
                    publish(
                        rollRadians = orientation[2],
                        pitchRadians = orientation[1],
                    )
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    // Fallback for a phone with no fused rotation vector: roll straight
                    // out of gravity, which is all the rotation vector is doing here.
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val roll = Math.atan2(x.toDouble(), z.toDouble()).toFloat()
                    val pitch = Math.atan2(
                        y.toDouble(),
                        Math.sqrt((x * x + z * z).toDouble()),
                    ).toFloat()
                    publish(roll, pitch)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun publish(rollRadians: Float, pitchRadians: Float) {
        val degrees = ((Math.toDegrees(rollRadians.toDouble()).toFloat() % 360f) + 360f) % 360f

        // Smoothed on the unit circle, so 359 and 1 average to 0 rather than to 180.
        val radians = Math.toRadians(degrees.toDouble())
        val sin = Math.sin(radians)
        val cos = Math.cos(radians)
        if (!hasSmoothed) {
            smoothedSin = sin
            smoothedCos = cos
            hasSmoothed = true
        } else {
            smoothedSin += (sin - smoothedSin) * SMOOTHING
            smoothedCos += (cos - smoothedCos) * SMOOTHING
        }

        val now = System.currentTimeMillis()
        if (now - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = now

        val smoothed = Math.toDegrees(Math.atan2(smoothedSin, smoothedCos)).toFloat()
        // Stood on end, gravity lies along the long axis and roll about it is meaningless.
        val pitchDegrees = Math.abs(Math.toDegrees(pitchRadians.toDouble()))
        _roll.value = Roll(
            degrees = ((smoothed % 360f) + 360f) % 360f,
            available = true,
            degenerate = pitchDegrees > MAX_PITCH_DEGREES,
        )
    }

    fun start() {
        val sensorManager = manager ?: return
        if (rotationVector != null) {
            sensorManager.registerListener(
                listener,
                rotationVector,
                SensorManager.SENSOR_DELAY_GAME,
            )
            return
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        manager?.unregisterListener(listener)
        hasSmoothed = false
    }

    val available: Boolean get() = rotationVector != null || accelerometer != null

    private companion object {
        const val SMOOTHING = 0.25
        const val PUBLISH_INTERVAL_MS = 40L

        /**
         * How far from level the long axis may tilt before roll stops meaning much.
         *
         * Zero pitch is level and ninety is stood on end. Sixty leaves a comfortable
         * working range while cutting off well short of where the number starts to spin.
         */
        const val MAX_PITCH_DEGREES = 60.0
    }
}
