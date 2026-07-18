package com.enderthor.trainerbridgeble.karoo

import com.enderthor.trainerbridgeble.CorrectedFeed
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Virtual sensor that publishes the CORRECTED power / cadence / speed (m/s) from [CorrectedFeed] so the
 *  Karoo records them natively. SEARCHING until fresh data arrives; back to SEARCHING when data goes
 *  stale (> [STALE_MS]) so the Karoo blanks the field instead of holding the last value. */
class CorrectedSource(extension: String) {
    val source by lazy {
        Device(
            extension,
            "corrected-trainer",
            listOf(DataType.Source.POWER, DataType.Source.CADENCE, DataType.Source.SPEED),
            "TrainerBridge",
        )
    }

    @Volatile private var scope: CoroutineScope? = null

    fun connect(emitter: Emitter<DeviceEvent>) {
        scope?.cancel()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        s.launch {
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
            emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "trainerbridge", "TrainerBridge")))
            var connected = false
            // Single collector -> one emitter caller (concurrent onNext on one Emitter is not guaranteed safe).
            // A 1 Hz ticker is merged in so freshness is re-evaluated (→ SEARCHING) even when the trainer
            // STOPS pushing (a StateFlow emits only on change, so without the tick a stale value would latch
            // forever). The tick also re-emits the current values, keeping the sensor alive at ~1 Hz.
            merge(
                CorrectedFeed.power.map { 0 },
                CorrectedFeed.cadence.map { 1 },
                CorrectedFeed.speedMps.map { 2 },
                flow { while (true) { emit(3); delay(1000) } },
            ).collect { which ->
                val fresh = CorrectedFeed.lastMs.value != 0L &&
                    System.currentTimeMillis() - CorrectedFeed.lastMs.value <= STALE_MS
                if (!fresh) {
                    if (connected) { connected = false; emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING)) }
                    return@collect
                }
                if (!connected) { connected = true; emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED)) }
                if (which == 0 || which == 3) CorrectedFeed.power.value?.let { emitPoint(emitter, DataType.Source.POWER, DataType.Field.POWER, it.toDouble()) }
                if (which == 1 || which == 3) CorrectedFeed.cadence.value?.let { emitPoint(emitter, DataType.Source.CADENCE, DataType.Field.CADENCE, it.toDouble()) }
                if (which == 2 || which == 3) CorrectedFeed.speedMps.value?.let { emitPoint(emitter, DataType.Source.SPEED, DataType.Field.SPEED, it) }  // m/s
            }
        }
        emitter.setCancellable { s.cancel(); if (scope === s) scope = null }
    }

    private fun emitPoint(e: Emitter<DeviceEvent>, source: String, field: String, v: Double) {
        e.onNext(OnDataPoint(DataPoint(source, values = mapOf(field to v), sourceId = this.source.uid)))
    }

    companion object { const val STALE_MS = 3000L }
}
