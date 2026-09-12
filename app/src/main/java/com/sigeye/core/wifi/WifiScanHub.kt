package com.sigeye.core.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One access point, as Android reports it. */
data class AccessPoint(
    val bssid: String,
    val ssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    val seenAtMs: Long,
) {
    /** Hidden networks return an empty name, which is itself worth showing. */
    val hidden: Boolean get() = ssid.isNullOrBlank()

    val band: String
        get() = when {
            frequencyMhz < 2500 -> "2.4 GHz"
            frequencyMhz < 5900 -> "5 GHz"
            else -> "6 GHz"
        }

    /**
     * Channel number from centre frequency.
     *
     * The 2.4 GHz band is 5 MHz a channel from 2412, with channel 14 sitting out on its
     * own; 5 and 6 GHz are a flat arithmetic step.
     */
    val channel: Int
        get() = when {
            frequencyMhz == 2484 -> 14
            frequencyMhz in 2412..2472 -> (frequencyMhz - 2412) / 5 + 1
            frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
            frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
            else -> 0
        }

    val open: Boolean
        get() = !capabilities.contains("WPA") && !capabilities.contains("WEP") &&
            !capabilities.contains("RSN")

    val security: String
        get() = when {
            capabilities.contains("SAE") -> "WPA3"
            capabilities.contains("RSN") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            else -> "Open"
        }
}

data class WifiScanState(
    val results: List<AccessPoint> = emptyList(),
    val scanning: Boolean = false,
    val scans: Int = 0,
    val lastScanAtMs: Long = 0,
    val throttled: Boolean = false,
    val error: String? = null,
)

/**
 * Wi-Fi access points, on a repeating scan.
 *
 * Deliberately shaped like [com.sigeye.core.ble.BleScanHub] but much less capable, because
 * Wi-Fi scanning on Android is much less capable. Two things are worth knowing.
 *
 * It returns access points only. Probe requests - the frames a phone sends out naming
 * networks it has joined before - are client traffic, and capturing those needs monitor
 * mode, which needs a chipset, a driver and root. No app can do it.
 *
 * And Android 9 onwards caps foreground scans at four in two minutes. `startScan` returns
 * false when the cap bites, which is the only signal there is, so a refusal is reported as
 * throttling rather than as an error. The cap can be turned off in developer options,
 * which is the difference between a coarse picture and a usable one.
 */
object WifiScanHub {

    private const val MIN_INTERVAL_MS = 8_000L

    private var manager: WifiManager? = null
    private var receiver: BroadcastReceiver? = null
    private val claims = mutableSetOf<String>()

    private val _state = MutableStateFlow(WifiScanState())
    val state: StateFlow<WifiScanState> = _state

    private val known = LinkedHashMap<String, AccessPoint>()
    private var lastRequestMs = 0L

    fun init(context: Context) {
        if (manager != null) return
        manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager
    }

    @Synchronized
    fun acquire(context: Context, tag: String) {
        init(context)
        val wasEmpty = claims.isEmpty()
        claims.add(tag)
        if (wasEmpty) register(context.applicationContext)
    }

    @Synchronized
    fun release(context: Context, tag: String) {
        claims.remove(tag)
        if (claims.isEmpty()) unregister(context.applicationContext)
    }

    private fun register(context: Context) {
        if (receiver != null) return
        val handler = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = harvest()
        }
        receiver = handler
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(handler, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(handler, filter)
            }
        }
        _state.value = _state.value.copy(scanning = true)
        // Whatever the system already has, before asking for anything new.
        harvest()
    }

    private fun unregister(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        _state.value = _state.value.copy(scanning = false)
    }

    /**
     * Asks for a fresh scan, if enough time has passed.
     *
     * Rate-limited on this side as well as Android's, because hammering startScan is the
     * fastest way to be throttled and there is nothing to gain from it.
     */
    @SuppressLint("MissingPermission")
    fun requestScan(): Boolean {
        val wifi = manager ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRequestMs < MIN_INTERVAL_MS) return false
        lastRequestMs = now

        @Suppress("DEPRECATION")
        val started = runCatching { wifi.startScan() }.getOrDefault(false)
        _state.value = _state.value.copy(
            throttled = !started,
            error = if (!started) {
                "Android refused the scan. That is almost always scan throttling - four " +
                    "scans every two minutes unless it is turned off in developer options."
            } else {
                null
            },
        )
        return started
    }

    @SuppressLint("MissingPermission")
    private fun harvest() {
        val wifi = manager ?: return
        val now = System.currentTimeMillis()
        val results: List<ScanResult> = runCatching { wifi.scanResults }.getOrNull().orEmpty()

        results.forEach { result ->
            val bssid = result.BSSID ?: return@forEach
            known[bssid.uppercase()] = AccessPoint(
                bssid = bssid.uppercase(),
                ssid = ssidOf(result),
                rssi = result.level,
                frequencyMhz = result.frequency,
                capabilities = result.capabilities.orEmpty(),
                seenAtMs = now,
            )
        }

        _state.value = _state.value.copy(
            results = known.values.sortedByDescending { it.rssi },
            scans = _state.value.scans + 1,
            lastScanAtMs = now,
            throttled = false,
        )
    }

    @Suppress("DEPRECATION")
    private fun ssidOf(result: ScanResult): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { result.wifiSsid?.toString()?.trim('"') }.getOrNull()
                ?: result.SSID
        } else {
            result.SSID
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        known.clear()
        _state.value = _state.value.copy(results = emptyList(), scans = 0)
    }
}
