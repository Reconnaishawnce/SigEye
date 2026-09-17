package com.sigeye.core.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One distance, measured rather than inferred.
 *
 * Everything else in this app that talks about distance inverts a path loss model with an
 * exponent somebody picked. This is time of flight: the radios exchange timestamped frames
 * and the distance falls out of how long light took. It is the only number in SigEye that
 * is a length rather than an argument about a length.
 */
data class RangeFix(
    val bssid: String,
    val ssid: String?,
    /** Metres. */
    val distanceM: Double,
    /** The radio's own estimate of how wrong that is, in metres. */
    val spreadM: Double,
    /** Level during the exchange, for putting the two methods side by side. */
    val rssi: Int,
    val successful: Int,
    val attempted: Int,
    val atMs: Long,
) {
    /** How many of the bursts came back. Under half and the number is not worth much. */
    val completion: Double
        get() = if (attempted <= 0) 0.0 else successful.toDouble() / attempted

    val trustworthy: Boolean
        get() = completion >= MIN_COMPLETION && spreadM <= MAX_SPREAD_M

    fun describe(): String = String.format(
        Locale.US,
        "%.2f m ± %.2f, %d of %d bursts",
        distanceM,
        spreadM,
        successful,
        attempted,
    )

    companion object {
        /** Below this share of successful bursts the reading is mostly a guess. */
        const val MIN_COMPLETION = 0.5

        /**
         * Above this much spread the reading is not a length.
         *
         * Indoors a clean 802.11mc exchange lands within a metre or so. Several metres of
         * spread means the direct path is blocked and the radio is timing a reflection.
         */
        const val MAX_SPREAD_M = 4.0
    }
}

/** What the phone can do about ranging at all. */
sealed interface RttSupport {
    data object Ready : RttSupport

    /** The chipset or the build has no 802.11mc. Nothing to be done about it. */
    data object NotSupported : RttSupport

    /** Supported, but switched off in system settings alongside Wi-Fi scanning. */
    data object Disabled : RttSupport

    /** Android 9 introduced the API. Nothing earlier can range. */
    data object TooOld : RttSupport
}

/**
 * Round-trip time ranging over 802.11mc.
 *
 * The one thing in this app that measures a distance instead of arguing about one, and the
 * reason it is worth having is not the number by itself. Doppler Walk exists to measure the
 * path loss exponent by pacing a distance out and counting steps. This measures the same
 * distance by timing light, which means the exponent can be fitted against something that
 * was not also estimated.
 *
 * Follows the approach in Spectre's WifiRttRanger (GPL-3.0, thomasbuilds), which is where
 * the useful details came from: filter on the responder flag before asking, respect the
 * peer cap, and never let two ranging bursts overlap. Rewritten here to report attempt
 * counts and spread rather than caching a bare distance, because this app has to be able to
 * say how good a reading was and not only what it said.
 *
 * Ranging is an active exchange. The phone transmits, which is unlike everything else in
 * SigEye, so it only ever happens when somebody presses the button.
 */
class RttRanger(context: Context) {

    private val app = context.applicationContext

