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
     * commanded. [showLevel] is the level the mirror decided to report — see [levelToShow].
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
            if (showLevel != null) it[ZYCLE_LEVEL_OFFSET] = showLevel.toByte()
        }
    }

    /** The level to report, and whether the servo step we owed is still outstanding. */
    data class ShownLevel(val level: Int, val servoStepOwed: Boolean)

    /**
     * The level to report, given the one we reported last ([shown]), the trainer's previous level
     * ([lastRaw]) and its current one ([raw]).
     *
     * The trainer moves its level for two reasons and the app must only see one: the RIDER pressing the
     * bike's own +/- buttons (a real intensity change — the app nudges its target a couple of watts per
     * step, which is what the rider asked for), and the SERVO settling on a target the app itself just
     * commanded (which must stay invisible, or the app rewrites the target it just sent and the two feed
     * each other — measured over a 60 min ride, that wander was 94 steps up against 25 down, net +27 W,
     * i.e. the app's intensity drifting on its own, not a one-way runaway).
     *
     * The discriminator is a budget, not a stopwatch: a control-point write buys the servo EXACTLY ONE level
     * step ([servoStepOwed]), because that is what it takes — over that ride 111 of 169 target writes were
     * followed by one step, 57 by none and 1 by two. Everything else is the rider. A plain time window was
     * tried first and is much worse: replayed against the same two rides it suppresses the same servo steps
     * (12 vs 13 leaked) but passes only 4 of the rider's 30 button presses instead of 15, because the app's
     * own reply to press #1 re-opens the window over presses #2-#10 (they come a median 300 ms apart).
     *
     * Deltas, not the value: with the servo's steps absorbed the reported level drifts away from the
     * machine's, which is safe only because the app reads it relatively — verified over the same ride, where
     * raw level 9 produced app targets of 68, 76 and 81 W and level 26 produced 170, 165 and 160.
     */
    fun levelToShow(shown: Int?, lastRaw: Int?, raw: Int, servoStepOwed: Boolean): ShownLevel = when {
        shown == null || lastRaw == null -> ShownLevel(raw, servoStepOwed)  // first frame: adopt what it says
        raw == lastRaw -> ShownLevel(shown, servoStepOwed)                  // no step, nothing to attribute
        servoStepOwed -> ShownLevel(shown, false)                           // the step we owed; spend it
        else -> ShownLevel((shown + (raw - lastRaw)).coerceIn(0, 255), false)   // the rider's — pass it on
    }

    /** The level this frame reports, or null if the frame is too short to hold one. Zero is a real level
     *  (the machine reports it while idle) and must be tracked like any other, or the step back out of it
     *  reaches the app as a jump the rider never made. */
    fun zycleLevel(value: ByteArray): Int? =
        if (value.size < ZYCLE_TELEMETRY_LEN) null else value[ZYCLE_LEVEL_OFFSET].toInt() and 0xFF

    private const val ZYCLE_TELEMETRY_LEN = 20
    private const val ZYCLE_POWER_OFFSET = 9
    private const val ZYCLE_LEVEL_OFFSET = 12

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
