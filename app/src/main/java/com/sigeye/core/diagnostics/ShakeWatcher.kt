package com.sigeye.core.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Listens for a shake while the app is on screen, and asks for a report when it hears one.
 *
 * Only while the app is in the foreground, so a phone shaken in a bag never opens anything,
 * and the accelerometer is not left running for nothing.
 */
class ShakeWatcher(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = ShakeDetector()

    fun start() {
        if (!Diagnostics.shakeToReport) return
        accelerometer?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        if (detector.reading(x, y, z, event.timestamp / 1_000_000L)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
