package com.enderthor.trainerbridgeble.karoo

import android.os.SystemClock
import com.enderthor.trainerbridgeble.CorrectedFeed
import com.enderthor.trainerbridgeble.FileLog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    fun connect(emitter: Emitter<DeviceEvent>) {
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        s.launch {
            // Every emitter.onNext is a BLOCKING binder call into the Karoo host: if that host dies or
            // restarts mid-ride it throws, and an uncaught exception in a coroutine takes the whole PROCESS
            // down — killing BridgeService, the trainer link and the mirror with it. Never let one escape.
            try {
                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "trainerbridge", "TrainerBridge")))
                var connected = false
                // A plain 1 Hz tick, NOT the value flows: the SDK expects ~1 Hz, and collecting the flows too
                // meant up to ~15 binder round-trips a second (the trainer pushes at 4 Hz × 3 fields) for
                // values this tick re-emits anyway. The tick also re-evaluates freshness when the trainer
                // STOPS pushing — a StateFlow emits only on change, so a stale value would latch forever.
                var consecutiveFailures = 0
                while (true) {
                    // Per-TICK catch: one transient binder hiccup must cost a sample, not the whole ride.
                    // Only a sustained failure (the host really is gone) ends the loop.
                    try {
                        val last = CorrectedFeed.lastMs.value
                        val fresh = last != 0L && SystemClock.elapsedRealtime() - last <= STALE_MS
                        // Flag flipped only AFTER the call returns: a throw here must not leave us believing
                        // the Karoo got a status it never received, which would never be re-sent.
                        if (!fresh) {
                            if (connected) { emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING)); connected = false }
                        } else {
                            if (!connected) { emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED)); connected = true }
                            CorrectedFeed.power.value?.let { emitPoint(emitter, DataType.Source.POWER, DataType.Field.POWER, it.toDouble()) }
                            CorrectedFeed.cadence.value?.let { emitPoint(emitter, DataType.Source.CADENCE, DataType.Field.CADENCE, it.toDouble()) }
                            CorrectedFeed.speedMps.value?.let { emitPoint(emitter, DataType.Source.SPEED, DataType.Field.SPEED, it) }  // m/s
                        }
                        consecutiveFailures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (++consecutiveFailures >= MAX_EMIT_FAILURES) throw e
                        FileLog.event("karoo sensor emit failed ($consecutiveFailures/$MAX_EMIT_FAILURES): ${e.message}")
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e                      // normal teardown via setCancellable — not an error
            } catch (e: Exception) {
                FileLog.event("karoo sensor emitter died: ${e.message}")
                runCatching { emitter.onError(e) }   // tell the Karoo instead of taking the process down
            }
        }
        emitter.setCancellable { s.cancel() }
    }

    private fun emitPoint(e: Emitter<DeviceEvent>, source: String, field: String, v: Double) {
        e.onNext(OnDataPoint(DataPoint(source, values = mapOf(field to v), sourceId = this.source.uid)))
    }

    companion object {
        const val STALE_MS = 3000L
        const val MAX_EMIT_FAILURES = 5   // ~5 s of a dead host: give up and tell the Karoo, don't spin
    }
}
