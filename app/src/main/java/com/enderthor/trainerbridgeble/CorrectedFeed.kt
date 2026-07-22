package com.enderthor.trainerbridgeble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide holder of the trainer's CORRECTED values, written by the receive side (BridgeService) and
 * read by the Monitor and the karoo-ext virtual sensor. Same process → plain StateFlows, no IPC.
 * Speed is stored in m/s (the karoo-ext canonical unit). [lastMs] is SystemClock.elapsedRealtime() at the
 * last push — NOT wall-clock, which jumps when the Karoo re-syncs its time and would blink the sensor to
 * SEARCHING mid-ride. Used for the sensor's freshness (SEARCHING vs CONNECTED). Cleared when receiving stops.
 */
object CorrectedFeed {
    private val _power = MutableStateFlow<Int?>(null)
    private val _speedMps = MutableStateFlow<Double?>(null)
    private val _cadence = MutableStateFlow<Int?>(null)
    private val _lastMs = MutableStateFlow(0L)

    val power: StateFlow<Int?> = _power
    val speedMps: StateFlow<Double?> = _speedMps
    val cadence: StateFlow<Int?> = _cadence
    val lastMs: StateFlow<Long> = _lastMs

    fun push(powerW: Int?, speedMps: Double?, cadence: Int?, now: Long) {
        _power.value = powerW
        _speedMps.value = speedMps
        _cadence.value = cadence
        _lastMs.value = now
    }

    fun clear() {
        _power.value = null; _speedMps.value = null; _cadence.value = null; _lastMs.value = 0L
    }
}
