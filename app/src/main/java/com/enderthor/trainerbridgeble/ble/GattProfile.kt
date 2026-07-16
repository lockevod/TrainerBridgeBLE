package com.enderthor.trainerbridgeble.ble

import java.util.UUID

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

    /** GAP/GATT services the stack provides itself — never mirror these. */
    val GENERIC_ACCESS: UUID = uuid16(0x1800)
    val GENERIC_ATTRIBUTE: UUID = uuid16(0x1801)

    private val powerUuids = setOf(INDOOR_BIKE_DATA, CYCLING_POWER_MEASUREMENT)

    fun isPowerChar(uuid: UUID) = uuid in powerUuids
    fun carriesControl(uuid: UUID) = uuid == FTMS_CONTROL_POINT
    fun isStackService(uuid: UUID) = uuid == GENERIC_ACCESS || uuid == GENERIC_ATTRIBUTE
}
