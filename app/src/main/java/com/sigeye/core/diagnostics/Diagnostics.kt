package com.sigeye.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sigeye.BuildConfig
import com.sigeye.core.Contact
import com.sigeye.core.CrashLog
import com.sigeye.core.FollowRunner
import com.sigeye.core.MyDevices
import com.sigeye.core.Recordings
import com.sigeye.core.Replay
import com.sigeye.core.ScanService
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.wifi.WifiScanHub
import com.sigeye.experiments.trainspotter.TrainRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watching for freezes and stutter, and turning them into a report somebody can send.
 *
 * People were saying the app froze and nobody was sending anything, which is no surprise: by
 * the time somebody opens a mail app and types "it froze", what froze is long gone and the
 * message says nothing a developer can use. So the evidence is collected all the time, cheaply,
 * and a shake hands it over in two taps.
 *
 * The watchdog is the important part. Every half second it asks the main thread to run a tiny
 * task; if the task has not run by the next check, the screen is frozen, and the main thread's
 * stack trace at that moment says exactly what it was stuck doing. That one trace is worth more
 * than any description.
 *
 * Nothing about the devices around the user goes into a report. Counts, rates and the app's
 * own state go in; addresses are stripped from everything, logs included.
 */
object Diagnostics {

    private const val CHECK_MS = 500L
    private const val LATER_SAMPLE_MS = 2_000L
    private const val NUDGE_EVERY_MS = 10 * 60_000L
    private const val NUDGE_WITHIN_MS = 2 * 60_000L
    private const val KEEP_REPORTS = 10
    private const val LOG_LINES = 400
    private const val STACK_FRAMES = 30

    val stalls = StallLog()
    val frames = FrameStats()

    private lateinit var app: Context
    private var installed = false
    private val startedAtMs = System.currentTimeMillis()

    @Volatile
    var screen: String = "home"
        private set
    private val trail = ArrayDeque<Pair<Long, String>>()

    private val _asking = MutableStateFlow(false)

    /** True while the report dialog should be showing. */
    val asking: StateFlow<Boolean> = _asking

    private var lastNudgeMs = 0L

    private val prefs by lazy { app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }

    /** Whether shaking the phone opens a report. On by default; there is a switch in Settings. */
    var shakeToReport: Boolean
        get() = prefs.getBoolean("shake", true)
        set(value) = prefs.edit().putBoolean("shake", value).apply()

    fun install(context: Context) {
        if (installed) return
        installed = true
        app = context.applicationContext
        startWatchdog()
    }

    fun onScreen(route: String?) {
        val name = Redact.screen(route)
        screen = name
        synchronized(trail) {
            trail.addLast(System.currentTimeMillis() to name)
            while (trail.size > 20) trail.removeFirst()
        }
    }

    fun requestReport() {
        _asking.value = true
    }

    fun dismiss() {
        _asking.value = false
    }

