package com.enderthor.trainerbridgeble.ble

import com.enderthor.trainerbridgeble.correction.PowerCorrection

/**
 * Pure helpers: correct the power field in BLE power notifications, and inverse-correct an ERG target in
 * a Control Point write. Only power changes; every other byte is preserved verbatim.
 */
object PowerRewrite {

    /** Cycling Power Measurement (0x2A63): flags uint16 LE at 0-1, Instantaneous Power sint16 LE at 2-3. */
    fun correctCyclingPower(value: ByteArray, c: PowerCorrection): ByteArray {
        if (value.size < 4) return value
        val raw = le16signed(value, 2)
        return value.copyOf().also { putLe16(it, 2, c.correct(raw)) }
    }

    /** Indoor Bike Data (0x2AD2): flags uint16 LE at 0-1; fields follow per flag bits. Instantaneous Power
     *  (sint16) is present when bit6 set; its offset depends on the earlier optional fields. */
    fun correctIndoorBikeData(value: ByteArray, c: PowerCorrection): ByteArray {
        if (value.size < 2) return value
        val flags = le16(value, 0)
        var off = 2
        if (flags and (1 shl 0) == 0) off += 2          // bit0 == 0 → Instantaneous Speed present
        if (flags and (1 shl 1) != 0) off += 2          // Average Speed
        if (flags and (1 shl 2) != 0) off += 2          // Instantaneous Cadence
        if (flags and (1 shl 3) != 0) off += 2          // Average Cadence
        if (flags and (1 shl 4) != 0) off += 3          // Total Distance (uint24)
        if (flags and (1 shl 5) != 0) off += 2          // Resistance Level
        if (flags and (1 shl 6) != 0) {                 // Instantaneous Power (sint16) — the field to correct
            if (off + 2 > value.size) return value
            val raw = le16signed(value, off)
            return value.copyOf().also { putLe16(it, off, c.correct(raw)) }
        }
        return value
    }

    /** FTMS Machine Status (0x2ADA) op 0x08 "Target Power Changed" echoes the watts the trainer was
     *  commanded — which we inverse-corrected. Correct it forward so the app reads back the wattage it will
     *  actually SEE. Below the ERG floor that is not the target it asked for: the floor lifts the command,
     *  deliberately, and this echo tells the truth about it. */
    fun correctMachineStatusTargetPower(value: ByteArray, c: PowerCorrection): ByteArray {
        if (value.size < 3 || (value[0].toInt() and 0xFF) != 0x08) return value
        val commanded = le16signed(value, 1)
        return value.copyOf().also { putLe16(it, 1, c.correct(commanded)) }
    }

    /** FTMS Control Point Set Target Power (0x05, uint16 W LE) → inverse-correct the watts. Other ops pass. */
    fun inverseTargetPower(write: ByteArray, c: PowerCorrection): ByteArray {
        if (write.size < 3 || (write[0].toInt() and 0xFF) != 0x05) return write
        val watts = le16signed(write, 1)   // FTMS: the Set Target Power parameter is sint16
        return write.copyOf().also { putLe16(it, 1, c.invert(watts)) }
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()
    private fun putLe16(b: ByteArray, i: Int, v: Int) { b[i] = (v and 0xFF).toByte(); b[i + 1] = ((v shr 8) and 0xFF).toByte() }
}
