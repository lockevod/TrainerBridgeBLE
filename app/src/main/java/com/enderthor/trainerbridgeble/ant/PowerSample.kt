package com.enderthor.trainerbridgeble.ant

/**
 * One sample of the corrected/uncorrected metrics, shared by both source profiles. A bare power meter
 * (Bicycle Power, device type 11) leaves [speedMps] null; an FE-C trainer (type 17) fills all three.
 */
data class PowerSample(
    val powerW: Int? = null,
    val cadenceRpm: Int? = null,
    val speedMps: Double? = null,
)

/** A source that receives live samples from a paired ANT device. */
interface PowerRx {
    fun start()
    fun stop()
}

/** A re-broadcaster that transmits corrected samples for a head unit to pair. */
interface PowerTx {
    fun start()
    fun stop()
    fun setLatest(sample: PowerSample)
}

/** ANT device types we can bridge. */
object AntDeviceType {
    const val BIKE_POWER = 11   // 0x0B — ANT+ Bicycle Power (power meter): power + cadence, no speed
    const val FITNESS_EQUIPMENT = 17 // 0x11 — FE-C smart trainer: power + cadence + speed
}
