package com.sigeye.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How much to trust the compass right now. */
enum class CompassQuality(val label: String, val advice: String?) {
    UNKNOWN("Checking compass", null),
    UNRELIABLE(
        "Compass unreliable",
        "Wave the phone in a figure of eight a few times to calibrate it. Near metal or " +
            "a magnet it may not settle at all.",
    ),
    LOW(
        "Compass poor",
        "Wave the phone in a figure of eight to calibrate. Move away from anything metal.",
    ),
    MEDIUM("Compass fair", null),
    HIGH("Compass good", null),
    ABSENT(
        "No compass",
        "This phone has no magnetometer, so heading cannot be measured.",
    ),
    ;

    /** Below medium, a sweep will be plotted against fiction. */
    val isUsable: Boolean get() = this == MEDIUM || this == HIGH
}

data class Heading(
    /** Compass degrees, 0 = magnetic north. */
    val degrees: Float,
    val quality: CompassQuality,
)

/**
 * Compass heading, fused from the rotation vector where the phone has one.
 *
 * Reports calibration quality alongside the angle, and the screens refuse to record while
 * it is poor. An uncalibrated magnetometer does not fail loudly - it returns a confident,
 * wrong number, and a polar plot drawn against those is worse than no plot at all.
 */
class HeadingSensor(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _heading = MutableStateFlow(
        Heading(0f, if (hasAnySource()) CompassQuality.UNKNOWN else CompassQuality.ABSENT),
    )
    val heading: StateFlow<Heading> = _heading

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    /** Smoothed so the needle does not jitter, in a way that survives the 0/360 wrap. */
    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private var hasSmoothed = false

    private var quality = CompassQuality.UNKNOWN
    private var lastPublishMs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    publish(Math.toDegrees(orientation[0].toDouble()).toFloat())
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    gravity = event.values.clone()
                    fuse()
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    geomagnetic = event.values.clone()
                    fuse()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD &&
                sensor?.type != Sensor.TYPE_ROTATION_VECTOR
            ) {
                return
            }
            quality = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassQuality.HIGH
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassQuality.MEDIUM
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassQuality.LOW
                SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassQuality.UNRELIABLE
                else -> CompassQuality.UNKNOWN
            }
            _heading.value = _heading.value.copy(quality = quality)
        }
    }

    private fun fuse() {
        // Only needed when there is no fused rotation vector to lean on.
        if (rotationVector != null) return
        val g = gravity ?: return
        val m = geomagnetic ?: return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, g, m)) return
        SensorManager.getOrientation(rotationMatrix, orientation)
        publish(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }

    private fun publish(rawDegrees: Float) {
        val normalised = ((rawDegrees % 360f) + 360f) % 360f
        val radians = Math.toRadians(normalised.toDouble())

        // Average the unit vector rather than the angle: averaging 359 and 1 numerically
        // gives 180, which points the needle at exactly the wrong place.
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

        // Publish at about 20 Hz rather than at sensor rate. The rotation vector fires
        // roughly fifty times a second, and every update recomposes whatever is reading
        // this flow - which is an entire experiment screen. Smooth needle, idle CPU.
        val now = System.currentTimeMillis()
        if (now - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = now

        val smoothed = Math.toDegrees(Math.atan2(smoothedSin, smoothedCos)).toFloat()
        _heading.value = Heading(((smoothed % 360f) + 360f) % 360f, quality)
    }

    fun start() {
        val sensorManager = manager ?: return
        if (rotationVector != null) {
            sensorManager.registerListener(
                listener,
                rotationVector,
                SensorManager.SENSOR_DELAY_UI,
            )
            return
        }
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        manager?.unregisterListener(listener)
        hasSmoothed = false
    }

    private fun hasAnySource(): Boolean =
        rotationVector != null || (accelerometer != null && magnetometer != null)

    private companion object {
        /** Low pass factor. Enough to steady the needle, quick enough to follow a turn. */
        const val SMOOTHING = 0.18

        /** 20 Hz is far smoother than the eye needs and a tenth of the recompositions. */
        const val PUBLISH_INTERVAL_MS = 50L
    }
}
