package com.enderthor.trainerbridgeble

import android.content.Context
import com.enderthor.trainerbridgeble.correction.PowerCorrection

/** Persisted settings: the linear correction (scale as a % adjustment, offset in W) and the paired
 *  trainer. Read live so edits apply without a restart. */
class Config(context: Context) {
    private val p = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)

    /** Scale as a percentage ADJUSTMENT: +6 → ×1.06 (0 = no change). */
    var scaleAdjustPercent: Int
        get() = p.getInt(KEY_SCALE, 0)
        set(v) = p.edit().putInt(KEY_SCALE, v).apply()

    var offsetW: Int
        get() = p.getInt(KEY_OFFSET, 0)
        set(v) = p.edit().putInt(KEY_OFFSET, v).apply()

    /** ERG floor: below this target (W) the inverse holds the floor's own raw target instead of dropping
     *  lower (targets within the offset still command 0). 0 = always invert. Avoids the inverse collapsing
     *  toward 0 at low recovery/warmup targets and breaking the app's ERG loop. */
    var invertFloorW: Int
        get() = p.getInt(KEY_INVERT_FLOOR, 50)
        set(v) = p.edit().putInt(KEY_INVERT_FLOOR, v).apply()

    /** Raw watts the trainer settles above its ERG command, LEARNED at ride time (not a setting anyone is
     *  expected to know) and kept so the next ride starts calibrated. See
     *  [com.enderthor.trainerbridgeble.correction.ErgBias]. Clamped on the way OUT too: this value goes
     *  straight into the ERG command, and a pref written by an older/broken build must not be able to
     *  steer the trainer by 500 W. */
    var ergBiasW: Int
        get() = p.getInt(KEY_ERG_BIAS, 0).coerceIn(-ERG_BIAS_LIMIT_W, ERG_BIAS_LIMIT_W)
        set(v) = p.edit().putInt(KEY_ERG_BIAS, v.coerceIn(-ERG_BIAS_LIMIT_W, ERG_BIAS_LIMIT_W)).apply()

    /** Whether the emit half was on, so a START_STICKY restart after a process kill brings the mirror and
     *  ANT back instead of coming up receive-only mid-ride. */
    var emitEnabled: Boolean
        get() = p.getBoolean(KEY_EMIT, false)
        set(v) = p.edit().putBoolean(KEY_EMIT, v).apply()

    /** The trainer the bridge last connected to. A connected BLE peripheral stops advertising, so it can
     *  NOT appear in a scan while the bridge is using it — this is how the config screen offers it anyway. */
    var lastSeenAddress: String
        get() = p.getString(KEY_SEEN_ADDR, "") ?: ""
        set(v) = p.edit().putString(KEY_SEEN_ADDR, v).apply()
    var lastSeenName: String
        get() = p.getString(KEY_SEEN_NAME, "") ?: ""
        set(v) = p.edit().putString(KEY_SEEN_NAME, v).apply()

    /** The paired trainer's BLE address (empty = connect to the first FTMS/CPS device found). */
    var pairedAddress: String
        get() = p.getString(KEY_ADDR, "") ?: ""
        set(v) = p.edit().putString(KEY_ADDR, v).apply()

    var pairedName: String
        get() = p.getString(KEY_NAME, "") ?: ""
        set(v) = p.edit().putString(KEY_NAME, v).apply()

    /** The name WE advertise to the apps — cosmetic: it is what you pick from the app's device list, nothing
     *  more. It was long assumed that an app keys the trainer's capabilities off the name; a captured session
     *  disproved it, advertising under a DIFFERENT name and still getting the full FTMS control handshake once
     *  the mirror actually served the capability characteristics. Default matches the trainer out of habit;
     *  a distinctive name is easier to tell apart in the app's list. */
    var advertisedName: String
        get() = p.getString(KEY_ADVNAME, "") ?: ""   // blank = advertise under the device's own name
        set(v) = p.edit().putString(KEY_ADVNAME, v).apply()

    /** Write the diagnostic CSV log. */
    /** Poke the Karoo awake before its idle timer fires. `persist.hx.idle_shutdown_delay` is 600000 ms:
     *  ten minutes after the screen sleeps with no ride recording, HxStateManagerService powers the whole
     *  device off — at any battery level, and a running bridge does NOT count as activity. This dispatches
     *  the SDK's TurnScreenOn every 5 minutes, which resets that.
     *  COST, stated honestly: TurnScreenOn has no counterpart that turns it back off, so each poke costs
     *  one full device screen timeout (~1 min on a stock Karoo), not "a brief flash" — roughly a 20%
     *  screen duty cycle for as long as a trainer is linked. The screen is the biggest consumer on this
     *  device. It stops on the same leash as the wakelock guard, so an abandoned session still sleeps. */
    var keepAwake: Boolean
        get() = p.getBoolean(KEY_KEEP_AWAKE, false)
        set(v) = p.edit().putBoolean(KEY_KEEP_AWAKE, v).apply()

    /** Hold the screen on while the bridge is active. The Karoo powers ITSELF off when idle: its
     *  HxStateManagerService arms the shutdown the moment the screen sleeps, and with no ride recording
     *  it takes the device down at any battery level. Recording a ride prevents it — this is for the
     *  sessions where you don't. Costs real battery: the screen is the biggest consumer on the device. */
    var keepScreenOn: Boolean
        get() = p.getBoolean(KEY_KEEP_SCREEN, false)
        set(v) = p.edit().putBoolean(KEY_KEEP_SCREEN, v).apply()

    var loggingEnabled: Boolean
        get() = p.getBoolean(KEY_LOG, false)
        set(v) = p.edit().putBoolean(KEY_LOG, v).apply()

    /** Test mode: a synthetic trainer feeds the pipeline (no real Zycle needed). */
    var simulate: Boolean
        get() = p.getBoolean(KEY_SIM, false)
        set(v) = p.edit().putBoolean(KEY_SIM, v).apply()

    /** Re-broadcast the corrected power over ANT+ (needs an ANT USB dongle) so a Garmin head unit that
     *  pairs sensors over ANT+ gets the corrected value. */
    var antOutputEnabled: Boolean
        get() = p.getBoolean(KEY_ANT, false)   // OFF by default: without a dongle it just alarms and retries
        set(v) = p.edit().putBoolean(KEY_ANT, v).apply()

    /** ANT+ device number our FE-C broadcasts on (what the head unit pairs). Configurable so the phone and
     *  the Karoo build don't collide on the same id — set a different one per device. 1..65535. */
    var antDeviceId: Int
        get() = p.getInt(KEY_ANT_ID, 0xACDC)
        set(v) = p.edit().putInt(KEY_ANT_ID, v.coerceIn(1, 65535)).apply()

    /** Master switch: the whole app on/off. false = fully shut down, zero consumption. */
    var masterEnabled: Boolean
        get() = p.getBoolean(KEY_MASTER, true)
        set(v) = p.edit().putBoolean(KEY_MASTER, v).apply()

    /** LIVE correction from the current scale/offset. */
    fun correction(): PowerCorrection {
        val mult = 1.0 + scaleAdjustPercent / 100.0
        return PowerCorrection(if (mult > 0.0) mult else 1.0, offsetW.toDouble(), invertFloorW, ergBiasW)
    }

    private companion object {
        const val KEY_SCALE = "scaleAdjustPct"
        const val KEY_OFFSET = "offsetW"
        const val KEY_INVERT_FLOOR = "invertFloorW"
        const val KEY_ERG_BIAS = "ergBiasW"
        const val ERG_BIAS_LIMIT_W = 30   // must match ErgBias's own clamp
        const val KEY_ADDR = "pairedAddress"
        const val KEY_SEEN_ADDR = "lastSeenAddress"
        const val KEY_SEEN_NAME = "lastSeenName"
        const val KEY_NAME = "pairedName"
        const val KEY_ADVNAME = "advertisedName"
        const val KEY_LOG = "loggingEnabled"
        const val KEY_KEEP_SCREEN = "keepScreenOn"
        const val KEY_KEEP_AWAKE = "keepAwake"
        const val KEY_SIM = "simulate"
        const val KEY_ANT = "antOutput"
        const val KEY_ANT_ID = "antDeviceId"
        const val KEY_MASTER = "masterEnabled"
        const val KEY_EMIT = "emitEnabled"
    }
}