    // ------------------------------------------------------------------ the watchdog

    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        Thread({
            val answered = AtomicBoolean(true)
            while (true) {
                answered.set(false)
                val postedAt = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                SystemClock.sleep(CHECK_MS)
                if (answered.get()) continue

                // Stuck. What it is stuck on, now, is the evidence.
                val first = format(mainThread.stackTrace)
                var later = emptyList<String>()
                while (!answered.get()) {
                    SystemClock.sleep(50)
                    if (later.isEmpty() && SystemClock.uptimeMillis() - postedAt >= LATER_SAMPLE_MS) {
                        later = format(mainThread.stackTrace)
                    }
                }
                val duration = SystemClock.uptimeMillis() - postedAt
                if (duration >= Stall.NOTICEABLE_MS) {
                    val stall = Stall(System.currentTimeMillis(), duration, screen, first, later)
                    stalls.record(stall)
                    if (stall.frozen) main.post { nudge(stall) }
                }
            }
        }, "SigEye-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    /** After a real freeze, one quiet line saying how to report it. At most every ten minutes. */
    private fun nudge(stall: Stall) {
        if (!shakeToReport) return
        val now = System.currentTimeMillis()
        if (now - lastNudgeMs < NUDGE_EVERY_MS) return
        lastNudgeMs = now
        Toast.makeText(
            app,
            "SigEye froze for %.1f s. Shake your phone to report it.".format(Locale.US, stall.durationMs / 1000.0),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun format(stack: Array<StackTraceElement>): List<String> =
        stack.take(STACK_FRAMES).map { it.toString() }

    // ------------------------------------------------------------------ frames

    private var frameThread: HandlerThread? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    /**
     * Counts slow frames while a window is showing.
     *
     * FrameMetrics only reports frames that were actually drawn, so an idle screen costs
     * nothing, which matters for an app people leave open for hours.
     */
    fun watchFrames(window: Window) {
        if (frameListener != null) return
        val thread = HandlerThread("SigEye-frames").also { it.start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            frames.frame(screen, TimeUnit.NANOSECONDS.toMillis(metrics.getMetric(FrameMetrics.TOTAL_DURATION)))
        }
        runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            .onSuccess {
                frameThread = thread
                frameListener = listener
            }
            .onFailure { thread.quitSafely() }
    }

    fun stopFrames(window: Window) {
        frameListener?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
        frameListener = null
        frameThread?.quitSafely()
        frameThread = null
    }

    // ------------------------------------------------------------------ the report

    /** One line for the dialog: the most recent freeze, if there was one in the last two minutes. */
    fun recentFreeze(): String? {
        val stall = stalls.latestWithin(System.currentTimeMillis(), NUDGE_WITHIN_MS) ?: return null
        val ago = (System.currentTimeMillis() - stall.atMs) / 1000
        return "SigEye froze for %.1f s on %s, %d seconds ago.".format(
            Locale.US, stall.durationMs / 1000.0, stall.screen, ago,
        )
    }

    /**
     * Everything for one report, as plain text.
     *
     * Slow to build - it reads the app's own log - so call it off the main thread.
     */
    fun build(note: String): String = buildString {
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

        appendLine("SigEye slowdown report")
        appendLine("Made: ${time.format(Date(now))}")
        appendLine()
        appendLine("WHAT THEY SAID")
        appendLine(note.ifBlank { "(nothing written)" })
        appendLine()

        section("PHONE") {
            appendLine("App: SigEye ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App running for: ${minutes(now - startedAtMs)}")
            battery()
            memory()
        }

        section("WHAT THE APP WAS DOING") {
            appendLine("Screen: $screen")
            synchronized(trail) {
                appendLine("Recent screens: " + trail.joinToString("  ->  ") { (at, name) ->
                    "$name (${(now - at) / 1000}s ago)"
                })
            }
            appendLine("Background modes: " + ScanService.activeModes.value.joinToString().ifEmpty { "none" })
            val health = BleScanHub.health.value
            appendLine(
                "Bluetooth: scanning=${health.scanning}, %.0f packets/s, backlog=${health.backlog}, ".format(
                    Locale.US, health.advertsPerSecond,
                ) + "crowded=${health.crowded}, restarts=${health.restarts}, claims=${health.claims.joinToString()}",
            )
            health.error?.let { appendLine("Bluetooth error: $it") }
            val wifi = WifiScanHub.state.value
            appendLine("Wi-Fi: scans=${wifi.scans}, networks=${wifi.results.size}, throttled=${wifi.throttled}")
            appendLine("Replaying a capture: ${Replay.active}")
            appendLine("Forensics: ${Recordings.forensics.deviceCount} devices, ${Recordings.forensics.networkCount} networks")
            appendLine("Place Profiler: ${Recordings.place.deviceCount} devices, ${Recordings.placeWifi.deviceCount} networks")
            appendLine("Journey legs: ${Recordings.convoy.legCount}")
            val follow = FollowRunner.state.value
            appendLine("Follow Me: watching ${follow.watching}, still in ${follow.stillIn.size}")
            val train = TrainRecorder.state.value
            appendLine("Train recording: active=${train.active}, rows=${train.rows}")
            appendLine("Marked as mine: ${MyDevices.get(app).size()}")
        }

        section("FREEZES (${stalls.count()} this session, ${minutes(stalls.totalMs())} in total)") {
            val recent = stalls.recent().asReversed()
            if (recent.isEmpty()) appendLine("None caught.")
            recent.forEach { stall ->
                appendLine(
                    "%s  %.1f s on %s".format(
                        Locale.US, time.format(Date(stall.atMs)), stall.durationMs / 1000.0, stall.screen,
                    ),
                )
                stall.stack.forEach { appendLine("    at $it") }
                if (stall.laterStack.isNotEmpty()) {
                    appendLine("  still stuck 2 s in:")
                    stall.laterStack.forEach { appendLine("    at $it") }
                }
                appendLine()
            }
        }

        section("FRAMES BY SCREEN") {
            val tallies = frames.snapshot()
            if (tallies.isEmpty()) appendLine("None recorded.")
            tallies.forEach { (name, tally) ->
                appendLine(
                    "%-14s %6d frames, %5d slow (%.1f%%), %3d frozen, worst %d ms".format(
                        Locale.US, name, tally.frames, tally.slow, tally.slowPercent, tally.frozen, tally.worstMs,
                    ),
                )
            }
        }

        section("BUSY THREADS RIGHT NOW") { threads() }
        section("HOW THE APP LAST ENDED") { exits() }

        section("RECENT CRASHES") {
            val crashes = CrashLog.reports(app).take(3)
            if (crashes.isEmpty()) appendLine("None.")
            crashes.forEach { appendLine("${it.stamp()}  ${it.headline}") }
        }

        section("APP LOG (addresses removed)") { appendLine(Redact.text(log())) }
    }

    /** Writes the report and opens a mail app with it attached. False if nothing can send it. */
    fun send(context: Context, note: String, text: String): Boolean {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports").apply { mkdirs() }
        folder.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_REPORTS - 1)?.forEach { it.delete() }
        val file = File(folder, "sigeye-report-${System.currentTimeMillis()}.txt").apply { writeText(text) }

        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Contact.ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, "SigEye slowdown report (${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, note.ifBlank { "Report attached." } + "\n\n" + (recentFreeze() ?: ""))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ pieces

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine(title)
        runCatching { body() }.onFailure { appendLine("(could not read: ${it.javaClass.simpleName})") }
        appendLine()
    }

    private fun StringBuilder.battery() {
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendLine(
            "Battery: ${level ?: "?"}%, charging=${battery?.isCharging}, " +
                "battery saver=${power?.isPowerSaveMode}",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Hot phones throttle the processor, which looks exactly like the app being slow.
            val thermal = when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutting down"
                else -> "unknown"
            }
            appendLine("Heat: $thermal")
        }
    }

    private fun StringBuilder.memory() {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        appendLine("App memory: $used MB used of ${runtime.maxMemory() / 1_048_576} MB, native ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB")
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        appendLine("Phone memory: ${info.availMem / 1_048_576} MB free, low=${info.lowMemory}")
    }

    /** Threads that are working or waiting on a lock, which is where a slowdown lives. */
    private fun StringBuilder.threads() {
        Thread.getAllStackTraces()
            .filter { (thread, _) -> thread.state == Thread.State.RUNNABLE || thread.state == Thread.State.BLOCKED }
            .filterKeys { it.name != "SigEye-watchdog" }
            .forEach { (thread, stack) ->
                appendLine("${thread.name} (${thread.state})")
                stack.take(12).forEach { appendLine("    at $it") }
            }
    }

    /**
     * Why the app's last few runs ended. Android keeps this, and for a freeze that ended in
     * "not responding" it keeps the stack trace too, which is the one thing a report sent
     * after restarting the app could otherwise never have.
     */
    private fun StringBuilder.exits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLine("Not available before Android 11.")
            return
        }
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 5)
        if (exits.isEmpty()) appendLine("Nothing recorded.")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        exits.forEach { exit ->
            appendLine("${time.format(Date(exit.timestamp))}  ${reason(exit.reason)}  ${exit.description.orEmpty()}")
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.bufferedReader()?.useLines { lines ->
                        // The main thread's part of the dump, which is what was frozen.
                        lines.dropWhile { !it.startsWith("\"main\"") }.take(40).forEach { appendLine("    $it") }
                    }
                }
            }
        }
    }

    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_ANR -> "NOT RESPONDING"
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by the system"
        else -> "reason $code"
    }

    /** The app's own recent log. Android lets an app read its own lines without permission. */
    private fun log(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", LOG_LINES.toString(), "--pid", Process.myPid().toString(),
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(3, TimeUnit.SECONDS)
        text
    }.getOrElse { "(log unavailable: ${it.javaClass.simpleName})" }

    private fun minutes(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
