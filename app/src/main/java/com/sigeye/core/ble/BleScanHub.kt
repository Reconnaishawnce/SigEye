package com.sigeye.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.sigeye.core.IgnoreList
import com.sigeye.core.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place in SigEye that talks to the BLE radio.
 *
 * Every experiment subscribes here instead of starting its own scan. Three reasons:
 * the radio is a single shared resource and parallel scans multiply battery cost for no
 * extra data; the starvation workaround below only works if one owner controls the scan
 * lifecycle; and decoding each packet once is cheaper than once per consumer.
 *
 * Subscribers [acquire] while they need data and [release] when they do not. The scan runs
 * whenever at least one holds a claim, so an experiment screen can piggyback on a running
 * foreground service at no extra cost.
 *
 * **Starvation.** The BLE controller keeps a finite duplicate-filter table. In a busy area,
 * with nearby phones rotating their addresses, it fills within minutes and the chipset then
 * stops surfacing genuinely new advertisers - while a trickle still gets through, so a
 * silence-based watchdog never fires and the app looks alive while seeing nothing. The hub
 * therefore measures the delivery *rate*, learns what healthy looks like early in each
 * cycle, restarts when it collapses, and cycles unconditionally on a timer regardless.
 */
object BleScanHub {

    private const val TAG = "SigEye/Hub"

    private const val TICK_MS = 1_000L
    private const val RATE_SAMPLE_MS = 10_000L
    private const val REFERENCE_SAMPLES = 3
    private const val STARVED_FRACTION = 0.25
    private const val STARVED_FOR_MS = 40_000L
    private const val MIN_MEANINGFUL_RATE = 1.0
    private const val SILENCE_RESTART_MS = 90_000L
    private const val MIN_RESTART_GAP_MS = 30_000L
    private const val RETRY_GAP_MS = 30_000L

    /** Unconditional scan cycle. Overridable so Train Spotter's slider still bites. */
    @Volatile
    var cycleMillis: Long = 4 * 60_000L

    private val _adverts = MutableSharedFlow<Advert>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every advertisement that passes the mute list.
     *
     * Dropping the oldest on overflow is deliberate: a consumer that falls behind should
     * lose old packets rather than stall the radio callback thread.
     */
    val adverts: SharedFlow<Advert> = _adverts

    private val _health = MutableStateFlow(ScanHealth())
    val health: StateFlow<ScanHealth> = _health

    private lateinit var appContext: Context
    private lateinit var ignoreList: IgnoreList
    private var initialised = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val claims = mutableSetOf<String>()
    private var scanning = false
    private var scanStartedAtMs = 0L
    private var lastScanAttemptMs = 0L
    private var lastRestartAtMs = 0L
    private var lastResultMs = 0L
    private var restarts = 0

