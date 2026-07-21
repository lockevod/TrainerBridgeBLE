package com.enderthor.trainerbridgeble.correction

import kotlin.math.roundToInt

/**
 * Linear power correction, from a Zycle-vs-reference regression.
 *
 *   corrected = raw * scale + offset          (reading — what the apps see)
 *   rawTarget = (target - offset) / scale      (ERG — inverse, so the corrected reading matches the app's
 *                                               requested target when the trainer holds rawTarget)
 *
 * Both clamp to >= 0 (a bike computer never records negative watts).
 */
class PowerCorrection(private val scale: Double, private val offset: Double, private val invertFloorW: Int = 0) {

    init {
        require(scale > 0.0) { "scale must be > 0 (got $scale) — inverse would divide by zero / flip sign" }
    }

    /**
     * Forward: raw watts -> corrected watts.
     *
     * Raw 0 (or negative) means the rider is NOT pedalling — no power is generated, so the output is 0
     * regardless of offset. Applying `+offset` at raw 0 would fabricate phantom watts for a stopped rider.
     */
    fun correct(rawWatts: Int): Int {
        if (rawWatts <= 0) return 0
        // clamp to sint16: a garbage raw frame (e.g. 0x7FFF from a flaky link) would otherwise wrap and be
        // transmitted as a large NEGATIVE wattage
        return (scale * rawWatts + offset).roundToInt().coerceIn(0, 32767)
    }

    /**
     * Inverse (ERG): a corrected watt target -> the raw target to command on the trainer, so that after
     * correction the delivered power matches what the app asked for.
     */
    fun invert(targetWatts: Int): Int {
        if (targetWatts <= 0) return 0
        val raw = ((targetWatts - offset) / scale).roundToInt()
        // Near zero (target within the offset → honest inverse ≤ 0): the app is asking for ~nothing / a stop,
        // so command a real 0 — never fabricate resistance here.
        if (raw <= 0) return 0
        // Above that, hold a minimum. NOT a maths fix — the inverse is exact here (correct(invert(N)) == N
        // to ±1 W across the range). It is a HARDWARE floor: the trainer cannot hold a raw target that low,
        // so commanding it leaves the flywheel effectively free and ERG stops tracking. The cost is real and
        // deliberate: with offset 26 / scale 1.09 / floor 50, every target from 27 to 50 W is ridden at 50 W.
        // Only applied when a POSITIVE offset is configured — with offset ≤ 0 a fresh install must NOT turn a
        // 40 W target into 50 W. offset ≤ 0 → floorRaw 0 → no-op. Set the floor to 0 to disable.
        val floorRaw = if (offset > 0) ((invertFloorW - offset) / scale).roundToInt().coerceAtLeast(0) else 0
        return raw.coerceAtLeast(floorRaw)
    }
}
