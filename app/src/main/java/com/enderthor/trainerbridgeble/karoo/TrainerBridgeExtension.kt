package com.enderthor.trainerbridgeble.karoo

import com.enderthor.trainerbridgeble.BridgeService
import com.enderthor.trainerbridgeble.Config
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent

class TrainerBridgeExtension : KarooExtension("trainerbridge", "1.0") {
    override fun startScan(emitter: Emitter<Device>) {
        // Always advertise the single virtual sensor so the Karoo can pair it.
        emitter.onNext(CorrectedSource(extension).source)
        emitter.setCancellable { }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        if (uid != "corrected-trainer") return
        // The extension auto-runs on the Karoo; only feed data (and start the receive service) when the
        // master switch is on. When off, the source stays SEARCHING and nothing is started.
        if (Config(applicationContext).masterEnabled) BridgeService.setMaster(applicationContext, true)
        CorrectedSource(extension).connect(emitter)
    }
}
