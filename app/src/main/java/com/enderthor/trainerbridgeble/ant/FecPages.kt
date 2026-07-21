package com.enderthor.trainerbridgeble.ant

import kotlin.math.roundToInt

/**
 * ANT+ FE-C (Fitness Equipment Control) page layouts for a stationary trainer.
 * RX parse: General FE Data (0x10 → speed) and Specific Trainer Data (0x19 → power, cadence).
 * TX build: the same two main pages plus common manufacturer/product pages, corrected power in 0x19.
 *
 * Channel: device type 17, RF 57, period 8192, transmission type 5.
 */
object FecPages {
    const val DEVICE_TYPE = 17          // 0x11 — Fitness Equipment
    const val RF_FREQUENCY = 57         // 2457 MHz
    const val CHANNEL_PERIOD = 8192     // ~4 Hz
    const val TRANSMISSION_TYPE = 5

    const val PAGE_GENERAL_FE = 0x10
    const val PAGE_SPECIFIC_TRAINER = 0x19
    const val PAGE_MANUFACTURER = 0x50
    const val PAGE_PRODUCT = 0x51
    const val PAGE_BASIC_RESISTANCE = 0x30
    const val PAGE_TARGET_POWER = 0x31

    private const val EQUIP_TYPE_TRAINER = 25
    const val FE_STATE_READY = 2        // powered, no rider input yet / stale
    const val FE_STATE_IN_USE = 3       // actively producing data
    private const val INVALID_U8 = 0xFF
    private const val POWER_INVALID_12BIT = 0xFFF

    // ── RX parsing ────────────────────────────────────────────────────────────────────────────────

    /** General FE Data (0x10): instantaneous speed in bytes 4-5 (0.001 m/s). */
    fun parseGeneralFe(p: ByteArray): Double? {
        if (p.size < 8 || (p[0].toInt() and 0xFF) != PAGE_GENERAL_FE) return null
        val raw = u8(p, 4) or (u8(p, 5) shl 8)
        if (raw == 0xFFFF) return null
        return raw * 0.001
    }

    /** Common page 0x50: manufacturer id (bytes 4-5 LE) + model number (bytes 6-7 LE). */
    fun parseManufacturer(p: ByteArray): Pair<Int, Int>? {
        if (p.size < 8 || (p[0].toInt() and 0xFF) != PAGE_MANUFACTURER) return null
        return Pair(u8(p, 4) or (u8(p, 5) shl 8), u8(p, 6) or (u8(p, 7) shl 8))
    }

    /**
     * Request Data Page (common page 70 / 0x46): asks the device to broadcast [requestedPage] now,
     * [txCount] times — so the scan resolves the name in ~1s instead of waiting for the slow
     * background rotation. Sent with startSendAcknowledgedData over a BIDIRECTIONAL_SLAVE channel.
     */
    fun requestDataPage(requestedPage: Int, txCount: Int = 4): ByteArray = byteArrayOf(
        70, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        (txCount and 0x7F).toByte(), (requestedPage and 0xFF).toByte(), 0x01,
    )

    /** Specific Trainer Data (0x19): instantaneous power (12-bit) + cadence. */
    fun parseSpecificTrainer(p: ByteArray): PowerSample? {
        if (p.size < 8 || (p[0].toInt() and 0xFF) != PAGE_SPECIFIC_TRAINER) return null
        val cadenceRaw = u8(p, 2)
        val power = u8(p, 5) or ((u8(p, 6) and 0x0F) shl 8)
        return PowerSample(
            powerW = if (power == POWER_INVALID_12BIT) null else power,
            cadenceRpm = if (cadenceRaw == INVALID_U8) null else cadenceRaw,
            speedMps = null,
        )
    }

    // ── TX building ───────────────────────────────────────────────────────────────────────────────

