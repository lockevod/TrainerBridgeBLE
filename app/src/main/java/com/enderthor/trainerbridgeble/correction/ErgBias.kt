package com.enderthor.trainerbridgeble.correction

import kotlin.math.roundToInt

/**
 * Learns how far the trainer settles ABOVE the raw target we command it.
 *
 * Measured over three sessions against a power meter: the Zycle holds ~8 W (raw) more than commanded. The
 * app runs its own ERG loop on the power we report, sees itself over target, and walks its setpoint down a
 * click at a time — which is what "the intensity goes down by itself, as if the minus button pressed
 * itself" actually is. The app is not misbehaving and neither are we: it is correcting a real overshoot.
 *
 * We cannot make the trainer track better, so we command it LOWER by exactly what it overshoots and it
 * lands where the app asked. Learned rather than configured: it is a property of this particular trainer
 * (and its temperature, and its belt), so no one can be expected to type the number in.
 *
 * It cannot wind up. Commanding `target - bias` makes the trainer deliver `(target - bias) + overshoot`,
 * so the sampled error stays at `overshoot` whatever the bias is — the EMA converges on the trainer's
 * error, it does not chase its own output the way an error integrator would.
 *
 * Pure logic, no Android: the caller passes the clock (as [com.enderthor.trainerbridgeble.CorrectedFeed]
 * does) so this is unit-testable.
 */
object ErgBias {

    /** Settling time before a sample counts: the trainer ramps to a new target over several seconds, and
     *  that ramp is not the steady-state error we are after. */
    private const val SETTLE_MS = 12_000L
    /** Below this raw target the ERG floor may be holding the command above what the app asked, so
     *  "measured - commanded" no longer measures the trainer. (floorRaw is ~25 with the usual settings.) */
    private const val MIN_TARGET_W = 40
    /** ~100 samples to converge; at the trainer's 0.5 Hz that is roughly three minutes. Slow on purpose:
     *  the rider surging over target must not move it. */
    private const val ALPHA = 0.02
    private const val MAX_BIAS_W = 30
    /** One absurd sample (a dropout, a standing sprint) must not drag the average. */
    private const val MAX_SAMPLE_W = 60

    private const val OP_SET_TARGET_POWER = 0x05

    @Volatile private var bias = 0.0
    private var commandedRaw: Int? = null
    private var commandedAtMs = 0L

    /** The learned bias, in raw watts, to subtract from the ERG command. */
    val watts: Int get() = bias.roundToInt()

    /** The raw ERG target currently being measured against, or null if none is active. Diagnostics only —
     *  it is how a log answers "did the resistance button actually retire the command?". */
    val commanded: Int? get() = commandedRaw

    /** Restore what a previous session learned, so a ride starts calibrated instead of re-converging. Also
     *  retires any active command: this is a session boundary, and a command left over from the last one
     *  would read as long settled and be measured against power from a different ride. */
    @Synchronized fun seed(w: Int) {
        bias = w.coerceIn(-MAX_BIAS_W, MAX_BIAS_W).toDouble()
        forget()
    }

    /**
     * A control write on its way to the trainer — already inverse-corrected, i.e. exactly the raw watts the
     * trainer is being told to hold. Anything that ends ERG (reset, stop, resistance or simulation mode)
     * retires the active command; the rest (request control, start/resume) leave it alone.
     */
    @Synchronized fun onControl(bytes: ByteArray, nowMs: Long) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt() and 0xFF) {
            OP_SET_TARGET_POWER -> if (bytes.size >= 3) {
                val raw = ((bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)).toShort().toInt()
                if (raw != commandedRaw) { commandedRaw = raw; commandedAtMs = nowMs }
            }
            0x01, 0x04, 0x08, 0x11 -> forget()
        }
    }

    /**
     * A raw power reading from the trainer. Returns the new bias when its whole-watt value changed (so the
     * caller can persist it), null otherwise.
     */
    @Synchronized fun onPower(rawWatts: Int, nowMs: Long): Int? {
        val target = commandedRaw ?: return null
        if (target < MIN_TARGET_W) return null
        if (rawWatts <= 0) return null                       // not pedalling: says nothing about tracking
        if (nowMs - commandedAtMs < SETTLE_MS) return null    // still ramping
        val before = watts
        val sample = (rawWatts - target).coerceIn(-MAX_SAMPLE_W, MAX_SAMPLE_W)
        bias = (bias + ALPHA * (sample - bias)).coerceIn(-MAX_BIAS_W.toDouble(), MAX_BIAS_W.toDouble())
        return watts.takeIf { it != before }
    }

    /** The trainer dropped, or ERG ended: there is no active command to measure against any more. The
     *  learned bias survives — it belongs to the trainer, not to the session. */
    @Synchronized fun forget() { commandedRaw = null; commandedAtMs = 0L }
}
