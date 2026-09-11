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
    val binsUntilWarm: Int = 0,
    val totalAdvertisements: Long = 0,
    val lastAlertMs: Long = 0,
    val lastResultMs: Long = 0,
    val scanRestarts: Int = 0,
    val config: PulseConfig = PulseConfig.DEFAULT,
    val error: String? = null,
    val csvPath: String? = null,
)

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
