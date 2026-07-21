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
