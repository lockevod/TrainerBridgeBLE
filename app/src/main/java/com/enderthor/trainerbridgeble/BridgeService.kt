package com.enderthor.trainerbridgeble

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Placeholder foreground service — wired to the BLE central+peripheral pipeline in a later task. */
class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
