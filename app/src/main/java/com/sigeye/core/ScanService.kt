package com.sigeye.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sigeye.MainActivity
import com.sigeye.R
import com.sigeye.core.ble.BleScanHub
import com.sigeye.experiments.trainspotter.Bin
import com.sigeye.experiments.trainspotter.TrainSpotterEngine
import com.sigeye.experiments.watchlist.WatchEngine
import com.sigeye.experiments.watchlist.WatchHit
import com.sigeye.experiments.watchlist.WatchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One foreground service for every background experiment.
 *
 * Modes are independent - Train Spotter and the watchlist can run alone or together - but
 * they share a single claim on [BleScanHub], so turning both on costs one scan rather than
 * two. The service lives exactly as long as at least one mode is active.
 */
class ScanService : Service() {

    /**
     * What the service can be asked to run.
     *
     * The last three are recordings rather than watchers: they accumulate into
     * [Recordings], which the screens read rather than own, so closing a screen no longer
     * ends the measurement it started.
     */
    enum class Mode(val label: String) {
        TRAIN_SPOTTER("Train Spotter"),
        WATCHLIST("Signal Watch"),
        FORENSICS("Forensics"),
        PLACE("Place Profiler"),
        CONVOY("Journey"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pump: Job? = null
    private var ticker: Job? = null

    private val modes = mutableSetOf<Mode>()

    private var trainSpotter: TrainSpotterEngine? = null
    private var watch: WatchEngine? = null
    private lateinit var watchStore: WatchStore

    private var alertSerial = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BleScanHub.init(this)
        // The service can outlive the activity that normally loads this.
        OuiRegistry.load(this)
        watchStore = WatchStore.get(this)
        Recordings.init(this)
        createChannels()
        WatchEngine.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_LABEL -> {
                trainSpotter?.markLabel("TRAIN")
                Log.i(TAG, "Manual TRAIN label queued for next bin")
                return START_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                trainSpotter?.reloadConfig()
                return START_STICKY
            }

            ACTION_STOP_MODE -> {
                intent.modeExtra()?.let { stopMode(it) }
                return if (modes.isEmpty()) START_NOT_STICKY else START_STICKY
            }
        }

