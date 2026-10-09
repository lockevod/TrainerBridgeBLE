package com.enderthor.trainerbridgeble.ble

import java.util.UUID

/** The trainer side of the bridge: either the real [ZycleClient] (BLE central) or the [SimSource]
 *  (synthetic, test mode). Both drive onProfile/onValue/onState and accept relayed control writes. */
interface TrainerSource {
    fun start()
    fun stop()
    /** True while a BLE scan is actually registered with the controller. The idle wakelock guard needs
     *  this: releasing the CPU is only safe once the controller is holding the search for us, because a
     *  postDelayed retry does NOT wake a suspended CPU — with no scan up, the trainer's advertising has
     *  nothing to arrive at. */
    val searching: Boolean get() = false
    /** @return false if the write could not be dispatched (no link, unknown characteristic) — the mirror
     *  must NOT then answer the app with success. */
    fun write(
        charUuid: UUID,
        bytes: ByteArray,
        withResponse: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean
}