    private val sampleAds = AtomicInteger(0)
    private var sampleStartMs = 0L
    private var referenceRate = 0.0
    private var currentRate = 0.0
    private var rateSamples = 0
    private var starvedSinceMs = 0L

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    scanning = false
                    publishHealth("Bluetooth is off. Turn it on to keep listening.")
                }

                BluetoothAdapter.STATE_ON -> {
                    publishHealth(null)
                    if (claims.isNotEmpty()) startScan()
                }
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        ignoreList = IgnoreList.get(appContext)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        initialised = true
    }

    /** Claim the radio under [tag]. Idempotent per tag. */
    @Synchronized
    fun acquire(tag: String) {
        check(initialised) { "BleScanHub.init() must be called before acquire()" }
        val wasEmpty = claims.isEmpty()
        // A tag acquired twice without a release is a leaked claim, and the symptom is a
        // flat battery rather than a crash - the radio simply never goes off again. The
        // claims are a set, so the second acquire is harmless in itself; what is not
        // harmless is nobody noticing. Twenty screens acquire by string tag, so this is
        // the one place that can tell.
        if (tag in claims) {
            Log.w(TAG, "Claim '$tag' acquired twice without a release - a screen leaked it.")
        }
        claims.add(tag)
        publishHealth(_health.value.error)
        if (wasEmpty) {
            resetRateLearning(System.currentTimeMillis())
            startScan()
            startLoop()
        }
    }

    /** Drop a claim. The scan stops once the last one goes. */
    @Synchronized
    fun release(tag: String) {
        if (!claims.remove(tag)) return
        if (claims.isEmpty()) {
            loop?.cancel()
            loop = null
            stopScan()
        }
        publishHealth(_health.value.error)
    }

    @Synchronized
    fun isHeldBy(tag: String): Boolean = claims.contains(tag)

    // ------------------------------------------------------------- capture and replay

    @Volatile
    private var replaying = false

    private var replayJob: Job? = null

    /** Set while a capture is being recorded; called for every live advertisement. */
    @Volatile
    private var recorder: ((Advert) -> Unit)? = null

    val isReplaying: Boolean get() = replaying

    fun record(sink: ((Advert) -> Unit)?) {
        recorder = sink
    }

    /**
     * Plays a recorded capture onto the advertisement flow in its original timing.
     *
     * Every experiment reads this flow and none of them needs to know where it came from,
     * which is the whole point: a bug that happens on a train can be reproduced at a desk
     * without a single screen being aware it is looking at a recording.
     *
     * The timing is preserved rather than replayed as fast as possible, because half the
     * app measures gaps between packets. An advertising interval is a fingerprint, a burst
     * is a train, and a capture flushed through in one go would say every device in the
     * room advertises infinitely fast.
     */
    fun startReplay(adverts: List<Advert>, onFinished: () -> Unit = {}) {
        stopReplay()
        if (adverts.isEmpty()) return
        replaying = true
        val startedAt = System.currentTimeMillis()
        val firstAt = adverts.first().atMs
        replayJob = scope.launch {
            adverts.forEach { advert ->
                val due = startedAt + (advert.atMs - firstAt)
                val wait = due - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (!replaying) return@launch
                _adverts.tryEmit(advert.copy(atMs = System.currentTimeMillis()))
            }
            replaying = false
            onFinished()
        }
    }

    fun stopReplay() {
        replaying = false
        replayJob?.cancel()
        replayJob = null
    }

    // --------------------------------------------------------------- scanning

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // A replay must be the only thing on the flow. Letting the live radio through
            // at the same time would mix a recorded room with the one you are sitting in,
            // and every reading afterwards would be of neither.
            if (replaying) return
            val scanResult = result ?: return
            val address = scanResult.device?.address ?: return
            if (ignoreList.isIgnored(address)) return

            val now = System.currentTimeMillis()
            lastResultMs = now
            sampleAds.incrementAndGet()

            val advert = Advert.from(scanResult, now) ?: return
            _adverts.tryEmit(advert)
            recorder?.invoke(advert)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "A scan was already running."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Android refused the scan. Toggle Bluetooth off and on."
                SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "This phone does not support the scan mode SigEye needs."
                SCAN_FAILED_INTERNAL_ERROR ->
                    "Bluetooth stack error. Toggle Bluetooth off and on."
                else -> "Scan failed (code $errorCode)."
            }
            Log.e(TAG, "onScanFailed $errorCode - $reason")
            publishHealth(reason)
        }
    }

    @SuppressLint("MissingPermission") // guarded by Permissions.canScan
    private fun startScan() {
        lastScanAttemptMs = System.currentTimeMillis()
        if (!Permissions.canScan(appContext)) {
            publishHealth("Missing Bluetooth or location permission.")
            return
        }
        val bt = adapter()
        if (bt == null) {
            publishHealth("No Bluetooth adapter on this device.")
            return
        }
        if (!bt.isEnabled) {
            publishHealth("Bluetooth is off. Turn it on to start listening.")
            return
        }
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            publishHealth("Bluetooth LE scanner unavailable.")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            // Filtered on purpose, which is the opposite of what it used to be.
            //
            // An unfiltered scan returns every advertiser in range, which is what this app
            // wants - right up until the screen goes off, at which point Android 8.1 and
            // later return nothing at all from it. A recording left running overnight went
            // flat at the moment the display timed out, and holding the screen awake looked
            // like the only cure.
            //
            // ScanFilters.broad() is as close to "everything" as the API can express. It is
            // used with the screen on as well, so a count taken at midnight is comparable
            // with one taken at noon - a measurement that quietly changes what it includes
            // halfway through is worse than one that consistently includes less.
            scanner.startScan(ScanFilters.broad(), settings, callback)
            scanning = true
            scanStartedAtMs = System.currentTimeMillis()
            lastResultMs = scanStartedAtMs
            publishHealth(null)
            Log.i(TAG, "Scan started, restarts so far: $restarts")
        } catch (e: Exception) {
            scanning = false
            Log.e(TAG, "startScan threw", e)
            publishHealth("Could not start scanning: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(callback) }
            .onFailure { Log.w(TAG, "stopScan threw", it) }
        scanning = false
        publishHealth(_health.value.error)
    }

    /**
     * Android 12+ allows only five scan starts per 30 seconds per app before silently
     * throttling it, so a persistent failure must not be allowed to retry in a tight loop.
     */
    private fun restartScan(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastRestartAtMs < MIN_RESTART_GAP_MS) return
        lastRestartAtMs = now
        restarts++
        Log.i(TAG, "Restarting scan: $why")
        stopScan()
        resetRateLearning(now)
        scope.launch {
            delay(1_200)
            synchronized(BleScanHub) { if (claims.isNotEmpty()) startScan() }
        }
    }

    private fun resetRateLearning(now: Long) {
        referenceRate = 0.0
        currentRate = 0.0
        rateSamples = 0
        starvedSinceMs = 0L
        sampleAds.set(0)
        sampleStartMs = now
    }

    // ------------------------------------------------------------- the watchdog

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                sampleRate(now)
                runWatchdog(now)
                publishHealth(_health.value.error)
            }
        }
    }

    private fun runWatchdog(now: Long) {
        val bt = adapter()
        if (bt == null || !bt.isEnabled) return

        if (!scanning) {
            if (now - lastScanAttemptMs > RETRY_GAP_MS) restartScan("scanner not running")
            return
        }
        if (now - lastResultMs > SILENCE_RESTART_MS) {
            restartScan("no results for ${SILENCE_RESTART_MS / 1000}s")
            return
        }
        if (now - scanStartedAtMs > cycleMillis) {
            restartScan("scan cycle, flushing the controller duplicate filter")
            return
        }
        if (isStarved(now)) {
            restartScan("delivery rate collapsed, likely duplicate-filter exhaustion")
        }
    }

    private fun sampleRate(now: Long) {
        if (sampleStartMs == 0L) {
            sampleStartMs = now
            return
        }
        val elapsed = now - sampleStartMs
        if (elapsed < RATE_SAMPLE_MS) return

        currentRate = sampleAds.getAndSet(0) * 1000.0 / elapsed
        sampleStartMs = now
        rateSamples++

        // Learn from the early, healthy part of a cycle, taking the best sample rather
        // than an average - a dip is the thing we are trying to detect.
        if (rateSamples <= REFERENCE_SAMPLES) {
            referenceRate = maxOf(referenceRate, currentRate)
        }
    }

    private fun isStarved(now: Long): Boolean {
        if (referenceRate < MIN_MEANINGFUL_RATE) return false
        if (rateSamples <= REFERENCE_SAMPLES) return false
        if (currentRate >= referenceRate * STARVED_FRACTION) {
            starvedSinceMs = 0L
            return false
        }
        if (starvedSinceMs == 0L) {
            starvedSinceMs = now
            return false
        }
        return now - starvedSinceMs >= STARVED_FOR_MS
    }

    private fun publishHealth(error: String?) {
        _health.value = ScanHealth(
            scanning = scanning,
            advertsPerSecond = currentRate,
            referenceRate = referenceRate,
            restarts = restarts,
            subscribers = claims.size,
            claims = claims.toSet(),
            error = error,
        )
    }
}
