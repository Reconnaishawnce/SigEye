package com.blepulse.scan

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blepulse.R
import com.blepulse.core.Bin
import com.blepulse.core.PulseAggregator
import com.blepulse.core.PulseConfig
import com.blepulse.core.PulseState
import com.blepulse.core.SettingsStore
import com.blepulse.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that keeps an unfiltered BLE scan running with the screen off
 * and turns the result into per-bin counts of newly seen addresses.
 *
 * Threading: scan callbacks land on a binder thread and only ever enqueue. The
 * aggregator is touched exclusively by the loop coroutine, so it needs no locking.
 */
class ScanService : Service() {

    private data class Advert(val address: String, val rssi: Int, val atMs: Long)

    private val queue = ConcurrentLinkedQueue<Advert>()
    private val lastResultMs = AtomicLong(0)

    private lateinit var aggregator: PulseAggregator
    private lateinit var csv: CsvLogger
    private lateinit var settings: SettingsStore
    private var config: PulseConfig = PulseConfig.DEFAULT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var scanStartedAtMs = 0L
    private var nextCloseAtMs = 0L
    private var lastAlertMs = 0L
    private var restarts = 0

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    scanning = false
                    PulseState.setError("Bluetooth is off. Turn it on to keep counting.")
                    updateOngoingNotification()
                }

                BluetoothAdapter.STATE_ON -> {
                    PulseState.setError(null)
                    startScan()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        config = settings.load()
        aggregator = PulseAggregator(config)
        csv = CsvLogger(this)
        createChannels()
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_LABEL -> {
                aggregator.markLabel("TRAIN")
                Log.i(TAG, "Manual TRAIN label queued for next bin")
                return START_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                config = settings.load()
                aggregator.reconfigure(config)
                PulseState.update { it.copy(config = config) }
                return START_STICKY
            }
        }

        if (!Permissions.canScan(this)) {
            PulseState.setError("Missing Bluetooth or location permission.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ONGOING,
                buildOngoingNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            PulseState.setError("Could not start the background scan.")
            stopSelf()
            return START_NOT_STICKY
        }

        config = settings.load()
        aggregator.reconfigure(config)
        aggregator.reset()
        queue.clear()
        lastAlertMs = 0L
        restarts = 0
        nextCloseAtMs = System.currentTimeMillis() + config.binMillis

        PulseState.update {
            it.copy(
                running = true,
                bins = emptyList(),
                currentCount = 0,
                activeUnique = 0,
                baseline = 0.0,
                binsUntilWarm = config.warmupBins,
                totalAdvertisements = 0,
                scanRestarts = 0,
                config = config,
                error = null,
                csvPath = csv.currentFile?.absolutePath,
            )
        }

        startScan()
        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        loopJob?.cancel()
        scope.cancel()
        csv.close()
        runCatching { unregisterReceiver(bluetoothReceiver) }
        PulseState.update { it.copy(running = false, currentCount = 0) }
        super.onDestroy()
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    // ---------------------------------------------------------------- scanning

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val now = System.currentTimeMillis()
            lastResultMs.set(now)
            queue.add(Advert(device.address, result.rssi, now))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED ->
                    "A scan was already running."

                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Android refused the scan. Toggle Bluetooth off and on."

                SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "This phone does not support the scan mode BLEPulse needs."

                SCAN_FAILED_INTERNAL_ERROR ->
                    "Bluetooth stack error. Toggle Bluetooth off and on."

                else -> "Scan failed (code " + errorCode + ")."
            }
            Log.e(TAG, "onScanFailed: " + errorCode + " - " + reason)
            PulseState.setError(reason)
        }
    }

    @SuppressLint("MissingPermission") // guarded by Permissions.canScan above every call
    private fun startScan() {
        if (!Permissions.canScan(this)) {
            PulseState.setError("Missing Bluetooth or location permission.")
            return
        }
        val bt = adapter()
        if (bt == null) {
            PulseState.setError("No Bluetooth adapter on this device.")
            return
        }
        if (!bt.isEnabled) {
            PulseState.setError("Bluetooth is off. Turn it on to start counting.")
            return
        }

        val s = bt.bluetoothLeScanner
        scanner = s
        if (s == null) {
            PulseState.setError("Bluetooth LE scanner unavailable.")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            // Null filters on purpose: we want every advertiser in range, not a device class.
            s.startScan(null, scanSettings, scanCallback)
            scanning = true
            scanStartedAtMs = System.currentTimeMillis()
            lastResultMs.set(System.currentTimeMillis())
            PulseState.setError(null)
            Log.i(TAG, "Scan started, restarts so far: " + restarts)
        } catch (e: Exception) {
            scanning = false
            Log.e(TAG, "startScan threw", e)
            PulseState.setError("Could not start scanning: " + e.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        runCatching { scanner?.stopScan(scanCallback) }
            .onFailure { Log.w(TAG, "stopScan threw", it) }
        scanning = false
    }

    private fun restartScan(why: String) {
        Log.i(TAG, "Restarting scan: " + why)
        restarts++
        stopScan()
        scope.launch {
            delay(1_200)
            startScan()
            PulseState.update { it.copy(scanRestarts = restarts) }
        }
    }

    // -------------------------------------------------------------------- loop

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                drainQueue()

                if (now >= nextCloseAtMs) {
                    nextCloseAtMs = now + config.binMillis
                    val bin = aggregator.closeBin(now)
                    if (bin != null) onBinClosed(bin)
                }

                runWatchdog(now)
                publish()
            }
        }
    }

    private fun drainQueue() {
        var advert = queue.poll()
        while (advert != null) {
            aggregator.observe(advert.address, advert.rssi, advert.atMs)
            advert = queue.poll()
        }
    }

    private fun onBinClosed(bin: Bin) {
        csv.append(bin)
        Log.d(
            TAG,
            "bin new=" + bin.newCount +
                " active=" + bin.activeUnique +
                " baseline=" + format1(bin.baseline) +
                " spike=" + bin.spike,
        )
        if (bin.spike) maybeAlert(bin)
        updateOngoingNotification()
    }

    private fun maybeAlert(bin: Bin) {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < config.alertCooldownMillis) return
        lastAlertMs = now
        PulseState.update { it.copy(lastAlertMs = now) }
        notifySpike(bin)
    }

    /**
     * Two failure modes we watch for: the Android 12+ stack quietly stopping delivery,
     * and the long-running-scan cutoff some OEM builds enforce around 30 minutes.
     */
    private fun runWatchdog(now: Long) {
        val bt = adapter()
        if (bt == null || !bt.isEnabled) return

        if (!scanning) {
            if (now - scanStartedAtMs > SILENCE_RESTART_MS) restartScan("scanner not running")
            return
        }
        if (now - lastResultMs.get() > SILENCE_RESTART_MS) {
            restartScan("no results for " + (SILENCE_RESTART_MS / 1000) + "s")
            return
        }
        if (now - scanStartedAtMs > PERIODIC_RESTART_MS) {
            restartScan("periodic refresh")
        }
    }

    private fun publish() {
        PulseState.update {
            it.copy(
                running = true,
                bins = aggregator.history(),
                currentCount = aggregator.currentCount(),
                activeUnique = aggregator.activeUnique(),
                baseline = aggregator.computeBaseline(),
                binsUntilWarm = aggregator.binsUntilWarm(),
                totalAdvertisements = aggregator.totalAdvertisements,
                lastResultMs = lastResultMs.get(),
                scanRestarts = restarts,
                csvPath = csv.currentFile?.absolutePath,
            )
        }
    }

    private fun stopEverything() {
        stopScan()
        loopJob?.cancel()
        csv.close()
        PulseState.update { it.copy(running = false, currentCount = 0) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ----------------------------------------------------------- notifications

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Scanning",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while BLEPulse is counting devices." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Train alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Fires when nearby Bluetooth devices spike."
                enableVibration(true)
            },
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildOngoingNotification(): Notification {
        val state = PulseState.state.value
        val text = when {
            state.error != null -> state.error!!
            state.binsUntilWarm > 0 -> "Warming up, " + state.binsUntilWarm + " bins to go"
            else -> state.currentCount.toString() + " new now, usual " + format1(state.baseline)
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle("BLEPulse is listening")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateOngoingNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ONGOING, buildOngoingNotification())
        }
    }

    private fun notifySpike(bin: Bin) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle("Train passing?")
            .setContentText(
                bin.newCount.toString() + " new devices in " + config.binSeconds +
                    "s, usual is " + format1(bin.baseline),
            )
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ALERT, notification)
        }
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val TAG = "BLEPulse"
        private const val CHANNEL_ONGOING = "scan"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2

        private const val TICK_MS = 500L
        private const val SILENCE_RESTART_MS = 90_000L
        private const val PERIODIC_RESTART_MS = 25 * 60_000L

        const val ACTION_START = "com.blepulse.START"
        const val ACTION_STOP = "com.blepulse.STOP"
        const val ACTION_LABEL = "com.blepulse.LABEL"
        const val ACTION_RELOAD_CONFIG = "com.blepulse.RELOAD_CONFIG"

        fun start(context: Context) {
            val intent = Intent(context, ScanService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))
        }

        fun label(context: Context) {
            context.startService(Intent(context, ScanService::class.java).setAction(ACTION_LABEL))
        }

        fun reloadConfig(context: Context) {
            context.startService(
                Intent(context, ScanService::class.java).setAction(ACTION_RELOAD_CONFIG),
            )
        }
    }
}
