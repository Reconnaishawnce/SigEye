package com.sigeye.core.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** What the room is doing. */
enum class MotionState(val label: String) {
    CALIBRATING("Learning the room"),
    QUIET("Still"),
    STIRRING("Something moved"),
    MOTION("Motion"),
}

/** One device being used as a reference link. */
data class Reference(
    val address: String,
    val baselineMean: Double,
    val baselineSigma: Double,
    val samples: Int,
    val packetsPerSecond: Double,
    /** Current deviation from its own baseline, in sigmas. */
    val score: Double = 0.0,
    val live: Boolean = true,
)

data class MotionReading(
    val state: MotionState,
    /** Highest deviation across references, in sigmas. */
    val score: Double,
    /** How many references currently exceed the threshold. Agreement is the point. */
    val disturbed: Int,
    val referenceCount: Int,
    val liveReferences: Int,
    val threshold: Double,
    val calibrationProgress: Float,
    val references: List<Reference>,
)

/** One detected disturbance. */
data class MotionEvent(
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val peakScore: Double,
    val peakDisturbed: Int,
) {
    fun durationMs(nowMs: Long): Long = (endedAtMs ?: nowMs) - startedAtMs
}

data class MotionConfig(
    val calibrationSeconds: Int = 25,
    /** Sliding window the live statistics are computed over. */
    val windowMs: Long = 2_500L,
    /** Deviation, in sigmas, that counts as disturbed. */
    val sensitivity: Double = 3.5,
    /** References that must agree before an event fires. */
    val minAgreement: Int = 2,
    /** Consecutive ticks above threshold before firing, to swallow single glitches. */
    val ticksToFire: Int = 2,
    /** A device advertising slower than this cannot carry a link. */
    val minPacketsPerSecond: Double = 1.5,
    /** A device whose own baseline is this noisy is probably moving itself. */
    val maxBaselineSigma: Double = 5.0,
    /** Sigma never goes below this, so a perfectly steady link cannot divide by zero. */
    val sigmaFloor: Double = 1.2,
    /** Drop a reference unheard for this long. */
    val staleMs: Long = 8_000L,
)

/**
 * Detects a person moving through a radio path.
 *
 * The mechanism that matters is not absorption but **multipath disturbance**. A body in a
 * room reflects 2.4 GHz, and moving it changes how those reflections add and cancel at the
 * receiver - so the signal becomes *unstable* long before it becomes weak. Watching the
 * level alone would miss someone walking across a room while catching every battery sag;
 * watching the variance catches the walk.
 *
 * Robustness comes from three decisions:
 *
 *  - **Several references, and they must agree.** One device glitching, re-pairing or
 *    having its battery dip is normal. Two independent links disturbed at the same instant
 *    is a room event. The agreement requirement is what separates the two.
 *  - **References are chosen, not assumed.** Calibration keeps only devices that are
 *    chatty enough to carry a link and steady enough to be sitting still. A phone in
 *    someone's pocket fails both and is excluded.
 *  - **The baseline adapts while quiet and freezes while disturbed.** Otherwise it would
 *    slowly learn to accept the intruder standing in the doorway.
 *
 * Pure and Android-free.
 */
class MotionDetector(var config: MotionConfig = MotionConfig()) {

    private class Track {
        val window = ArrayDeque<Pair<Long, Int>>()
        var baselineMean = 0.0
        var baselineSigma = 0.0
        var calibrated = false
        var lastSeenMs = 0L
        var totalSamples = 0
        var firstSeenMs = 0L
        var score = 0.0
    }

    private val tracks = HashMap<String, Track>()
    private val events = ArrayDeque<MotionEvent>()

    private var calibrationStartMs: Long? = null
    private var calibrationEndMs: Long? = null
    private var state = MotionState.CALIBRATING
    private var consecutiveHot = 0
    private var currentEvent: MotionEvent? = null

    fun startCalibration(nowMs: Long) {
        tracks.clear()
        events.clear()
        calibrationStartMs = nowMs
        calibrationEndMs = null
        state = MotionState.CALIBRATING
        consecutiveHot = 0
        currentEvent = null
    }

    fun reset() {
        tracks.clear()
        events.clear()
        calibrationStartMs = null
        calibrationEndMs = null
        state = MotionState.CALIBRATING
        consecutiveHot = 0
        currentEvent = null
    }

    fun eventLog(): List<MotionEvent> = events.toList().asReversed()

    fun observe(address: String, rssi: Int, nowMs: Long) {
        if (calibrationStartMs == null) return
        val track = tracks.getOrPut(address) { Track().apply { firstSeenMs = nowMs } }
        track.window.addLast(nowMs to rssi)
        track.lastSeenMs = nowMs
        track.totalSamples++
        while (track.window.isNotEmpty() && nowMs - track.window.first().first > config.windowMs) {
            track.window.removeFirst()
        }
    }

