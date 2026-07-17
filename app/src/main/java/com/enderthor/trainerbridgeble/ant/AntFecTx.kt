package com.enderthor.trainerbridgeble.ant

import android.content.Context
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.EventCode
import com.dsi.ant.message.fromant.ChannelEventMessage
import com.dsi.ant.message.fromant.MessageFromAntType

/**
 * Broadcasts a corrected FE-C trainer on a raw MASTER channel so the Garmin pairs to it (as a smart
 * trainer) and records corrected power + passthrough cadence + speed. Bestcycling stays on the real
 * Zycle — this only feeds the Garmin.
 *
 * On every per-period TX event we load the next page: main pages 0x10 (general/speed) and 0x19
 * (trainer/power+cadence) interleaved, with common pages 0x50/0x51 every 32 messages (~8 s).
 *
 * Staleness: if no fresh sample has arrived within [STALE_MS] (dropout / rider stopped feeding), power
 * is transmitted as the FE-C invalid sentinel (0xFFF) AND the update event count / accumulated power are
 * FROZEN, so a head unit computing avg = Δaccum/Δcount records a gap rather than being dragged toward 0
 * by counted-but-invalid frames. A KNOWN real 0 W (correct() maps a non-pedalling rider to 0) is passed
 * through as a counted 0 — distinct from "no data".
 */
class AntFecTx(
    context: Context,
    deviceNumber: Int = 0xACDC,
    onState: (ok: Boolean, detail: String) -> Unit = { _, _ -> },
) : PowerTx {

    @Volatile private var latest = PowerSample()
    @Volatile private var lastUpdateMs = 0L

    /** Feed the latest corrected values; transmitted on the next matching page. */
    override fun setLatest(sample: PowerSample) {
        latest = sample
        lastUpdateMs = System.currentTimeMillis()
    }

    // TX state — mutated from BOTH the IO thread (onOpened) and the ANT callback thread (onMessage TX
    // event), so every access goes through `lock` (visibility + mutual exclusion). 4 Hz → no contention.
    private val lock = Any()
    private var pageCounter = 0
    private var eventCount = 0
    private var accumulatedPower = 0
    private var startMs = 0L
    private var lastBuildMs = 0L
    private var distanceM = 0.0

    private val link = RawAntLink(
        context = context,
        tag = "PowerProxy/FecTx",
        configure = { ch ->
            ch.assign(ChannelType.BIDIRECTIONAL_MASTER)
            ch.setChannelId(ChannelId(deviceNumber, FecPages.DEVICE_TYPE, FecPages.TRANSMISSION_TYPE))
            ch.setRfFrequency(FecPages.RF_FREQUENCY)
            ch.setPeriod(FecPages.CHANNEL_PERIOD)
        },
        onOpened = { ch ->
            synchronized(lock) {
                val now = System.currentTimeMillis()
                if (startMs == 0L) startMs = now   // keep the original session start across reopens (no
                lastBuildMs = now                  // backward elapsed-time jump); reset dt anchor only
            }
            ch.setBroadcastData(nextPage())
        },
        onMessage = { ch, type, msg ->
            if (type == MessageFromAntType.CHANNEL_EVENT) {
                val ev = runCatching { ChannelEventMessage(msg).eventCode }.getOrNull()
                if (ev == EventCode.TX) ch.setBroadcastData(nextPage())
            }
        },
        onState = onState,
    )

    /** True while the RX is actively feeding fresh samples. */
    private fun fresh(): Boolean {
        val t = lastUpdateMs
        return t != 0L && System.currentTimeMillis() - t <= STALE_MS
    }

    /**
     * Build the next FE-C page. Main pages 0x10 (even slots) and 0x19 (odd slots) interleave; a common
     * identity page (0x50 then 0x51) is inserted every 32 messages (~8 s each) so the head unit resolves
     * the sensor name promptly. 32 is even, so the 0x10/0x19 parity never drifts. Called per channel period.
     */
    private fun nextPage(): ByteArray = synchronized(lock) {
        val c = pageCounter++
        // Diagnostic (~every 25 s): proves the master is actually transmitting on-air (a TX event fires
        // each channel period). If this never climbs, the channel opened but the shared ANT radio isn't
        // putting it on-air.
        if (c % 100 == 0) com.enderthor.trainerbridgeble.FileLog.event("FecTx tx pages=$c")
        when (c % 32) {
            30 -> FecPages.buildManufacturer()
            31 -> FecPages.buildProduct()
            else -> if (c % 2 == 0) buildGeneral() else buildTrainer()
        }
    }

    // buildGeneral/buildTrainer are only ever called from nextPage(), i.e. already holding `lock`.
    // fresh() and latest are each snapshotted ONCE per page so a sample arriving mid-build can't produce
    // a self-contradictory frame (e.g. invalid power with an IN_USE state).
    private fun buildGeneral(): ByteArray {
        val now = System.currentTimeMillis()
        val fresh = fresh()
        val speed = if (fresh) latest.speedMps else null
        val dt = (now - lastBuildMs) / 1000.0
        lastBuildMs = now
        if (speed != null) distanceM += speed * dt
        val elapsedQuarterSec = ((now - startMs) / 250).toInt()
        val state = if (fresh) FecPages.FE_STATE_IN_USE else FecPages.FE_STATE_READY
        return FecPages.buildGeneralFe(elapsedQuarterSec, distanceM.toInt(), speed, state)
    }

    private fun buildTrainer(): ByteArray {
        val snap = latest
        val power = if (fresh()) snap.powerW else null   // null → 0xFFF invalid (stale or unknown)
        val cadence = if (power != null) snap.cadenceRpm else null
        val state = if (power != null) FecPages.FE_STATE_IN_USE else FecPages.FE_STATE_READY
        if (power == null) {
            // No fresh real power: FREEZE the event count + accumulator and send invalid, so the receiver
            // sees no new event (a gap) instead of counting a 0 that drags its average power down.
            return FecPages.buildSpecificTrainer(eventCount, cadence, null, accumulatedPower, state)
        }
        eventCount++
        accumulatedPower = (accumulatedPower + power) and 0xFFFF
        return FecPages.buildSpecificTrainer(eventCount, cadence, power, accumulatedPower, state)
    }

    override fun start() = link.start()
    override fun stop() = link.stop()

    private companion object {
        /** Hold the last value through a brief dropout; only after this → transmit invalid (gap). */
        const val STALE_MS = 6_000L
    }
}