    private val manager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                app.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
            }.getOrNull()
        } else {
            null
        }

    private val wifi: WifiManager? = runCatching {
        app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }.getOrNull()

    // One burst at a time. The radio serializes them anyway and overlapping requests come
    // back as failures that look like a phone with no line of sight.
    private val ranging = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sigeye-rtt").apply { isDaemon = true }
    }

    private val _fixes = MutableStateFlow<List<RangeFix>>(emptyList())
    val fixes: StateFlow<List<RangeFix>> = _fixes

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _trouble = MutableStateFlow<String?>(null)
    val trouble: StateFlow<String?> = _trouble

    fun support(): RttSupport = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> RttSupport.TooOld
        !app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) ->
            RttSupport.NotSupported

        manager == null -> RttSupport.NotSupported
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isAvailable() -> RttSupport.Disabled
        else -> RttSupport.Ready
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun isAvailable(): Boolean =
        runCatching { manager?.isAvailable == true }.getOrDefault(false)

    /**
     * Access points that answer ranging requests, from the last scan.
     *
     * Most do not. The flag is set by the access point and there is no way to talk one into
     * it, so a screen has to be able to say "none of these can do this" without it reading
     * as a broken phone.
     */
    @SuppressLint("MissingPermission")
    fun responders(): List<Responder> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val results = runCatching { wifi?.scanResults }.getOrNull().orEmpty()
        return results
            .filter { it.is80211mcResponder }
            .map {
                Responder(
                    bssid = it.BSSID.orEmpty().uppercase(Locale.US),
                    ssid = it.SSID?.takeIf { name -> name.isNotBlank() },
                    rssi = it.level,
                    frequencyMhz = it.frequency,
                )
            }
            .filter { it.bssid.isNotBlank() }
            .sortedByDescending { it.rssi }
    }

    /** An access point that is willing to be ranged. */
    data class Responder(
        val bssid: String,
        val ssid: String?,
        val rssi: Int,
        val frequencyMhz: Int,
    )

    /**
     * Asks every willing access point how far away it is.
     *
     * @param onDone called with the readings, on the ranging thread.
     */
    @SuppressLint("MissingPermission")
    fun measure(onDone: (List<RangeFix>) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mgr = manager ?: return
        if (!ranging.compareAndSet(false, true)) return
        _busy.value = true
        _trouble.value = null

        val results = runCatching { wifi?.scanResults }.getOrNull().orEmpty()
            .filter { it.is80211mcResponder }
            // The radio will only range a handful at once, and asking for more than the
            // cap throws rather than truncating.
            .take(RangingRequest.getMaxPeers())

        if (results.isEmpty()) {
            finish("Nothing in range answers ranging requests.", emptyList(), onDone)
            return
        }

        val request = runCatching {
            RangingRequest.Builder().apply { results.forEach { addAccessPoint(it) } }.build()
        }.getOrElse {
            finish("The ranging request was refused: ${it.message}", emptyList(), onDone)
            return
        }

        val names = results.associate {
            it.BSSID.orEmpty().uppercase(Locale.US) to it.SSID?.takeIf { s -> s.isNotBlank() }
        }

        runCatching {
            mgr.startRanging(
                request,
                executor,
                object : RangingResultCallback() {
                    override fun onRangingResults(results: List<RangingResult>) {
                        val now = System.currentTimeMillis()
                        val fixes = results.mapNotNull { result ->
                            if (result.status != RangingResult.STATUS_SUCCESS) return@mapNotNull null
                            val mac = result.macAddress?.toString()?.uppercase(Locale.US)
                                ?: return@mapNotNull null
                            RangeFix(
                                bssid = mac,
                                ssid = names[mac],
                                // The radio reports millimetres, which is a precision it
                                // does not have. Kept as metres and shown to centimetres.
                                distanceM = result.distanceMm / 1000.0,
                                spreadM = result.distanceStdDevMm / 1000.0,
                                rssi = result.rssi,
                                successful = result.numSuccessfulMeasurements,
                                attempted = result.numAttemptedMeasurements,
                                atMs = now,
                            )
                        }
                        finish(
                            if (fixes.isEmpty()) {
                                "Every access point refused or timed out. Ranging needs a " +
                                    "clear path, and a wall is usually enough to stop it."
                            } else {
                                null
                            },
                            fixes,
                            onDone,
                        )
                    }

                    override fun onRangingFailure(code: Int) {
                        finish("The radio refused to range (code $code).", emptyList(), onDone)
                    }
                },
            )
        }.onFailure {
            finish("Could not start ranging: ${it.message}", emptyList(), onDone)
        }
    }

    private fun finish(problem: String?, fixes: List<RangeFix>, onDone: (List<RangeFix>) -> Unit) {
        _trouble.value = problem
        if (fixes.isNotEmpty()) _fixes.value = fixes
        _busy.value = false
        ranging.set(false)
        onDone(fixes)
    }

    fun clear() {
        _fixes.value = emptyList()
        _trouble.value = null
    }
}
