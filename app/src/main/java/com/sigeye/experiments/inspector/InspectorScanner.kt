package com.sigeye.experiments.inspector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground-only scanner for the Inspector screen.
 *
 * Unlike the Train Spotter this keeps no service and no history: it exists while you are
 * looking at it. It also keeps the full advertisement rather than just the address, which
 * is the entire difference between counting devices and identifying them.
 *
 * Devices are restarted on a cycle for the same reason the Train Spotter does it - the
 * controller duplicate-filter table fills and starves the scan.
 */
class InspectorScanner(context: Context) {

    private val appContext = context.applicationContext
    private val ignoreList = IgnoreList.get(appContext)

    private val devices = LinkedHashMap<String, SeenDevice>()

    private val _state = MutableStateFlow(InspectorState())
    val state: StateFlow<InspectorState> = _state

    private var scanning = false
    private var startedAtMs = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null) record(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { record(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(TAG, "Inspector scan failed: " + errorCode)
            _state.value = _state.value.copy(
                running = false,
                error = "Scan failed (code " + errorCode + "). Toggle Bluetooth off and on.",
            )
        }
    }

    @Synchronized
    private fun record(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        if (ignoreList.isIgnored(address)) return

        val now = System.currentTimeMillis()
        val record = result.scanRecord

        var companyId: Int? = null
        var manufacturerData: ByteArray? = null
        val mfg = record?.manufacturerSpecificData
        if (mfg != null && mfg.size() > 0) {
            companyId = mfg.keyAt(0)
            manufacturerData = mfg.valueAt(0)
        }

        val uuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val serviceData = record?.serviceData
            ?.mapKeys { it.key.uuid.toString() }
            ?.mapValues { it.value ?: ByteArray(0) }
            .orEmpty()

        val existing = devices[address]
        devices[address] = SeenDevice(
            address = address,
            name = record?.deviceName ?: existing?.name,
            rssi = result.rssi,
            bestRssi = maxOf(result.rssi, existing?.bestRssi ?: Int.MIN_VALUE),
            companyId = companyId ?: existing?.companyId,
            manufacturerData = manufacturerData ?: existing?.manufacturerData,
            serviceUuids = uuids.ifEmpty { existing?.serviceUuids.orEmpty() },
            serviceData = serviceData.ifEmpty { existing?.serviceData.orEmpty() },
            txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE } ?: existing?.txPower,
            firstSeenMs = existing?.firstSeenMs ?: now,
            lastSeenMs = now,
            sightings = (existing?.sightings ?: 0) + 1,
        )

        publish(now)
    }

    private fun publish(now: Long) {
        val all = devices.values.toList()
        _state.value = _state.value.copy(
            running = scanning,
            devices = all,
            axonPresent = all.any { it.isAxon },
            error = null,
        )
    }

    @SuppressLint("MissingPermission") // gated by Permissions.canScan
    fun start(): Boolean {
        if (!Permissions.canScan(appContext)) {
            _state.value = _state.value.copy(
                error = "Missing Bluetooth or location permission.",
            )
            return false
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(error = "Bluetooth is off.")
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: return false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        return try {
            scanner.startScan(null, settings, callback)
            scanning = true
            startedAtMs = System.currentTimeMillis()
            _state.value = _state.value.copy(running = true, error = null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Inspector startScan threw", e)
            _state.value = _state.value.copy(
                error = "Could not start scanning: " + e.javaClass.simpleName,
            )
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        _state.value = _state.value.copy(running = false)
    }

    /** Restart if the cycle has elapsed, to flush the controller duplicate filter. */
    fun maintain(cycleMillis: Long) {
        if (!scanning) return
        if (System.currentTimeMillis() - startedAtMs < cycleMillis) return
        stop()
        start()
    }

    @Synchronized
    fun forget(address: String) {
        devices.remove(address)
        publish(System.currentTimeMillis())
    }

    @Synchronized
    fun clear() {
        devices.clear()
        publish(System.currentTimeMillis())
    }

    private companion object {
        const val TAG = "SigEye/Inspector"
    }
}

data class InspectorState(
    val running: Boolean = false,
    val devices: List<SeenDevice> = emptyList(),
    val axonPresent: Boolean = false,
    val error: String? = null,
)

/** Drops devices not heard from recently, so the list reflects what is here now. */
fun List<SeenDevice>.freshWithin(millis: Long, now: Long = System.currentTimeMillis()) =
    filter { now - it.lastSeenMs <= millis }

fun List<SeenDevice>.sortedBy(mode: SortMode): List<SeenDevice> = when (mode) {
    SortMode.STRONGEST -> sortedByDescending { it.rssi }
    SortMode.NEWEST -> sortedByDescending { it.firstSeenMs }
    SortMode.CHATTIEST -> sortedByDescending { it.sightings }
    SortMode.NAME -> sortedBy { it.displayName.lowercase() }
}

fun ByteArray.toHex(limit: Int = 24): String {
    val shown = take(limit).joinToString(" ") { String.format("%02X", it) }
    return if (size > limit) shown + " ..." else shown
}
