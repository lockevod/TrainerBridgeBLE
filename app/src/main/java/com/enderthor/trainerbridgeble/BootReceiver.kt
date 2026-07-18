package com.enderthor.trainerbridgeble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume the master state after a device reboot: if the user had the app active (masterEnabled persisted
 *  on), bring the service back up so receiving + the virtual sensor work without opening the app. If it was
 *  off, do nothing (stays off — zero consumption). BOOT_COMPLETED is an allowed foreground-service start. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (Config(context).masterEnabled) BridgeService.setMaster(context, true)
    }
}
