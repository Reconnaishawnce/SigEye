package com.sigeye.experiments.trainspotter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ScanUiState(
    val running: Boolean = false,
    val bins: List<Bin> = emptyList(),
    val currentCount: Int = 0,
    val activeUnique: Int = 0,
    val baseline: Double = 0.0,
    val phase: Phase = Phase.ENROLL,
    val secondsUntilArmed: Int = 0,
    /**
     * Wall-clock moment the alerts are due to arm.
     *
     * Arming itself is counted in closed bins, and [secondsUntilArmed] is that count in
     * seconds - which means it only moves when a bin closes, once every several seconds.
     * That is the right number for the engine and a terrible one to watch: the countdown
     * on screen sat still and then jumped. This is the same deadline as a timestamp, so a
     * screen can tick it smoothly, and the two only ever disagree by less than one bin.
     */
    val armsAtMs: Long = 0,
    /** When this run began, for the same reason. */
    val startedAtMs: Long = 0,
    val armingProgress: Float = 0f,
    val totalAdvertisements: Long = 0,
    /** Live delivery rate, and the healthy rate learned after the last scan restart. */
    val advertsPerSecond: Double = 0.0,
    /**
     * Advertisements and first-ever-seen addresses since the previous publish, and how long
     * that covered.
     *
     * Here because [advertsPerSecond] is republished every ten seconds by the scan hub, so
     * anything sampling it faster than that draws the same number over and over. These are
     * counted between publishes, which is twice a second, and are therefore the only
     * numbers on this state worth plotting.
     */
    val sampleAdverts: Int = 0,
    val sampleNew: Int = 0,
    val sampleMs: Long = 0,
    /** Increments once per publish, so a reader can tell two identical samples apart. */
    val sampleSeq: Int = 0,
    val referenceRate: Double = 0.0,
    val ignoredCount: Int = 0,
    val lastAlertMs: Long = 0,
    val lastResultMs: Long = 0,
    val scanRestarts: Int = 0,
    val config: PulseConfig = PulseConfig.DEFAULT,
    val error: String? = null,
    val csvPath: String? = null,
) {
    /** Delivery has collapsed relative to what this phone managed a moment ago. */
    val starved: Boolean
        get() = running && referenceRate >= 1.0 && advertsPerSecond < referenceRate * 0.25
}

/**
 * Single shared snapshot of the scan, written by the service and read by the UI.
 * A process-wide object keeps the UI decoupled from service binding, which is
 * overkill for one screen and one service.
 */
object PulseState {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state

    fun update(block: (ScanUiState) -> ScanUiState) = _state.update(block)

    fun setError(message: String?) = _state.update { it.copy(error = message) }
}
