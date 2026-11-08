package com.sigeye.experiments.trainspotter

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** A long recording as the screen needs to show it. */
data class LongRecording(
    val active: Boolean = false,
    val startedAtMs: Long = 0L,
    /** When it stops on its own, or null to run until somebody stops it. */
    val endsAtMs: Long? = null,
    val path: String? = null,
    val rows: Long = 0L,
    val bytes: Long = 0L,
    val latest: Second? = null,
    /** Every closed ten-second bucket so far, for the live graph. */
    val buckets: List<Bucket> = emptyList(),
    /** Seconds the phone slept through instead of recording. */
    val missedSeconds: Long = 0L,
)

/**
 * Recording train traffic for a day or more, one row a second.
 *
 * The live Train Spotter answers "is a train going past right now". This answers the
 * question you can only ask afterwards: when was the first train, when is rush hour, how
 * full do the trains get. That needs the same plain numbers written every second for
 * twenty-four hours, which is what this does.
 *
 * It rides on the Train Spotter's scan rather than starting its own, and holds a partial
 * wake lock while running. Without one, Android lets the processor nap with the screen off
 * and the once-a-second row turns into a row whenever the phone feels like it. That costs
 * battery, which is why the screen says to leave it plugged in.
 *
 * Every entry point is synchronized: adverts arrive on the scan thread and ticks on the
 * service's timer, and this app has already learned what unsynchronized recorders do.
 */
object TrainRecorder {

    private const val TAG = "SigEye/TrainRec"

    /** A day of buckets is 8,640. A week is plenty for anybody's phone memory. */
    private const val MAX_BUCKETS = 7 * 8_640

    /** Write to disk this often, so a dead battery costs ten seconds rather than the day. */
    private const val FLUSH_EVERY = 10

    /** Upper limit on the wake lock when there is no end time, as a safety net. */
    private const val LONGEST_MS = 7L * 24 * 60 * 60 * 1000

    private val _state = MutableStateFlow(LongRecording())
    val state: StateFlow<LongRecording> = _state

    private var counts: RollingCounts? = null
    private var accumulator = TrainDay.Accumulator()
    private val buckets = ArrayList<Bucket>()
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var nextSampleMs = 0L
    private var sinceFlush = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val zone: ZoneId = ZoneId.systemDefault()

    val active: Boolean get() = _state.value.active

    fun directory(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "train").apply { mkdirs() } }

    /** Past recordings, newest first. */
    fun recordings(context: Context): List<File> =
        directory(context)
            ?.listFiles { f -> f.name.startsWith("train-") && f.name.endsWith(".csv") && !f.name.endsWith("-trains.csv") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    @Synchronized
    fun start(
        context: Context,
        durationMs: Long?,
        forgetMs: Long,
        rssiFloor: Int,
        nowMs: Long,
    ): Boolean {
        if (_state.value.active) return true
        val folder = directory(context) ?: return false

        val name = "train-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".csv"
        val target = File(folder, name)
        val opened = runCatching { BufferedWriter(FileWriter(target, true)) }.getOrNull() ?: return false

        opened.write(TrainCsv.HEADER)
        opened.newLine()
        opened.flush()

        writer = opened
        file = target
        counts = RollingCounts(forgetMs = forgetMs, rssiFloor = rssiFloor).also { it.start(nowMs) }
        accumulator = TrainDay.Accumulator()
        buckets.clear()
        nextSampleMs = nowMs - Math.floorMod(nowMs, 1000L) + 1000L
        sinceFlush = 0
        holdWake(context, durationMs)

        _state.value = LongRecording(
            active = true,
            startedAtMs = nowMs,
            endsAtMs = durationMs?.let { nowMs + it },
            path = target.absolutePath,
            bytes = target.length(),
        )
        Log.i(TAG, "Long recording started: ${target.name}")
        return true
    }

    @Synchronized
    fun stop() {
        if (!_state.value.active) return
        accumulator.flush()?.let { buckets.add(it) }
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        counts = null
        releaseWake()
        _state.value = _state.value.copy(
            active = false,
            buckets = buckets.toList(),
            bytes = file?.length() ?: _state.value.bytes,
        )
        Log.i(TAG, "Long recording stopped after ${_state.value.rows} rows")
    }

    @Synchronized
    fun onAdvert(address: String, rssi: Int, atMs: Long) {
        counts?.advert(address, rssi, atMs)
    }

    /** Called often; writes one row each time a new whole second has started. */
    @Synchronized
    fun tick(nowMs: Long) {
        val running = counts ?: return
        if (nowMs < nextSampleMs) return

        // If the timer fired late, the seconds in between were not recorded. Saying so is
        // better than quietly writing one row for several seconds of traffic.
        val missed = ((nowMs - nextSampleMs) / 1000L).coerceAtLeast(0L)
        nextSampleMs = nowMs - Math.floorMod(nowMs, 1000L) + 1000L

        val second = running.sample(nowMs)
        val line = TrainCsv.row(second, zone)
        runCatching {
            writer?.write(line)
            writer?.newLine()
            if (++sinceFlush >= FLUSH_EVERY) {
                writer?.flush()
                sinceFlush = 0
            }
        }.onFailure { Log.w(TAG, "Could not write a row", it) }

        val closed = accumulator.add(second)
        if (closed != null) {
            buckets.add(closed)
            while (buckets.size > MAX_BUCKETS) buckets.removeAt(0)
        }

        val current = _state.value
        _state.value = current.copy(
            rows = current.rows + 1,
            bytes = current.bytes + line.length + 1,
            latest = second,
            missedSeconds = current.missedSeconds + missed,
            // A new list only when a bucket closes, so the graph does not redraw every second.
            buckets = if (closed != null) buckets.toList() else current.buckets,
        )

        current.endsAtMs?.let { if (nowMs >= it) stop() }
    }

    @SuppressLint("WakelockTimeout") // it has one; the lint check cannot see a computed value
    private fun holdWake(context: Context, durationMs: Long?) {
        releaseWake()
        val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SigEye:TrainRecording").apply {
            setReferenceCounted(false)
            acquire(((durationMs ?: LONGEST_MS) + 5 * 60_000L).coerceAtMost(LONGEST_MS))
        }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
