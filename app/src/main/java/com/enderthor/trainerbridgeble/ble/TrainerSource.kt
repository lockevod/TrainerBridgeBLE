package com.enderthor.trainerbridgeble.ble

import java.util.UUID

/** The trainer side of the bridge: either the real [ZycleClient] (BLE central) or the [SimSource]
 *  (synthetic, test mode). Both drive onProfile/onValue/onState and accept relayed control writes. */
interface TrainerSource {
    fun start()
    fun stop()
    fun write(charUuid: UUID, bytes: ByteArray, withResponse: Boolean)
}
