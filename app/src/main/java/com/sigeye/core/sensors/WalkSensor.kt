package com.sigeye.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone is being carried somewhere, and for how long it has not been. */
data class Walk(
    val moving: Boolean,
    /** How long since it last moved, or null when nothing here can tell. */
    val stillForMs: Long?,
    val available: Boolean,
)

/**
 * Deciding whether somebody is walking, without asking for anything.
 *
 * The step counter would be the obvious answer and it is the wrong one here: on Android 10
 * and later it needs ACTIVITY_RECOGNITION, and asking a permission the moment somebody
 * opens a following experiment is a bad trade for a fact this can get for free. The
 * accelerometer needs no permission at all, works indoors where GPS does not, and only has
 * to answer a coarse question.
 *
 * The signal is how much the total acceleration varies. Gravity is always about 9.8 m/s²,
 * so a phone lying on a table or held still reads a flat magnitude and almost no spread;
 * carried in a hand or a pocket it swings several metres per second squared with every
 * step. Telling those two apart does not need anything cleverer than a standard deviation.
 *
 * This cannot tell walking from a train, and does not try. What it is for is the case where
 * somebody has stopped moving and the elimination has therefore stopped working, which is
 * the one situation where a still count means the method has halted rather than the app.
 */
class WalkSensor(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val recent = ArrayDeque<Float>()
    private var lastMovedAtMs = System.currentTimeMillis()

    private val _walk = MutableStateFlow(
        Walk(moving = true, stillForMs = null, available = accelerometer != null),
    )
    val walk: StateFlow<Walk> = _walk

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            val z = event.values.getOrNull(2) ?: return

            recent.addLast(sqrt(x * x + y * y + z * z))
            while (recent.size > WINDOW) recent.removeFirst()
            if (recent.size < WINDOW) return

            val now = System.currentTimeMillis()
            val moving = Walking.moving(recent.toList())
            if (moving) lastMovedAtMs = now
            _walk.value = Walk(
                moving = moving,
                stillForMs = (now - lastMovedAtMs).coerceAtLeast(0L),
                available = true,
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val sensor = accelerometer ?: return
        // The slowest rate Android offers. This answers "is somebody walking", which does
        // not need fifty readings a second and should not cost a battery to know.
        runCatching {
            manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener(listener) }
        recent.clear()
    }

    private companion object {
        /** Readings before a verdict. At the normal rate this is a couple of seconds. */
        const val WINDOW = 24
    }
}

/** The decision, separated out so it can be tested without a phone. */
object Walking {

    /**
     * Spread in metres per second squared above which somebody is carrying the phone.
     *
     * A phone on a table reads gravity and sensor noise, well under a tenth. A phone in a
     * pocket at walking pace swings two to five. Half of one leaves room for a hand held
     * carefully still and for a bus, and still separates the two cases by a wide margin.
     */
    const val MOVING_SD = 0.5

    fun spread(magnitudes: List<Float>): Double {
        if (magnitudes.size < 2) return 0.0
        val mean = magnitudes.map { it.toDouble() }.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        return sqrt(variance)
    }

    fun moving(magnitudes: List<Float>): Boolean = spread(magnitudes) >= MOVING_SD
}
