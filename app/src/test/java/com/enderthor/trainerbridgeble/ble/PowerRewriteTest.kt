package com.enderthor.trainerbridgeble.ble

import com.enderthor.trainerbridgeble.correction.PowerCorrection
import org.junit.Assert.assertArrayEquals
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

    @Test fun inverseTargetPower_invertsSetTargetPower() {
        // Set Target Power (0x05), 200 W (0x00C8) → invert = round((200-28)/1.06)=162 (0x00A2)
        assertArrayEquals(bytes(0x05, 0xA2, 0x00), PowerRewrite.inverseTargetPower(bytes(0x05, 0xC8, 0x00), c))
    }

    @Test fun inverseTargetPower_passesOtherOpsThrough() {
        val reset = bytes(0x01)
        assertArrayEquals(reset, PowerRewrite.inverseTargetPower(reset, c))
    }
}
