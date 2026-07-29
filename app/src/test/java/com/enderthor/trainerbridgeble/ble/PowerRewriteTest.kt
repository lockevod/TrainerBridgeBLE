package com.enderthor.trainerbridgeble.ble

import com.enderthor.trainerbridgeble.correction.PowerCorrection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerRewriteTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    private val c = PowerCorrection(1.06, 28.0)   // +6% / +28 W

    @Test fun cyclingPower_rewritesInstantaneousPower() {
        // 0x2A63: flags 0x0020 (instantaneous power present), power sint16 LE at 2-3.
        // raw 100 → round(1.06*100+28)=134 (0x0086)
        val v = bytes(0x20, 0x00, 0x64, 0x00)
        assertArrayEquals(bytes(0x20, 0x00, 0x86, 0x00), PowerRewrite.correctCyclingPower(v, c))
    }

    @Test fun cyclingPower_rawZeroStaysZero() {
        val v = bytes(0x20, 0x00, 0x00, 0x00)
        assertArrayEquals(bytes(0x20, 0x00, 0x00, 0x00), PowerRewrite.correctCyclingPower(v, c))
    }

    @Test fun indoorBikeData_rewritesPowerField() {
        // flags 0x0044 (inst speed present + inst cadence + inst power): speed(2) cadence(2) power(2)
        // raw power 100 (0x0064) → 134 (0x0086); speed/cadence untouched
        val v = bytes(0x44, 0x00, 0x2C, 0x01, 0xB4, 0x00, 0x64, 0x00)
        assertArrayEquals(bytes(0x44, 0x00, 0x2C, 0x01, 0xB4, 0x00, 0x86, 0x00), PowerRewrite.correctIndoorBikeData(v, c))
    }

    @Test fun indoorBikeData_rewritesPowerWithResistanceFieldPresent() {
        // flags 0x0064 (speed + cadence + RESISTANCE bit5 + power): speed(2) cadence(2) resistance(2) power(2)
        // power raw 100 (0x0064) at bytes 8-9 → 134 (0x0086); resistance (bytes 6-7) untouched
        val v = bytes(0x64, 0x00, 0x2C, 0x01, 0xB4, 0x00, 0x14, 0x00, 0x64, 0x00)
        assertArrayEquals(bytes(0x64, 0x00, 0x2C, 0x01, 0xB4, 0x00, 0x14, 0x00, 0x86, 0x00), PowerRewrite.correctIndoorBikeData(v, c))
    }

    @Test fun inverseTargetPower_invertsSetTargetPower() {
        // Set Target Power (0x05), 200 W (0x00C8) → invert = round((200-28)/1.06)=162 (0x00A2)
        assertArrayEquals(bytes(0x05, 0xA2, 0x00), PowerRewrite.inverseTargetPower(bytes(0x05, 0xC8, 0x00), c))
    }

    @Test fun inverseTargetPower_passesOtherOpsThrough() {
        val reset = bytes(0x01)
        assertArrayEquals(reset, PowerRewrite.inverseTargetPower(reset, c))
    }

    /** A real frame off the trainer: 18 00 00 02 78 00 00 49 00 53 00 21 11 02 + zeros.
     *  Power (0x0053 = 83 W) sits at 9..10 and the 0x2AD2 frame from the same instant carried the same 83. */
    private fun realZycleFrame() = bytes(0x18, 0x00, 0x00, 0x02, 0x78, 0x00, 0x00, 0x49, 0x00,
        0x53, 0x00, 0x21, 0x11, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    @Test fun zycleTelemetry_correctsPowerAndNothingElse() {
        val original = realZycleFrame()
        val out = PowerRewrite.correctZycleTelemetry(original, c)
        assertEquals(20, out.size)
        assertEquals(116, (out[9].toInt() and 0xFF) or (out[10].toInt() shl 8))   // 1.06*83+28 = 116
        // every other byte verbatim — speed, cadence and the machine's own 0-50 level are not watts
        for (i in original.indices) if (i != 9 && i != 10) assertEquals("byte $i", original[i], out[i])
    }

    /** The whole point of the change: an app subscribed to both channels must see ONE number. Both frames
     *  below were captured at the same instant and both carried a raw 83 W. */
    @Test fun zycleTelemetry_agreesWithIndoorBikeData() {
        // flags 0x0874: speed, cadence, distance uint24, resistance, power at 11..12, elapsed time
        val ibd = bytes(0x74, 0x08, 0x60, 0x00, 0x90, 0x00, 0x3D, 0x06, 0x00, 0x16, 0x00, 0x53, 0x00, 0x5B, 0x02)
        val ftms = PowerRewrite.correctIndoorBikeData(ibd, c)
        val zycle = PowerRewrite.correctZycleTelemetry(realZycleFrame(), c)
        assertEquals((ftms[11].toInt() and 0xFF) or (ftms[12].toInt() shl 8),
            (zycle[9].toInt() and 0xFF) or (zycle[10].toInt() shl 8))
    }

    /** Bestcycling rewrites its ERG target ~60 ms after every level change, so the level the mirror reports
     *  is the one it decided on, not the trainer's. Power still gets corrected. */
    @Test fun zycleTelemetry_reportsTheLevelWeChose() {
        val moved = realZycleFrame().also { it[12] = 0x2C }        // the trainer's servo nudged the level
        val out = PowerRewrite.correctZycleTelemetry(moved, c, showLevel = 0x11)
        assertEquals(0x11.toByte(), out[12])
        assertEquals(116, (out[9].toInt() and 0xFF) or (out[10].toInt() shl 8))
    }

    /** Every frame carries a level, zero included — the machine reports 0 while idle and the step out of it
     *  must be differenced like any other, or leaving idle reaches the app as a jump nobody made. */
    @Test fun zycleLevel_readsTheByteIncludingZero() {
        assertEquals(0, PowerRewrite.zycleLevel(realZycleFrame().also { it[12] = 0 }))
        assertEquals(0x11, PowerRewrite.zycleLevel(realZycleFrame()))
        assertEquals(null, PowerRewrite.zycleLevel(bytes(0x18, 0x00)))
    }

    /** The whole rule, with the numbers off the 28-jul ride: adopt the first level, let our own command
     *  have ONE step, and pass every other step on as the rider's. */
    @Test fun levelToShow_spendsOneStepOnTheServoAndPassesTheRiderOn() {
        // first frame of the mirror: whatever the trainer says
        assertEquals(6, PowerRewrite.levelToShow(null, null, 6, servoStepOwed = false).level)
        // we relayed an ERG target, so the servo is owed one step: 6 -> 32 is it, and the app sees nothing
        val servo = PowerRewrite.levelToShow(6, 6, 32, servoStepOwed = true)
        assertEquals(6, servo.level)
        assertEquals(false, servo.servoStepOwed)      // spent — the next step is the rider's
        // rider presses + twice, machine 32 -> 34: the app's level steps by the same 2
        assertEquals(7, PowerRewrite.levelToShow(6, 32, 33, servoStepOwed = false).level)
        assertEquals(8, PowerRewrite.levelToShow(7, 33, 34, servoStepOwed = false).level)
        // and down again, symmetrically
        assertEquals(7, PowerRewrite.levelToShow(8, 34, 33, servoStepOwed = false).level)
    }

    /** 57 of 169 target writes moved no level at all: a frame that reports the same level must not spend
     *  the budget, or the servo's real step lands after it and reaches the app. */
    @Test fun levelToShow_anUnchangedLevelSpendsNothing() {
        val idle = PowerRewrite.levelToShow(6, 6, 6, servoStepOwed = true)
        assertEquals(6, idle.level)
        assertEquals(true, idle.servoStepOwed)
    }

    /** The budget is one, not a window: the rider's presses land a median 300 ms apart, so everything after
     *  the servo's one step must get through even while the app is still replying. */
    @Test fun levelToShow_onlyTheFirstStepAfterAWriteIsOurs() {
        val first = PowerRewrite.levelToShow(20, 20, 21, servoStepOwed = true)
        assertEquals(20, first.level)
        val second = PowerRewrite.levelToShow(first.level, 21, 22, first.servoStepOwed)
        assertEquals(21, second.level)                // the rider's, passed on immediately
    }

    /** A byte is a byte: the reported level must stay in 0..255 however far the deltas push it. */
    @Test fun levelToShow_staysInsideAByte() {
        assertEquals(0, PowerRewrite.levelToShow(2, 40, 10, servoStepOwed = false).level)       // would go negative
        assertEquals(255, PowerRewrite.levelToShow(250, 10, 40, servoStepOwed = false).level)   // would overflow
    }

    @Test fun zycleTelemetry_passesAnUnexpectedLayoutThrough() {
        val short = bytes(0x18, 0x00, 0x00, 0x02, 0x78)   // never seen, but must not be rewritten blindly
        assertArrayEquals(short, PowerRewrite.correctZycleTelemetry(short, c))
    }
}

