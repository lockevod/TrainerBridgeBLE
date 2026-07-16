package com.enderthor.trainerbridgeble

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder launcher — replaced by the real monitor UI in a later task. */
class MonitorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "TrainerBridge BLE" })
    }
}