    /**
     * Advances the detector. Call on a timer, not per packet - the statistics are over a
     * window and only mean anything once per window step.
     */
    fun tick(nowMs: Long): MotionReading {
        val started = calibrationStartMs ?: return idleReading()

        if (calibrationEndMs == null) {
            val elapsed = nowMs - started
            if (elapsed >= config.calibrationSeconds * 1_000L) {
                finishCalibration(nowMs)
            } else {
                return MotionReading(
                    state = MotionState.CALIBRATING,
                    score = 0.0,
                    disturbed = 0,
                    referenceCount = 0,
                    liveReferences = 0,
                    threshold = config.sensitivity,
                    calibrationProgress =
                        (elapsed.toFloat() / (config.calibrationSeconds * 1_000f))
                            .coerceIn(0f, 1f),
                    references = emptyList(),
                )
            }
        }

        val references = mutableListOf<Reference>()
        var peak = 0.0
        var disturbed = 0
        var liveCount = 0

        tracks.entries.forEach { (address, track) ->
            if (!track.calibrated) return@forEach
            val live = nowMs - track.lastSeenMs <= config.staleMs && track.window.size >= 3
            if (live) liveCount++

            val score = if (live) scoreOf(track) else 0.0
            track.score = score
            if (score > peak) peak = score
            if (live && score >= config.sensitivity) disturbed++

            references.add(
                Reference(
                    address = address,
                    baselineMean = track.baselineMean,
                    baselineSigma = track.baselineSigma,
                    samples = track.totalSamples,
                    packetsPerSecond = rateOf(track, nowMs),
                    score = score,
                    live = live,
                ),
            )
        }

        val agreementNeeded = minOf(config.minAgreement, maxOf(1, liveCount))
        val hot = disturbed >= agreementNeeded && peak >= config.sensitivity
        if (hot) consecutiveHot++ else consecutiveHot = 0

        val previousState = state
        state = when {
            liveCount == 0 -> MotionState.QUIET
            consecutiveHot >= config.ticksToFire -> MotionState.MOTION
            hot || peak >= config.sensitivity * 0.6 -> MotionState.STIRRING
            else -> MotionState.QUIET
        }

        updateEvents(previousState, nowMs, peak, disturbed)

        // Adapt only while genuinely quiet, or the baseline learns to accept whoever is
        // standing in the doorway.
        if (state == MotionState.QUIET) adaptBaselines()

        return MotionReading(
            state = state,
            score = peak,
            disturbed = disturbed,
            referenceCount = references.size,
            liveReferences = liveCount,
            threshold = config.sensitivity,
            calibrationProgress = 1f,
            references = references.sortedByDescending { it.score },
        )
    }

    /**
     * Deviation in sigmas, taking the worse of two symptoms.
     *
     * A level shift is someone standing in the path; a jitter rise is someone walking
     * through it. Both are motion, and neither alone catches the other.
     */
    private fun scoreOf(track: Track): Double {
        val values = track.window.map { it.second.toDouble() }
        if (values.size < 3) return 0.0
        val mean = values.average()
        val sigma = track.baselineSigma.coerceAtLeast(config.sigmaFloor)

        val levelShift = abs(mean - track.baselineMean) / sigma

        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val windowSigma = sqrt(variance)
        // How many times noisier than its calm self, expressed on the same scale.
        val jitter = ((windowSigma / sigma) - 1.0) * 2.0

        return maxOf(levelShift, jitter).coerceAtLeast(0.0)
    }

    private fun rateOf(track: Track, nowMs: Long): Double {
        val span = (track.lastSeenMs - track.firstSeenMs).coerceAtLeast(1L)
        return track.totalSamples * 1000.0 / span
    }

    /** Keeps only devices chatty enough and steady enough to carry a reference link. */
    private fun finishCalibration(nowMs: Long) {
        calibrationEndMs = nowMs
        val keep = HashMap<String, Track>()

        tracks.forEach { (address, track) ->
            val rate = rateOf(track, nowMs)
            if (rate < config.minPacketsPerSecond) return@forEach
            if (track.totalSamples < 8) return@forEach

            val values = track.window.map { it.second.toDouble() }
            if (values.size < 3) return@forEach
            val mean = values.average()
            val sigma = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            // A device whose own signal was already wandering is probably moving.
            if (sigma > config.maxBaselineSigma) return@forEach

            track.baselineMean = mean
            track.baselineSigma = sigma
            track.calibrated = true
            keep[address] = track
        }

        tracks.clear()
        tracks.putAll(keep)
        state = if (tracks.isEmpty()) MotionState.QUIET else MotionState.QUIET
    }

    /** Slow drift correction, so temperature and battery sag do not accumulate. */
    private fun adaptBaselines() {
        tracks.values.forEach { track ->
            if (!track.calibrated || track.window.size < 3) return@forEach
            val values = track.window.map { it.second.toDouble() }
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            track.baselineMean += (mean - track.baselineMean) * ADAPT
            track.baselineSigma += (sqrt(variance) - track.baselineSigma) * ADAPT
        }
    }

    private fun updateEvents(
        previous: MotionState,
        nowMs: Long,
        peak: Double,
        disturbed: Int,
    ) {
        if (state == MotionState.MOTION) {
            val open = currentEvent
            if (open == null) {
                currentEvent = MotionEvent(nowMs, null, peak, disturbed)
            } else {
                currentEvent = open.copy(
                    peakScore = maxOf(open.peakScore, peak),
                    peakDisturbed = maxOf(open.peakDisturbed, disturbed),
                )
            }
        } else if (previous == MotionState.MOTION && state != MotionState.MOTION) {
            currentEvent?.let { open ->
                events.addLast(open.copy(endedAtMs = nowMs))
                while (events.size > MAX_EVENTS) events.removeFirst()
            }
            currentEvent = null
        }
    }

    private fun idleReading() = MotionReading(
        state = MotionState.CALIBRATING,
        score = 0.0,
        disturbed = 0,
        referenceCount = 0,
        liveReferences = 0,
        threshold = config.sensitivity,
        calibrationProgress = 0f,
        references = emptyList(),
    )

    private companion object {
        const val ADAPT = 0.02
        const val MAX_EVENTS = 200
    }
}
