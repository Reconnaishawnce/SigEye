package com.sigeye.core.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.sigeye.core.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the phone thinks it is, and how sure it is about that. */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    /** Radius in metres the phone claims the real position is inside. */
    val accuracyM: Float,
    val atMs: Long,
) {
    /** Enough to say which pole a camera is on, rather than which street. */
    val goodEnoughToMap: Boolean get() = accuracyM in 0f..40f

    fun pretty(): String = "%.5f, %.5f".format(latitude, longitude)
}

/**
 * The phone's position, for the few experiments that need to write one down.
 *
 * Kept deliberately small. Most of this app is about what is in earshot and does not care
 * where that is, so there is no background location, no history and nothing persisted here.
 * A screen asks while it is open and stops when it closes.
 */
class Where(context: Context) {

    private val app = context.applicationContext

    private val manager: LocationManager? = runCatching {
        app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull()

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    private var listener: LocationListener? = null

    val available: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    @SuppressLint("MissingPermission") // guarded by Permissions.canScan
    fun start() {
        if (listener != null) return
        if (!Permissions.canScan(app)) return
        val source = manager ?: return

        val callback = LocationListener { location -> _fix.value = location.toFix() }
        listener = callback

        runCatching {
            // Last known first, so a screen has something to show before the radio has
            // agreed with the sky. It may be minutes old, which is why the timestamp
            // travels with it rather than being assumed to be now.
            source.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                _fix.value = it.toFix()
            }
            source.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                callback,
                Looper.getMainLooper(),
            )
        }.onFailure { listener = null }
    }

    fun stop() {
        val callback = listener ?: return
        listener = null
        runCatching { manager?.removeUpdates(callback) }
    }

    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        atMs = time,
    )

    private companion object {
        /** Often enough to keep up with a car, rarely enough not to cook the battery. */
        const val MIN_TIME_MS = 2_000L
        const val MIN_DISTANCE_M = 5f
    }
}