    /** General FE Data (0x10): equipment type, elapsed time, distance, speed, state. */
    fun buildGeneralFe(elapsedQuarterSec: Int, distanceM: Int, speedMps: Double?, feState: Int): ByteArray {
        // 0xFFFF is the FE-C "invalid" sentinel — without it a dropout is recorded as a real 0.000 m/s
        val speed = speedMps?.let { (it * 1000.0).roundToInt().coerceIn(0, 0xFFFE) } ?: 0xFFFF
        return byteArrayOf(
            PAGE_GENERAL_FE.toByte(),
            EQUIP_TYPE_TRAINER.toByte(),
            (elapsedQuarterSec and 0xFF).toByte(),
            (distanceM and 0xFF).toByte(),
            (speed and 0xFF).toByte(),
            ((speed shr 8) and 0xFF).toByte(),
            INVALID_U8.toByte(),                                   // heart rate: not provided
            (((feState shl 4) or 0x04) and 0xFF).toByte(),         // FE state + distance-enabled bit
        )
    }

    /**
     * Specific Trainer Data (0x19): corrected power, cadence, accumulated power.
     * instantPowerW null → the 12-bit invalid sentinel 0xFFF (no data / rider not tracked), so the head
     * unit records a gap instead of a fabricated 0 W. A real, known 0 W (rider stopped) is passed as 0.
     */
    fun buildSpecificTrainer(eventCount: Int, cadenceRpm: Int?, instantPowerW: Int?, accumulatedPowerW: Int, feState: Int): ByteArray {
        val power = instantPowerW?.coerceIn(0, 0xFFE) ?: POWER_INVALID_12BIT
        return byteArrayOf(
            PAGE_SPECIFIC_TRAINER.toByte(),
            (eventCount and 0xFF).toByte(),
            ((cadenceRpm ?: INVALID_U8) and 0xFF).toByte(),
            (accumulatedPowerW and 0xFF).toByte(),
            ((accumulatedPowerW shr 8) and 0xFF).toByte(),
            (power and 0xFF).toByte(),
            ((power shr 8) and 0x0F).toByte(),                     // bits 0-3 power MSN; bits 4-7 trainer status = 0
            ((feState shl 4) and 0xFF).toByte(),                   // flags = 0, FE state
        )
    }

    /** Common page 0x50 — Manufacturer's Identification. 0x00FF = development/unregistered manufacturer. */
    fun buildManufacturer(manufacturerId: Int = 0x00FF, modelNumber: Int = 1): ByteArray = byteArrayOf(
        PAGE_MANUFACTURER.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        1,                                                          // HW revision
        (manufacturerId and 0xFF).toByte(), ((manufacturerId shr 8) and 0xFF).toByte(),
        (modelNumber and 0xFF).toByte(), ((modelNumber shr 8) and 0xFF).toByte(),
    )

    /** Common page 0x51 — Product Information. */
    fun buildProduct(serialNumber: Int = 1): ByteArray = byteArrayOf(
        PAGE_PRODUCT.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        1,                                                          // SW revision (main)
        (serialNumber and 0xFF).toByte(), ((serialNumber shr 8) and 0xFF).toByte(),
        ((serialNumber shr 16) and 0xFF).toByte(), ((serialNumber shr 24) and 0xFF).toByte(),
    )

    /** Target Power control page (0x31): bytes 6-7 = target power in 0.25 W units (LE). Sent as
     *  acknowledged data from a bidirectional slave to put an FE-C trainer into ERG at [targetWatts]. */
    fun buildTargetPower(targetWatts: Int): ByteArray {
        val q = (targetWatts * 4).coerceIn(0, 0xFFFF)   // 0.25 W units
        return byteArrayOf(
            PAGE_TARGET_POWER.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), (q and 0xFF).toByte(), ((q shr 8) and 0xFF).toByte(),
        )
    }

    /** Basic Resistance control page (0x30): byte 7 = total resistance in 0.5 % units (0-200 = 0-100 %).
     *  [percent] is the target braking resistance as a percentage. */
    fun buildBasicResistance(percent: Double): ByteArray {
        val units = (percent * 2).roundToInt().coerceIn(0, 200)   // 0.5 % units
        return byteArrayOf(
            PAGE_BASIC_RESISTANCE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), (units and 0xFF).toByte(),
        )
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
}
