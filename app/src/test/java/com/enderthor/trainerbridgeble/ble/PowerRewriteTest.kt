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

    /** The bike's own button notification (f03ee002, page 0x31): 20 x the level the press set, plus 0-16. */
    private fun press(level: Int, extra: Int = 4) = (20 * level + extra).let {
        bytes(0x31, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, it and 0xFF, it shr 8)
    }

    @Test fun zycleButtonLevel_readsTheLevelThePressSet() {
        assertEquals(24, PowerRewrite.zycleButtonLevel(bytes(0x31, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xE4, 0x01)))
        assertEquals(51, PowerRewrite.zycleButtonLevel(press(51, 16)))
        // page 0x30 is not a press and its counter does not track the level
        assertEquals(null, PowerRewrite.zycleButtonLevel(bytes(0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00)))
        assertEquals(null, PowerRewrite.zycleButtonLevel(bytes(0x31, 0xFF)))
    }

    /** 4-oct ride, 504-541 s: the app commands 173 W, the servo jumps 15 -> 23; the rider presses + nine times
     *  (23 -> 33, each with its button notification ~10 ms behind the level frame); the app re-sends 173 W
     *  and the servo drops 33 -> 24. The app must see the nine presses and NEITHER jump — the drop is what it
     *  read as the rider pressing - nine times, cutting its target and its %. */
    @Test fun levelAttribution_passesPressesAndAbsorbsTheServoWhateverItsSize() {
        val l = PowerRewrite.LevelAttribution()
        var t = 0L
        l.onLevel(15, t)
        assertEquals(40, l.shown)                          // anchored off the floor, so presses - are visible too
        assertEquals("SERVO", l.onLevel(23, t + 400))      // no press behind it
        assertEquals(40, l.shown)
        t = 10_000
        for (lvl in 24..33) {
            assertEquals("SERVO", l.onLevel(lvl, t))        // provisional until the button speaks
            assertEquals(true, l.onButton(PowerRewrite.zycleButtonLevel(press(lvl))!!, t + 10))
            t += 700
        }
        assertEquals(50, l.shown)                          // all ten presses through, none eaten
        assertEquals("SERVO", l.onLevel(24, t + 15_000))
        assertEquals(50, l.shown)                          // the -9 never reaches the app
        assertEquals(false, l.onButton(33, t + 15_010))    // a stale level is not a claim on the drop
        assertEquals(50, l.shown)
    }

    /** The two notifications are on different characteristics; now and then the button one arrives first. */
    @Test fun levelAttribution_aPressMayArriveBeforeItsLevelFrame() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(30, 0)
        assertEquals(false, l.onButton(29, 100))
        assertEquals("RIDER", l.onLevel(29, 150))
        assertEquals(39, l.shown)
    }

    /** A press is only evidence for a level change close to it: one seen later cannot claim an old jump. */
    @Test fun levelAttribution_aPressOutsideTheWindowClaimsNothing() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(30, 0)
        l.onLevel(31, 1000)
        assertEquals(false, l.onButton(31, 2000))
        assertEquals(40, l.shown)
        l.onLevel(30, 5000)
        assertEquals("SERVO", l.onLevel(31, 5100))          // and that stale press does not claim a later step to 31
        assertEquals(40, l.shown)
    }

    /** csv.1, 1785081034847: a target write, the servo jumps 7 -> 25, and 15 ms later the bike's button names 25.
     *  One notification is one press: the other 17 levels are the servo's and must not reach the app. */
    @Test fun levelAttribution_aPressNeverClaimsMoreThanOneStep() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(7, 0)
        l.onLevel(25, 1000)
        assertEquals(true, l.onButton(25, 1015))
        assertEquals(41, l.shown)
        // ...and the same when the button beats the frame
        assertEquals(false, l.onButton(10, 2000))
        assertEquals("RIDER", l.onLevel(10, 2010))
        assertEquals(40, l.shown)
    }

    /** Two presses inside one telemetry frame: each notification claims its own step, and only its own. */
    @Test fun levelAttribution_twoPressesInOneFrameNeedTwoNotifications() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(30, 0)
        l.onLevel(32, 1000)
        assertEquals(true, l.onButton(31, 1010))
        assertEquals(true, l.onButton(32, 1020))
        assertEquals(42, l.shown)
        assertEquals(false, l.onButton(31, 1030))           // nothing left to claim
        assertEquals(42, l.shown)
    }

    /** A real burst names each level it sets (31, then 32), and both may arrive on either side of the one
     *  frame that carries them. Keeping a single early press, or matching only the frame's level, lost one. */
    @Test fun levelAttribution_aBurstOfPressesIsCreditedInEitherOrder() {
        val early = PowerRewrite.LevelAttribution()
        early.onLevel(30, 0)
        assertEquals(false, early.onButton(31, 100))
        assertEquals(false, early.onButton(32, 200))
        assertEquals("RIDER", early.onLevel(32, 250))
        assertEquals(42, early.shown)

        val late = PowerRewrite.LevelAttribution()
        late.onLevel(30, 0)
        assertEquals("SERVO", late.onLevel(32, 100))
        assertEquals(true, late.onButton(31, 110))
        assertEquals(true, late.onButton(32, 120))
        assertEquals(42, late.shown)
    }

    /** The level the app sees saturates at the byte's bounds: past 0, the first press back up shows at once. */
    @Test fun levelAttribution_reversesImmediatelyAfterSaturating() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(0, 0)                                      // shown anchored at 40
        var raw = 0; var t = 1000L
        repeat(70) {                                         // 70 presses -, servo restoring headroom between
            l.onLevel(20, t); raw = 19
            l.onLevel(raw, t + 300); l.onButton(raw, t + 310)
            t += 1000
        }
        assertEquals(0, l.shown)
        l.onLevel(20, t); assertEquals(true, l.onButton(20, t + 10))
        assertEquals(1, l.shown)
    }

    /** A high first level is adopted as is: the anchor only lifts a low one off the floor. */
    @Test fun levelAttribution_keepsAHighFirstLevel() {
        val l = PowerRewrite.LevelAttribution()
        l.onLevel(75, 0)
        assertEquals(75, l.shown)
        assertEquals(null, l.onLevel(75, 100))              // nothing moved, nothing to attribute
    }

    /** ...and the byte on the wire is what stays inside a byte. */
    @Test fun theWireByteIsClampedNotTheRunningTotal() {
        val c = PowerCorrection(1.0, 0.0)
        assertEquals(0, PowerRewrite.correctZycleTelemetry(realZycleFrame(), c, -28)[12].toInt() and 0xFF)
        assertEquals(255, PowerRewrite.correctZycleTelemetry(realZycleFrame(), c, 280)[12].toInt() and 0xFF)
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
