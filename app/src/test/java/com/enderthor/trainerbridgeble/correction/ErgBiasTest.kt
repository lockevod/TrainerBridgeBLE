package com.enderthor.trainerbridgeble.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErgBiasTest {

    /** ErgBias is a singleton — every test starts from a known state. */
    @Before fun reset() { ErgBias.seed(0); ErgBias.forget() }

    private fun setTargetPower(w: Int) = byteArrayOf(0x05, (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())

    /** Feed `seconds` of a trainer that settles `overshoot` W above whatever it is commanded. */
    private fun ride(commandedRaw: Int, overshoot: Int, seconds: Int, startMs: Long = 0L): Long {
        ErgBias.onControl(setTargetPower(commandedRaw), startMs)
        var t = startMs
        repeat(seconds) { t += 2_000; ErgBias.onPower(commandedRaw + overshoot, t) }
        return t
    }

    @Test fun learnsTheTrainersOvershoot() {
        ride(commandedRaw = 150, overshoot = 8, seconds = 400)
        assertEquals(8, ErgBias.watts)
    }

    /**
     * The property the whole design rests on: the bias converges on the trainer's error and STAYS there,
     * because commanding lower does not change the error we sample. An error integrator would run away.
     */
    @Test fun doesNotWindUpOnceApplied() {
        val c = PowerCorrection(scale = 1.05, offset = 24.0, ergBiasW = 8)
        val target = 200
        val commanded = c.invert(target)              // already 8 W lower than the honest inverse
        ride(commandedRaw = commanded, overshoot = 8, seconds = 600)
        assertEquals(8, ErgBias.watts)                // still 8 — not 16, not climbing
        // ...and the rider gets what the app asked for: the trainer delivers commanded + 8.
        assertEquals(target, c.correct(commanded + 8))
    }

    /**
     * Regression: subtracting the bias BEFORE the "is the app asking for nothing" check widened that
     * window by the bias, so a 30 W recovery target commanded 0 — flywheel free — instead of holding the
     * ERG floor. The bias may push the command down, never past the floor and never off a cliff to zero.
     */
    @Test fun theErgBiasNeverCostsTheFloor() {
        val plain = PowerCorrection(scale = 1.08, offset = 25.0, invertFloorW = 50)
        val biased = PowerCorrection(scale = 1.08, offset = 25.0, invertFloorW = 50, ergBiasW = 8)
        for (target in 1..400) {
            val p = plain.invert(target); val b = biased.invert(target)
            if (p == 0) assertEquals("target $target: zero must stay zero", 0, b)
            else assertTrue("target $target: floor lost ($p -> $b)", b >= 23)   // floorRaw = (50-25)/1.08
        }
        assertEquals(0, biased.invert(25))    // within the offset: still a real stop
        assertEquals(23, biased.invert(30))   // recovery target: still holds the floor
        assertEquals(154, biased.invert(200)) // well above it: the full bias applies
    }

    @Test fun ignoresTheRampToANewTarget() {
        ErgBias.onControl(setTargetPower(150), 0L)
        repeat(5) { ErgBias.onPower(60, 2_000L * it) }   // first 10 s: still spinning up, way under target
        assertEquals(0, ErgBias.watts)
    }

    @Test fun ignoresACoastingRiderAndLowTargets() {
        ErgBias.onControl(setTargetPower(150), 0L)
        repeat(100) { ErgBias.onPower(0, 20_000L + 2_000L * it) }   // stopped pedalling
        assertEquals(0, ErgBias.watts)
        ride(commandedRaw = 20, overshoot = 15, seconds = 200)      // below the ERG floor: means nothing
        assertEquals(0, ErgBias.watts)
    }

    @Test fun stopEndsTheMeasurement() {
        ErgBias.onControl(setTargetPower(150), 0L)
        ErgBias.onControl(byteArrayOf(0x08), 1_000L)                // FTMS Stop/Pause
        repeat(200) { ErgBias.onPower(300, 20_000L + 2_000L * it) }
        assertEquals(0, ErgBias.watts)
    }

    @Test fun oneAbsurdSampleBarelyMovesIt() {
        ride(commandedRaw = 150, overshoot = 8, seconds = 400)
        ErgBias.onPower(150 + 900, 2_000_000L)                      // a garbage frame / standing sprint
        assertTrue("a single outlier must not swing the bias", ErgBias.watts in 8..10)
    }

    @Test fun staysWithinItsClamp() {
        ride(commandedRaw = 150, overshoot = 500, seconds = 2000)
        assertEquals(30, ErgBias.watts)
    }

    @Test fun seedSurvivesUntilNewEvidence() {
        ErgBias.seed(9)
        assertEquals(9, ErgBias.watts)
        ErgBias.forget()
        assertEquals(9, ErgBias.watts)   // the trainer dropping does not unlearn the trainer
    }
}