        val mode = intent?.modeExtra()
        if (mode == null && modes.isEmpty()) {
            // A sticky restart with no intent and nothing to run.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Permissions.canScan(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!goForeground()) return START_NOT_STICKY

        mode?.let { startMode(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownInternals()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ modes

    private fun startMode(mode: Mode) {
        val now = System.currentTimeMillis()
        if (!modes.add(mode)) {
            Log.i(TAG, "$mode already running")
            return
        }

        when (mode) {
            Mode.TRAIN_SPOTTER -> {
                trainSpotter = TrainSpotterEngine(
                    context = this,
                    onSpike = { bin -> notifySpike(bin) },
                    onBinClosed = { updateOngoingNotification() },
                ).also { it.start(now) }
            }

            Mode.WATCHLIST -> {
                watchStore.armed = true
                watch = WatchEngine(this, watchStore) { hit -> notifyWatchHit(hit) }
            }

            // The recordings are started by the screen that owns them, because only it
            // knows the settings - slice length, leg name. The service just feeds them.
            Mode.FORENSICS, Mode.PLACE, Mode.CONVOY -> Unit
        }

        BleScanHub.acquire(HUB_TAG)
        startPump()
        publishModes()
        updateOngoingNotification()
    }

    private fun stopMode(mode: Mode) {
        if (!modes.remove(mode)) return
        when (mode) {
            Mode.TRAIN_SPOTTER -> {
                trainSpotter?.stop()
                trainSpotter = null
            }

            Mode.WATCHLIST -> {
                watchStore.armed = false
                watch = null
            }

            Mode.FORENSICS -> Recordings.forensics.stop(System.currentTimeMillis())
            // Place and Convoy keep whatever they have: stopping the service should not
            // throw away four hours of profile, and the screen decides what to do with it.
            Mode.PLACE, Mode.CONVOY -> Unit
        }
        publishModes()
        if (modes.isEmpty()) {
            shutdown()
        } else {
            updateOngoingNotification()
        }
    }

    private fun shutdown() {
        shutdownInternals()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownInternals() {
        pump?.cancel()
        ticker?.cancel()
        pump = null
        ticker = null
        trainSpotter?.stop()
        trainSpotter = null
        watch = null
        watchStore.armed = false
        modes.clear()
        BleScanHub.release(HUB_TAG)
        publishModes()
    }

    // ------------------------------------------------------------------- pump

    private fun startPump() {
        if (pump == null) {
            pump = scope.launch {
                BleScanHub.adverts.collect { advert ->
                    trainSpotter?.onAdvert(advert)
                    watch?.onAdvert(advert)
                    Recordings.onAdvert(advert, modes)
                }
            }
        }
        if (ticker == null) {
            ticker = scope.launch {
                var lastPrune = System.currentTimeMillis()
                var lastNotification = 0L
                while (isActive) {
                    delay(500)
                    val now = System.currentTimeMillis()
                    trainSpotter?.tick(now)
                    trainSpotter?.publish(BleScanHub.health.value)
                    if (now - lastPrune > 10 * 60_000L) {
                        watch?.prune(now)
                        lastPrune = now
                    }
                    // A recording's notification is the only view of it when the screen is
                    // off, so it is kept current. Every five seconds, not every tick -
                    // rewriting a notification twice a second is its own battery cost.
                    if (recording() && now - lastNotification > 5_000L) {
                        lastNotification = now
                        updateOngoingNotification()
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------- notifications

    private fun goForeground(): Boolean = try {
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
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        stopSelf()
        false
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Listening", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while SigEye is listening in the background." },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRAIN, "Train alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
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
        val health = BleScanHub.health.value
        val running = modes.joinToString(" + ") { it.label }.ifEmpty { "Starting" }

        // A recording's progress is the useful thing to see from the lock screen; the
        // packet rate only matters when nothing is being recorded.
        val progress = Recordings.summary(modes)
        val detail = when {
            health.error != null -> health.error
            health.starved -> "Signal starved, restarting the scan"
            progress.isNotEmpty() -> progress.joinToString(" \u00B7 ")
            else -> String.format(Locale.US, "%.1f adverts/sec", health.advertsPerSecond)
        }

        val stopIntent = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(running)
            .setContentText(detail)
            .setContentIntent(contentIntent())
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** True while something is accumulating rather than merely watching. */
    private fun recording(): Boolean =
        modes.any { it == Mode.FORENSICS || it == Mode.PLACE || it == Mode.CONVOY }

    private fun updateOngoingNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ONGOING, buildOngoingNotification())
        }
    }

    private fun notifySpike(bin: Bin) {
        val notification = NotificationCompat.Builder(this, CHANNEL_TRAIN)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle("Train passing?")
            .setContentText(
                "${bin.newCount} new devices in one bin, usual is " +
                    String.format(Locale.US, "%.1f", bin.baseline),
            )
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_TRAIN, notification)
        }
    }

    private fun notifyWatchHit(hit: WatchHit) {
        val notification = NotificationCompat.Builder(this, WatchEngine.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(hit.ruleLabel)
            .setContentText("${hit.displayName} — ${hit.rssi} dBm")
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(WatchEngine.NOTIF_BASE + (alertSerial++ % 16), notification)
        }
    }

    private fun publishModes() {
        _activeModes.value = modes.toSet()
    }

    private fun Intent.modeExtra(): Mode? =
        getStringExtra(EXTRA_MODE)?.let { name -> runCatching { Mode.valueOf(name) }.getOrNull() }

    companion object {
        private const val TAG = "SigEye/Service"
        private const val CHANNEL_ONGOING = "scan"
        private const val CHANNEL_TRAIN = "alerts"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_TRAIN = 2
        private const val HUB_TAG = "service"

        const val ACTION_START_MODE = "com.sigeye.START_MODE"
        const val ACTION_STOP_MODE = "com.sigeye.STOP_MODE"
        const val ACTION_STOP_ALL = "com.sigeye.STOP_ALL"
        const val ACTION_LABEL = "com.sigeye.LABEL"
        const val ACTION_RELOAD_CONFIG = "com.sigeye.RELOAD_CONFIG"
        const val EXTRA_MODE = "mode"

        private val _activeModes = MutableStateFlow<Set<Mode>>(emptySet())

        /** Which background experiments are live, for the screens to reflect. */
        val activeModes: StateFlow<Set<Mode>> = _activeModes

        fun start(context: Context, mode: Mode) {
            context.startForegroundService(
                Intent(context, ScanService::class.java)
                    .setAction(ACTION_START_MODE)
                    .putExtra(EXTRA_MODE, mode.name),
            )
        }

        fun stop(context: Context, mode: Mode) {
            context.startService(
                Intent(context, ScanService::class.java)
                    .setAction(ACTION_STOP_MODE)
                    .putExtra(EXTRA_MODE, mode.name),
            )
        }

        fun label(context: Context) {
            context.startService(
                Intent(context, ScanService::class.java).setAction(ACTION_LABEL),
            )
        }

        fun reloadConfig(context: Context) {
            context.startService(
                Intent(context, ScanService::class.java).setAction(ACTION_RELOAD_CONFIG),
            )
        }
    }
}