/** The trainer's real Indoor Bike Data layout (flags 0x0874: speed, cadence, distance uint24, resistance,
 *  power, elapsed) — the simulator emits the same one. Power sits at offset 11, and a parser that skipped
 *  the uint24 distance would silently correct the wrong two bytes. */
class IndoorBikeDataLayoutTest {

    private fun realLayoutPacket(power: Int) = byteArrayOf(
        0x74, 0x08,                     // flags
        0x10, 0x0E,                     // inst speed  36.00 km/h
        0x2E, 0x01,                     // inst cadence 151 → 0x012E = 302 half-rpm
        0x40, 0x1F, 0x00,               // total distance uint24 = 8000 m
        0x05, 0x00,                     // resistance level 5
        (power and 0xFF).toByte(), ((power shr 8) and 0xFF).toByte(),
        0x2C, 0x01,                     // elapsed time 300 s
    )

    @Test fun correctsPowerPastTheUint24Distance() {
        val corrected = PowerRewrite.correctIndoorBikeData(realLayoutPacket(200), PowerCorrection(1.0, 50.0))
        assertEquals(15, corrected.size)
        assertEquals(250, (corrected[11].toInt() and 0xFF) or ((corrected[12].toInt() and 0xFF) shl 8))
        // every other byte untouched — a wrong offset would show up here
        val original = realLayoutPacket(200)
        for (i in original.indices) if (i != 11 && i != 12) assertEquals("byte $i", original[i], corrected[i])
    }
}

/** CPS event time must be the time of the revolution, not one tick per revolution: a consumer computes
 *  cadence as Δrevs / Δtime, so sampling a free-running clock is the difference between 87 and 240 rpm. */
class CpsEventClockTest {

    @Test fun cadenceFromEventClockMatchesTheSimulatedCadence() {
        val tickMs = 250; val cadenceRpm = 87.0
        var turns = 0.0; var revs = 0; var clock1024 = 0; var evt = 0
        var firstEvt = -1; var firstRev = 0
        repeat(240) {                                    // 60 s at 4 Hz
            turns += cadenceRpm / 60.0 * (tickMs / 1000.0)
            clock1024 += tickMs * 1024 / 1000
            if (turns.toInt() != revs) {
                revs = turns.toInt(); evt = clock1024 and 0xFFFF
                if (firstEvt < 0) { firstEvt = evt; firstRev = revs }
            }
        }
        val rpm = (revs - firstRev) * 60.0 / ((evt - firstEvt) / 1024.0)
        assertEquals(cadenceRpm, rpm, 1.0)
    }
}
