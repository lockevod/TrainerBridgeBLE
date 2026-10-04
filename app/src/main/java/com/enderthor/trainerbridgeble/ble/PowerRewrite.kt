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
        return value.copyOf().also { putLe16(it, 1, c.correctCommanded(commanded)) }
    }

    /**
     * Zycle's proprietary telemetry (20 bytes): speed at 4..5, cadence at 7..8, Instantaneous Power
     * (uint16 LE) at 9..10, and the machine's own 0-50 level at 12.
     *
     * The power field carries the SAME watts as Indoor Bike Data — verified against a captured ride: it is
     * byte-identical to the 0x2AD2 field in 72% of samples and 0.3 W away in the rest, which is the gap
     * between the two notifications, not a different quantity. Correct it for that reason alone: an app
     * subscribed to both (Bestcycling is) must not be handed two different numbers for the same instant.
     *
     * The level byte is NOT watts — it is the machine's own scale (seen 0..90, not the 0-50 the panel shows)
     * — and it is the one field an app must not see follow the trainer: Bestcycling re-derives its ERG
     * target from it and rewrites the target ~60 ms after every change (118 of 125 level steps in a 60 min
     * ride did exactly that), including the changes the trainer's own servo makes settling on a target WE
     * commanded. [showLevel] is the level the mirror decided to report — see [LevelAttribution].
     *
     * Note the FTMS frame (0x2AD2) carries the machine's resistance level too, and that copy is relayed
     * untouched — it is a different scale (identical to this byte in only 37-74% of samples, mean +4 to +8)
     * so the same number cannot just be stamped on it, and Bestcycling demonstrably does not act on it: the
     * ride where this byte was frozen and that field still moved through 93 values had no target wander.
     */
    fun correctZycleTelemetry(value: ByteArray, c: PowerCorrection, showLevel: Int? = null): ByteArray {
        if (value.size < ZYCLE_TELEMETRY_LEN) return value   // an unexpected layout is passed through, not guessed at
        val raw = le16signed(value, ZYCLE_POWER_OFFSET)
        return value.copyOf().also {
            putLe16(it, ZYCLE_POWER_OFFSET, c.correct(raw))
            // The byte is what it is — one octet. The running total behind it may sit outside that
            // range after a run of absorbed steps; clamping HERE defers the rider's press to the frame
            // that brings the total back in range, instead of erasing it.
            if (showLevel != null) it[ZYCLE_LEVEL_OFFSET] = showLevel.coerceIn(0, 255).toByte()
        }
    }

    /**
     * Who moved the trainer's level: the RIDER pressing the bike's own +/- buttons (a real intensity change —
     * the app nudges its target for each step) or the SERVO settling on a target the app commanded (must stay
     * invisible, or the app reads it as presses and rewrites its target and its %).
     *
     * The bike says which: every press sends a button notification ([zycleButtonLevel]) carrying the level
     * it set, and the servo never does. 4-oct ride: 176 single steps came with one, ~10-100 ms from the level
     * frame and in either order; none of the 821 levels the servo moved did. Nor is the servo one step per
     * write, which the budget this replaced assumed: re-sending the SAME target re-derives the level from
     * scratch (33 -> 24 after the rider's nine presses), and an interval change jumps it 40+ — all of which
     * leaked to the app as presses -, dragging its target and % down and the reported level below 0, where
     * the clamped byte hid every later press.
     *
     * One notification is ONE press, whatever the step it lands next to: a press and a servo jump can share
     * a telemetry frame (7 -> 25 with the button naming 25, 15 ms after a target write), and crediting the
     * whole step handed the app 17 phantom presses. Any remainder stays claimable by a second notification.
     *
     * Synchronized: live notifications arrive on the GATT binder thread, the mirror's start-up seed on main.
     *
     * ponytail: a press whose notification never comes (~6% of them, 4-oct) is absorbed as servo. The
     * notification's counter would recover most; add that if lost presses are felt.
     */
    class LevelAttribution(private val windowMs: Long = 500) {
        var shown: Int? = null; private set          // what the app sees
        var lastRaw: Int? = null; private set        // what the trainer last reported
        private var unclaimed = 0; private var unclaimedAt = 0L   // the last step, until a press claims it
        private var pressLevel = -1; private var pressAt = 0L     // a press that beat its level frame here

        /** A telemetry frame: RIDER/SERVO for a step (SERVO may still be claimed by [onButton]), null if none. */
        @Synchronized fun onLevel(raw: Int, nowMs: Long): String? {
            val last = lastRaw
            lastRaw = raw
            // Anchored mid-range, not at the idle 0 the ride starts from: the app reads the level relatively,
            // and from 0 the rider's first presses - could not show at all.
            val s = shown ?: return null.also { shown = maxOf(raw, ANCHOR_LEVEL) }
            if (last == null || raw == last) return null
            if (pressLevel == raw && nowMs - pressAt < windowMs) {
                val step = Integer.signum(raw - last)
                shown = s + step; pressLevel = -1
                unclaimed = raw - last - step; unclaimedAt = nowMs
                return "RIDER"
            }
            unclaimed = raw - last; unclaimedAt = nowMs
            return "SERVO"
        }

        /** A button notification for [level]. @return true if it claimed one level of the step just absorbed. */
        @Synchronized fun onButton(level: Int, nowMs: Long): Boolean {
            val s = shown
            if (s != null && unclaimed != 0 && level == lastRaw && nowMs - unclaimedAt < windowMs) {
                val step = Integer.signum(unclaimed)
                shown = s + step; unclaimed -= step
                return true
            }
            pressLevel = level; pressAt = nowMs
            return false
        }

        /** The trainer link dropped: whatever it moved through meanwhile is nobody's press. */
        @Synchronized fun rebase(raw: Int) { lastRaw = raw; unclaimed = 0; pressLevel = -1 }
    }

    /** Page 0x31 of the Zycle's button characteristic: uint16 LE at 6..7 is 20 x level + 0..16 (371 of 371
     *  frames), so it names the level the press set. Page 0x30 does not track the level and is ignored. */
    fun zycleButtonLevel(value: ByteArray): Int? =
        if (value.size != 8 || (value[0].toInt() and 0xFF) != 0x31) null else le16(value, 6) / 20

    /** The level this frame reports, or null if the frame is too short to hold one. Zero is a real level
     *  (the machine reports it while idle) and must be tracked like any other, or the step back out of it
     *  reaches the app as a jump the rider never made. */
    fun zycleLevel(value: ByteArray): Int? =
        if (value.size < ZYCLE_TELEMETRY_LEN) null else value[ZYCLE_LEVEL_OFFSET].toInt() and 0xFF

    private const val ZYCLE_TELEMETRY_LEN = 20
    private const val ZYCLE_POWER_OFFSET = 9
    private const val ZYCLE_LEVEL_OFFSET = 12
    // Inside the 0-90 the machine itself has sent Bestcycling, with room for 60 net presses - and ~195 +.
    private const val ANCHOR_LEVEL = 60

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
