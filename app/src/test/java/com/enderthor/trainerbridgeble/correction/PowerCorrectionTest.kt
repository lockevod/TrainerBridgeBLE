package com.enderthor.trainerbridgeble.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PowerCorrectionTest {

    @Test fun appliesScaleAndOffset() {
        val c = PowerCorrection(scale = 0.95, offset = 5.0)
        assertEquals(195, c.correct(200)) // 0.95*200 + 5 = 195.0
    }

    @Test fun roundsToNearestWatt() {
        val c = PowerCorrection(scale = 0.95, offset = 0.0)
        assertEquals(190, c.correct(200)) // 190.0
        assertEquals(48, c.correct(50))   // 47.5 -> 48
    }

    @Test fun clampsNegativeToZero() {
        val c = PowerCorrection(scale = 1.0, offset = -30.0)
        assertEquals(0, c.correct(10)) // -20 -> 0
    }

    @Test fun rawZeroStaysZeroDespitePositiveOffset() {
        val c = PowerCorrection(scale = 0.95, offset = 30.0)
        assertEquals(0, c.correct(0))
        assertEquals(0, c.correct(-5))
        assertEquals(59, c.correct(30)) // 0.95*30+30 = 58.5 -> 59
    }

    @Test fun invertClampsNonPositiveTargetToZero() {
        val c = PowerCorrection(scale = 0.95, offset = 5.0)
        assertEquals(0, c.invert(0))
        assertEquals(0, c.invert(-10))
    }

    @Test fun invertIsTheLeftInverseOfCorrect() {
        val c = PowerCorrection(scale = 0.95, offset = 5.0)
        val rawTarget = c.invert(200)   // (200-5)/0.95 = 205.26 -> 205
        assertEquals(200, c.correct(rawTarget)) // 0.95*205 + 5 = 199.75 -> 200
    }

    @Test fun rejectsNonPositiveScale() {
        assertThrows(IllegalArgumentException::class.java) { PowerCorrection(scale = 0.0, offset = 0.0) }
    }
}
