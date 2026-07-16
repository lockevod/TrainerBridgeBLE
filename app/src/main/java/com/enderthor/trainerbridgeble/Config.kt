package com.enderthor.trainerbridgeble

import android.content.Context
import com.enderthor.trainerbridgeble.correction.PowerCorrection

/** Persisted settings: the linear correction (scale as a % adjustment, offset in W) and the trainer name
 *  prefix to scan for. Read live so edits apply without a restart. */
class Config(context: Context) {
    private val p = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)

    /** Scale as a percentage ADJUSTMENT: +6 → ×1.06 (0 = no change). */
    var scaleAdjustPercent: Int
        get() = p.getInt(KEY_SCALE, 0)
        set(v) = p.edit().putInt(KEY_SCALE, v).apply()

    var offsetW: Int
        get() = p.getInt(KEY_OFFSET, 0)
        set(v) = p.edit().putInt(KEY_OFFSET, v).apply()

    /** Name prefix used to find the trainer over BLE (the Zycle advertises "Zycle..."). */
    var namePrefix: String
        get() = p.getString(KEY_PREFIX, "Zycle") ?: "Zycle"
        set(v) = p.edit().putString(KEY_PREFIX, v).apply()

    /** Write the diagnostic CSV log. */
    var loggingEnabled: Boolean
        get() = p.getBoolean(KEY_LOG, false)
        set(v) = p.edit().putBoolean(KEY_LOG, v).apply()

    /** Test mode: a synthetic trainer feeds the pipeline (no real Zycle needed). */
    var simulate: Boolean
        get() = p.getBoolean(KEY_SIM, false)
        set(v) = p.edit().putBoolean(KEY_SIM, v).apply()

    /** LIVE correction from the current scale/offset. */
    fun correction(): PowerCorrection {
        val mult = 1.0 + scaleAdjustPercent / 100.0
        return PowerCorrection(if (mult > 0.0) mult else 1.0, offsetW.toDouble())
    }

    private companion object {
        const val KEY_SCALE = "scaleAdjustPct"
        const val KEY_OFFSET = "offsetW"
        const val KEY_PREFIX = "namePrefix"
        const val KEY_LOG = "loggingEnabled"
        const val KEY_SIM = "simulate"
    }
}
