package com.sigeye.core.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sigeye.core.analysis.gnss.Constellation
import com.sigeye.core.analysis.gnss.Satellite
import com.sigeye.core.analysis.gnss.Sky
import com.sigeye.core.analysis.gnss.SkyView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Listening to the satellites.
 *
 * Everything else in SigEye measures a transmitter somebody in the room owns. These are
 * twenty thousand kilometres up, they arrive weaker than the thermal noise of the chip
 * receiving them, and a phone pulls them out of that noise by knowing exactly what code to
 * look for. It is the most impressive radio in the handset and the one nobody thinks about.
 *
 * Purely an observer. Nothing here requests a position fix or a location update: it reads
 * the status the receiver publishes about what it can hear, which means it never asks the
 * phone to go and find out where it is.
 */
class SkyWatcher(context: Context) {

    private val app = context.applicationContext

    private val manager: LocationManager? = runCatching {
        app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull()

    private val _sky = MutableStateFlow(SkyView(emptyList(), 0L))
    val sky: StateFlow<SkyView> = _sky

    /** True once the receiver has said anything at all, so a screen can tell waiting from empty. */
    private val _heard = MutableStateFlow(false)
    val heard: StateFlow<Boolean> = _heard

    private var callback: GnssStatus.Callback? = null

    val available: Boolean
        get() = manager?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true

    /** Whether the receiver is switched on at all, which is a separate question from permission. */
    val enabled: Boolean
        get() = runCatching {
            manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        val mgr = manager ?: return
        if (callback != null) return

        val listener = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                _heard.value = true
                _sky.value = SkyView(read(status), System.currentTimeMillis())
            }
        }
        callback = listener
        runCatching {
            mgr.registerGnssStatusCallback(listener, Handler(Looper.getMainLooper()))
        }.onFailure { callback = null }
    }

    fun stop() {
        val listener = callback ?: return
        runCatching { manager?.unregisterGnssStatusCallback(listener) }
        callback = null
    }

    private fun read(status: GnssStatus): List<Satellite> = (0 until status.satelliteCount)
        .map { index ->
            // Zero elevation and zero azimuth together mean the receiver has not worked out
            // the orbit yet, not that the satellite is on the horizon due north. Drawing
            // the second would put a row of satellites along the bottom of the plot that
            // are not there.
            val elevation = status.getElevationDegrees(index)
            val azimuth = status.getAzimuthDegrees(index)
            val placed = elevation != 0f || azimuth != 0f

            Satellite(
                constellation = constellationOf(status.getConstellationType(index)),
                id = status.getSvid(index),
                band = Sky.bandFor(
                    if (status.hasCarrierFrequencyHz(index)) {
                        status.getCarrierFrequencyHz(index).toDouble()
                    } else {
                        null
                    },
                ),
                cn0DbHz = status.getCn0DbHz(index),
                elevationDeg = elevation.takeIf { placed },
                azimuthDeg = azimuth.takeIf { placed },
                usedInFix = status.usedInFix(index),
                hasEphemeris = status.hasEphemerisData(index),
            )
        }
        // Something reporting nothing and not being used is a slot the receiver is holding
        // open rather than a satellite anybody is hearing.
        .filter { it.cn0DbHz > 0f || it.usedInFix }

    private fun constellationOf(type: Int): Constellation = when (type) {
        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
        GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
        GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
        else -> if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            type == GnssStatus.CONSTELLATION_IRNSS
        ) {
            Constellation.NAVIC
        } else {
            Constellation.UNKNOWN
        }
    }
}
