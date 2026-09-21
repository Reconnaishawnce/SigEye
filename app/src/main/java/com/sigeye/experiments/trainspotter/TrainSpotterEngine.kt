package com.sigeye.experiments.trainspotter

import com.sigeye.core.Clock
import android.content.Context
import android.util.Log
import com.sigeye.core.CsvLogger
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.ScanHealth

/**
 * The Train Spotter, with the radio taken out of it.
 *
 * Everything that used to live in its service - the aggregator, the CSV, the alert
 * cooldown - is here, driven by adverts handed in from [BleScanHub]. The service now only
 * owns the notification and the lifecycle, so the watchlist can run beside it on the same
 * scan rather than starting a second one.
 *
 * Single-threaded by contract: the service confines every call to one coroutine, so
 * nothing here needs locking.
 */
class TrainSpotterEngine(
    context: Context,
    private val onSpike: (Bin) -> Unit,
    private val onBinClosed: () -> Unit,
) {
    private val settings = SettingsStore(context)
    private val csv = CsvLogger(context)

    private val aggregator = PulseAggregator(settings.load())
    private var config: PulseConfig = aggregator.config

    private var nextCloseAtMs = 0L
    private var lastAlertMs = 0L
    private var lastPublishMs = Clock.nowMs()

    /**
     * False between an alert firing and the count coming back down.
     *
     * A Schmitt trigger, because the windows overlap: one burst is above the line in every
     * window that contains it, and firing on each of those would be one train reported
     * eight times.
     */
    private var armed = true

    fun start(nowMs: Long) {
        config = settings.load()
        aggregator.reconfigure(config)
        aggregator.reset()
        csv.pruneOlderThan(CSV_RETENTION_DAYS)
        BleScanHub.cycleMillis = config.scanCycleMillis
        lastAlertMs = 0L
        armed = true
        nextCloseAtMs = nowMs + config.binMillis

        PulseState.update {
            it.copy(
                running = true,
                bins = emptyList(),
                currentCount = 0,
                activeUnique = 0,
                baseline = 0.0,
                phase = Phase.ENROLL,
                secondsUntilArmed = config.armedAfterBins * config.binSeconds,
                armsAtMs = nowMs + config.armedAfterBins * config.binMillis,
                startedAtMs = nowMs,
                armingProgress = 0f,
                advertsPerSecond = 0.0,
                referenceRate = 0.0,
                totalAdvertisements = 0,
                scanRestarts = 0,
                config = config,
                error = null,
                csvPath = csv.currentFile?.absolutePath,
            )
        }
    }

    fun reloadConfig() {
        config = settings.load()
        aggregator.reconfigure(config)
        BleScanHub.cycleMillis = config.scanCycleMillis
        PulseState.update { it.copy(config = config) }
    }

    fun markLabel(label: String) = aggregator.markLabel(label)

    fun onAdvert(advert: Advert) {
        aggregator.observe(advert.address, advert.rssi, advert.atMs)
    }

    /** Called on the service tick. Closes a bin when one is due. */
    fun tick(nowMs: Long) {
        // A backwards clock jump would otherwise park the next boundary in the far
        // future and stall binning indefinitely.
        if (nextCloseAtMs - nowMs > config.binMillis * 2) {
            Log.w(TAG, "Clock moved backwards, resyncing bin schedule")
            nextCloseAtMs = nowMs + config.binMillis
        }
        if (nowMs >= nextCloseAtMs) {
            nextCloseAtMs = nowMs + config.binMillis
            aggregator.closeBin(nowMs)?.let { bin ->
                csv.append(bin)
                Log.d(
                    TAG,
                    "bin new=${bin.newCount} active=${bin.activeUnique} " +
                        "baseline=${"%.1f".format(bin.baseline)} " +
                        "phase=${bin.phase.csv()} spike=${bin.spike}",
                )
                onBinClosed()
            }
        }

        // The decision, on a window that ends now rather than at the last boundary. The
        // bin's own spike flag is still computed and still written to the CSV, because the
        // recorded history and the labels in it are all bin-shaped and have to stay
        // comparable with what was recorded before this existed.
        evaluateRolling(nowMs)
    }

    private fun evaluateRolling(nowMs: Long) {
        if (!aggregator.isWarm()) return
        val rolling = aggregator.rollingNew(nowMs)
        val baseline = aggregator.computeBaseline()

        if (aggregator.isSpike(rolling, baseline)) {
            if (armed) {
                armed = false
                alert(rolling, baseline, nowMs)
            }
        } else if (aggregator.hasFallenBack(rolling, baseline)) {
            armed = true
        }
    }

    fun publish(health: ScanHealth) {
        val publishedAtMs = Clock.nowMs()
        val rolling = aggregator.rollingNew(publishedAtMs)
        PulseState.update {
            it.copy(
                running = true,
                bins = aggregator.history(),
                currentCount = aggregator.currentCount(),
                activeUnique = aggregator.activeUnique(),
                baseline = aggregator.computeBaseline(),
                phase = aggregator.phase(),
                secondsUntilArmed = aggregator.secondsUntilArmed(),
                armingProgress = aggregator.armingProgress(),
                totalAdvertisements = aggregator.totalAdvertisements,
                advertsPerSecond = health.advertsPerSecond,
                sampleAdverts = aggregator.takeAdvertsSinceLastAsk(),
                sampleNew = aggregator.takeNewSinceLastAsk(),
                sampleMs = (publishedAtMs - lastPublishMs).coerceAtLeast(1L),
                sampleSeq = it.sampleSeq + 1,
                rollingNew = rolling,
                rollingSpike = aggregator.isSpike(rolling, aggregator.computeBaseline()),
                referenceRate = health.referenceRate,
                scanRestarts = health.restarts,
                error = health.error,
                csvPath = csv.currentFile?.absolutePath,
            )
        }
        lastPublishMs = publishedAtMs
    }

    fun stop() {
        csv.close()
        PulseState.update { it.copy(running = false, currentCount = 0) }
    }

    /**
     * Fires once for a burst, subject to the cooldown.
     *
     * The [Bin] handed to the notification is synthesised from the window rather than taken
     * from the history, because the window is what the decision was made on and the
     * notification should say what was actually seen. It is never added to the history.
     */
    private fun alert(count: Int, baseline: Double, nowMs: Long) {
        if (nowMs - lastAlertMs < config.alertCooldownMillis) return
        lastAlertMs = nowMs
        PulseState.update { it.copy(lastAlertMs = nowMs) }
        onSpike(
            Bin(
                startMs = nowMs - config.binMillis,
                newCount = count,
                activeUnique = aggregator.activeUnique(),
                baseline = baseline,
                spike = true,
                phase = aggregator.phase(),
                label = "",
            ),
        )
    }

    companion object {
        private const val TAG = "SigEye/Train"
        private const val CSV_RETENTION_DAYS = 30
    }
}
