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
class PowerCorrection(private val scale: Double, private val offset: Double) {

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
        return (scale * rawWatts + offset).roundToInt().coerceAtLeast(0)
    }

    /**
     * Inverse (ERG): a corrected watt target -> the raw target to command on the trainer, so that after
     * correction the delivered power matches what the app asked for.
     */
    fun invert(targetWatts: Int): Int {
        if (targetWatts <= 0) return 0
        return ((targetWatts - offset) / scale).roundToInt().coerceAtLeast(0)
    }
}
