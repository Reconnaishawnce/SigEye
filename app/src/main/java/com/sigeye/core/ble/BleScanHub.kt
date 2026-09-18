package com.sigeye.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import kotlinx.coroutines.channels.Channel
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

    /**
     * How long the one device being followed may go quiet before the controller is flushed.
     *
     * Shorter than [SILENCE_RESTART_MS] because it is a sharper question. Total silence
     * across every advertiser in range means the radio has stopped; silence from the single
     * device somebody is walking towards means that device has aged out of the controller
     * duplicate-filter table, which is a thing a restart fixes in about a second.
     */
    private const val FOCUS_SILENCE_MS = 20_000L

    /**
     * Packets that may be waiting to be decoded before the oldest are dropped.
     *
     * Large enough to ride out a garbage collection pause or a burst as somebody walks into
     * a hall, small enough that a backlog means something is genuinely wrong rather than
     * that a minute of stale packets is about to be decoded and reported as current.
     */
    private const val INTAKE_CAPACITY = 8_192

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
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val claims = mutableSetOf<String>()
    private var scanning = false
    private var scanStartedAtMs = 0L
    private var lastScanAttemptMs = 0L
    private var lastRestartAtMs = 0L
    private var lastResultMs = 0L
    private var restarts = 0

    /**
     * A packet as the radio handed it over, before anything has been decoded.
     *
     * The arrival time is captured in the callback rather than by the decoder, because
     * half this app measures gaps between arrivals and a timestamp taken after a queue is
     * a measurement of the queue.
     */
    private class Pending(val result: ScanResult, val atMs: Long, val focused: Boolean)

    /**
     * Packets waiting to be decoded.
     *
     * Android delivers scan results on the main looper. Decoding one allocates a list of
     * UUID strings, two maps and an appearance parse, which is nothing in a room with
     * twenty devices and is thousands of times a second in an airport - on the same thread
     * Compose draws on. Once that thread cannot keep up the Bluetooth stack drops results,
     * and what gets dropped is not the packets from devices nobody cares about. So the
     * callback does the least it possibly can and hands the work to a background thread.
     */
    private val intake = Channel<Pending>(
        capacity = INTAKE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var decoder: Job? = null

    private val intakeIn = AtomicInteger(0)
    private val intakeOut = AtomicInteger(0)

    /**
     * The address a screen asked to follow, whether or not a scan for it is running.
     *
     * Separate from [focusedAddress] on purpose. Focusing can fail - the radio may be off
     * when the screen opens, or the platform may refuse another registration - and the
     * request has to outlive the failure so that turning Bluetooth on, or the next
     * controller flush, picks it back up instead of quietly leaving the screen on the
     * broad scan forever.
     */
    private var requestedFocus: String? = null

    /**
     * The address actually registered with the controller under a filter of its own.
     *
     * Only ever set after a scan for it has genuinely started, because the decoder uses it
     * to drop the broad copy of that device. Setting it optimistically would silence the
     * one device somebody is walking towards.
     */
    @Volatile
    private var focusedAddress: String? = null

    private var focusCallback: ScanCallback? = null
    private var lastFocusResultMs = 0L

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
                    if (claims.isNotEmpty()) {
                        startScan()
                        requestedFocus?.let { startFocusScan(it) }
                    }
                }
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        ignoreList = IgnoreList.get(appContext)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        startDecoder()
        initialized = true
    }

    /** Claim the radio under [tag]. Idempotent per tag. */
    @Synchronized
    fun acquire(tag: String) {
        check(initialized) { "BleScanHub.init() must be called before acquire()" }
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
            // The focused scan is a separate registration and would hold the radio on by
            // itself, so the last claim going has to take it down too.
            requestedFocus = null
            stopFocusScan()
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

    /**
     * The whole of the main-thread work for one packet: a clock read and a queue push.
     *
     * Everything that can be deferred is deferred. What cannot is the arrival time and the
     * rate counters, because the first is the measurement and the second is how the
     * watchdog knows the radio is alive.
     */
    private fun intake(result: ScanResult?, focused: Boolean) {
        // A replay must be the only thing on the flow. Letting the live radio through
        // at the same time would mix a recorded room with the one you are sitting in,
        // and every reading afterwards would be of neither.
        if (replaying) return
        val scanResult = result ?: return

        val now = System.currentTimeMillis()
        if (focused) lastFocusResultMs = now else lastResultMs = now
        sampleAds.incrementAndGet()
        intakeIn.incrementAndGet()
        intake.trySend(Pending(scanResult, now, focused))
    }

    /** Decodes packets away from the main thread and puts them on the flow. */
    private fun startDecoder() {
        if (decoder != null) return
        decoder = scope.launch {
            for (pending in intake) {
                intakeOut.incrementAndGet()
                val address = pending.result.device?.address ?: continue
                if (ignoreList.isIgnored(address)) continue

                // A focused device is registered with the controller twice: once by the
                // broad filter set and once by its own address. Both fire for the same
                // packet, so the broad copy is dropped. Emitting both would double every
                // arrival, and the interval between arrivals is a fingerprint here.
                if (!pending.focused && address.equals(focusedAddress, ignoreCase = true)) continue

                val advert = Advert.from(pending.result, pending.atMs) ?: continue
                _adverts.tryEmit(advert)
                recorder?.invoke(advert)
            }
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) = intake(result, false)

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { intake(it, false) }
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

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0L)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    // ------------------------------------------------------- following one device

    /**
     * Puts one address under a filter of its own, in addition to the broad scan.
     *
     * A screen that cares about a single device would otherwise read every packet in the
     * room and throw away all but one in a thousand, which is fine in a room and useless in
     * a terminal building. This registers a second scan whose only filter is the address,
     * so the controller does the discarding in hardware and that device arrives at whatever
     * rate it actually advertises, however busy the air is.
     *
     * It also gives the watchdog a question it could not previously ask. The starvation
     * test watches total delivery, and in a crowd the total never falls: rotating addresses
     * are an endless supply of advertisers the controller has never seen, so throughput
     * looks healthy while the one device being followed has aged out of the duplicate
     * filter and gone silent. Watching that device by name catches it.
     *
     * Pass null to stop. Only one address at a time; focusing on a second replaces the
     * first, because the point is a device somebody is walking towards and there is only
     * ever one of those.
     */
    @Synchronized
    fun focus(address: String?) {
        val wanted = address?.uppercase()?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        if (wanted == requestedFocus) return
        requestedFocus = wanted
        stopFocusScan()
        if (wanted != null) startFocusScan(wanted)
        publishHealth(_health.value.error)
    }

    /** The address currently under its own filter, for a screen that should say so. */
    val focusedOn: String? get() = focusedAddress

    private val focusScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) = intake(result, true)

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { intake(it, true) }
        }

        override fun onScanFailed(errorCode: Int) {
            // The broad scan still carries this device, so the decoder has to stop
            // suppressing a copy that is no longer arriving by any other route. The
            // request survives: the next controller flush will try again.
            Log.w(TAG, "Focused scan failed with $errorCode, falling back to the broad scan")
            synchronized(BleScanHub) {
                focusedAddress = null
                focusCallback = null
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by Permissions.canScan
    private fun startFocusScan(address: String) {
        if (!Permissions.canScan(appContext)) return
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        runCatching { scanner.startScan(listOf(filter), scanSettings(), focusScanCallback) }
            .onSuccess {
                focusCallback = focusScanCallback
                // Set last, so the decoder never suppresses the broad copy of a device
                // whose own scan did not actually start.
                focusedAddress = address
                lastFocusResultMs = System.currentTimeMillis()
                Log.i(TAG, "Focused on $address")
            }
            .onFailure { Log.w(TAG, "Could not focus on $address", it) }
    }

    @SuppressLint("MissingPermission")
    private fun stopFocusScan() {
        val running = focusCallback ?: run { focusedAddress = null; return }
        focusedAddress = null
        focusCallback = null
        lastFocusResultMs = 0L
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(running) }
            .onFailure { Log.w(TAG, "stopScan threw for the focused scan", it) }
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

        val settings = scanSettings()

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
        // Whatever is being followed has to be re-registered as well: the restart exists
        // to flush the controller duplicate-filter table, and a filter left in place across
        // it would be pointing at a device the table has already decided it has seen.
        val refocus = requestedFocus
        stopFocusScan()
        stopScan()
        resetRateLearning(now)
        scope.launch {
            delay(1_200)
            synchronized(BleScanHub) {
                if (claims.isEmpty()) return@synchronized
                startScan()
                refocus?.let { startFocusScan(it) }
            }
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
        // A crowd defeats the throughput test below, so ask about the one device that
        // matters by name. See [focus] for why the two questions are not the same.
        val watching = focusedAddress
        if (watching != null && lastFocusResultMs > 0L && now - lastFocusResultMs > FOCUS_SILENCE_MS) {
            restartScan("nothing from $watching for ${FOCUS_SILENCE_MS / 1000}s")
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
            backlog = (intakeIn.get() - intakeOut.get()).coerceAtLeast(0),
            focusedOn = focusedAddress,
            error = error,
        )
    }
}
