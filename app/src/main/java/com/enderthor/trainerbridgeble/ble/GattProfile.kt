package com.enderthor.trainerbridgeble.ble

import android.os.ParcelUuid
import java.util.UUID

/** The trainer's own advertising, captured during the scan so the mirror can re-advertise an IDENTICAL
 *  packet (same service UUIDs + manufacturer data) instead of a hand-built one — a head unit that filters
 *  on these then sees us exactly as it sees the real trainer. (Appearance/AD type 0x19 is NOT clonable on
 *  Android — no API — but head units discover by service UUID, not appearance.) */
data class AdvBlueprint(
    val serviceUuids: List<ParcelUuid>,
    val manufacturerData: List<Pair<Int, ByteArray>>,   // company id → payload
)

/** A discovered GATT characteristic to mirror: same UUID, properties, permissions, and CCCD/descriptors. */
data class CharSpec(
    val uuid: UUID,
    val properties: Int,
    val permissions: Int,
    val descriptors: List<UUID> = emptyList(),
)

/** A discovered GATT service to mirror. */
data class SvcSpec(
    val uuid: UUID,
    val primary: Boolean,
    val chars: List<CharSpec>,
)

/** Snapshot of the trainer's full GATT structure, so the peripheral can expose an identical copy. */
data class GattProfile(val services: List<SvcSpec>)

/** Which characteristics get their power field corrected, and which carry ERG control (inverse-corrected). */
object GattUuids {
    fun uuid16(v: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805f9b34fb".format(v))

    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)          // FTMS — carries power (rider read)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63) // CPS — carries power (Garmin reads here)
    val FTMS_CONTROL_POINT: UUID = uuid16(0x2AD9)        // ERG / control writes
    val MACHINE_STATUS: UUID = uuid16(0x2ADA)            // echoes accepted targets back to the app

    /** Zycle's own telemetry notification. Bestcycling subscribes to THIS as well as to 0x2AD2, and it
     *  carries the same watts — so leaving it uncorrected handed one app two different numbers for the same
     *  instant, ~8% + offset apart, from the same bridge. Whatever an app does with power, it must read the
     *  same value whichever channel it listens on. */
    val ZYCLE_TELEMETRY: UUID = UUID.fromString("beefe004-4910-473c-be46-960948c2f59c")

    /** Scan filters for the given 16-bit service UUIDs (ORed) — the controller drops everything else
     *  before it reaches the callback, so HR straps, phones and headphones never show up.
     *  0x1826 = FTMS (a trainer), 0x1818 = Cycling Power (a trainer OR a bare power meter). */
    fun scanFilters(vararg svc16: Int): List<android.bluetooth.le.ScanFilter> = svc16.map {
        android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(uuid16(it))).build()
    }

    /** GAP/GATT services the stack provides itself — never mirror these. */
    val GENERIC_ACCESS: UUID = uuid16(0x1800)
    val GENERIC_ATTRIBUTE: UUID = uuid16(0x1801)

    private val powerUuids = setOf(INDOOR_BIKE_DATA, CYCLING_POWER_MEASUREMENT)

    fun isPowerChar(uuid: UUID) = uuid in powerUuids
    fun carriesControl(uuid: UUID) = uuid == FTMS_CONTROL_POINT
    fun isStackService(uuid: UUID) = uuid == GENERIC_ACCESS || uuid == GENERIC_ATTRIBUTE
}
