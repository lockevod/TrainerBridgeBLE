package com.enderthor.trainerbridgeble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.enderthor.trainerbridgeble.FileLog
import com.enderthor.trainerbridgeble.R
import com.enderthor.trainerbridgeble.correction.PowerCorrection
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE peripheral that MIRRORS the trainer's GATT to the apps (Bestcycling + Garmin), transparently:
 * every service/characteristic the trainer exposes is re-exposed with the same UUID/properties, and
 * relayed byte-for-byte — EXCEPT the advertised name and the power field (corrected read-side, and the
 * ERG target inverse-corrected on control writes). Serves multiple centrals at once.
 *
 * @param correction supplies the LIVE correction (config may change mid-session).
 * @param toZycle    forwards an app write to the trainer's matching characteristic.
 */
@SuppressLint("MissingPermission")
class MirrorServer(
    private val context: Context,
    private val advertisedName: String,
    private val correction: () -> PowerCorrection,
    private val toZycle: (charUuid: UUID, bytes: ByteArray, withResponse: Boolean) -> Unit,
    private val onStatus: (String) -> Unit = {},
    /** Health report to the UI: true once we're actually advertising; false if the server/advertising fails. */
    private val onAdvState: (Boolean) -> Unit = {},
) {
    private val tag = "TBB/MirrorServer"
    private val handler = Handler(Looper.getMainLooper())
    private val mgr by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter by lazy { mgr.adapter }

    @Volatile private var server: BluetoothGattServer? = null
    private val chars = ConcurrentHashMap<UUID, BluetoothGattCharacteristic>()          // uuid → our mirrored char
    private val cache = ConcurrentHashMap<UUID, ByteArray>()                            // last value (power corrected)
    private val subscribers = ConcurrentHashMap<UUID, MutableSet<String>>()             // char uuid → subscribed client addrs
    private val clients = ConcurrentHashMap<String, BluetoothDevice>()                  // connected centrals
    private val pendingServices = ArrayDeque<BluetoothGattService>()

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var originalName: String? = null
    @Volatile private var advertising = false
    private val built = java.util.concurrent.atomic.AtomicBoolean(false)   // build the mirrored GATT once; a trainer reconnect keeps it
    @Volatile private var advBlueprint: AdvBlueprint? = null   // the trainer's real advertising, to clone
    @Volatile private var trainerLinked = false   // advertise only while a trainer is actually feeding us

    /** Gate advertising on the trainer link. We advertise under the trainer's own name, and the trainer
     *  starts advertising again the moment it drops — so advertising with no trainer behind us puts two
     *  identical devices in the air and lets an app bind to a bridge that has no data to give it. */
    fun setTrainerLinked(linked: Boolean) {
        if (trainerLinked == linked) return
        trainerLinked = linked
        FileLog.event("mirror trainer link=$linked -> ${if (linked) "advertise" else "stop advertising"}")
        handler.post { if (linked) startAdvertising() else stopAdvertising() }
    }

    /** Adopt the trainer's own advertised service UUIDs + manufacturer data (captured by the client) so we
     *  advertise an identical packet. If we're already advertising, restart to apply it. */
    fun setAdvBlueprint(bp: AdvBlueprint) {
        advBlueprint = bp
        FileLog.event("mirror adv blueprint: ${bp.serviceUuids.size} uuids, ${bp.manufacturerData.size} mfr")
        handler.post { if (advertising) restartAdvertising() }   // serialise onto the advertising thread
    }

    fun start() {
        val srv = runCatching { mgr.openGattServer(context, serverCallback) }.getOrNull()
        if (srv == null) { onStatus(context.getString(R.string.status_ble_server_failed)); onAdvState(false); return }
        server = srv
        renameAdapter()
    }

    /** Rename the adapter to our advertised name, persisting the ORIGINAL to prefs so a process kill (which
     *  skips stop()) doesn't lose the user's real Bluetooth name — and so we never capture our own rename. */
    private fun renameAdapter() {
        val prefs = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)
        val current = runCatching { adapter.name }.getOrNull()
        // a stored original always wins over a read-back name, which may still be our own (see restoreName)
        if (prefs.getString(KEY_ORIG_NAME, null) == null && current != null && current != advertisedName)
            prefs.edit().putString(KEY_ORIG_NAME, current).apply()
        originalName = prefs.getString(KEY_ORIG_NAME, null)
        runCatching { if (current != advertisedName) adapter.name = advertisedName }
    }

    /** ponytail: KEY_ORIG_NAME is deliberately NOT cleared. adapter.name is set asynchronously, so a
     *  stop→start within the same second (a config save that changes the advertised name) would read back
     *  our own name, fail to store it, and lose the user's real one forever. Keeping it costs a stale pref
     *  if the user renames the device while we hold it; clear it on restore if that ever matters. */
    private fun restoreName() {
        val prefs = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)
        (originalName ?: prefs.getString(KEY_ORIG_NAME, null))?.let { orig -> runCatching { adapter.name = orig } }
        originalName = null
    }

    /** Build the mirrored server from the trainer's discovered GATT. Services are added one at a time.
     *  Built ONCE per session: a trainer reconnect re-discovers the SAME GATT, so we keep the existing
     *  services + characteristics (re-adding them would strand apps still subscribed to the old instances
     *  and there is no clean live rebuild). */
    fun build(profile: GattProfile) {
        val srv = server ?: return
        // Atomic gate: build() is called from both the GATT binder thread (onServicesDiscovered) and the main
        // thread (Start) — a plain check-then-set could let both through and double-add the services.
        if (!built.compareAndSet(false, true)) return
        chars.clear(); pendingServices.clear()
        for (svc in profile.services) {
            if (GattUuids.isStackService(svc.uuid)) continue
            val service = BluetoothGattService(svc.uuid,
                if (svc.primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY)
            for (cs in svc.chars) {
                // Android's CLIENT-side getPermissions() is 0 (permissions aren't exposed to a central), so
                // mirroring cs.permissions verbatim leaves the char with NO read/write permission and apps
                // can't use it. Derive permissions from the discovered PROPERTIES instead.
                val ch = BluetoothGattCharacteristic(cs.uuid, cs.properties, permissionsFor(cs.properties))
                for (dU in cs.descriptors) ch.addDescriptor(BluetoothGattDescriptor(dU,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                // Ensure a CCCD exists on notify/indicate chars even if the trainer didn't list it.
                if (cs.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && ch.getDescriptor(cccd) == null)
                    ch.addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                service.addCharacteristic(ch)
                chars[cs.uuid] = ch
                FileLog.event("mirror char ${shortUuid(svc.uuid)}/${shortUuid(cs.uuid)} props=${cs.properties}")
            }
            pendingServices.add(service)
        }
        FileLog.event("mirror built ${pendingServices.size} services")
        addNextService()
    }

    /** Read/write GATT permissions for a mirrored characteristic, derived from its BLE properties. */
    private fun permissionsFor(properties: Int): Int {
        var perm = 0
        if (properties and (BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            perm = perm or BluetoothGattCharacteristic.PERMISSION_READ
        if (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0)
            perm = perm or BluetoothGattCharacteristic.PERMISSION_WRITE
        return perm
    }

    private fun addNextService() { pendingServices.poll()?.let { s -> runCatching { server?.addService(s) } } }

    fun stop() {
        // Stop the advertiser UNCONDITIONALLY: the `advertising` flag is transiently false mid-restart, so
        // trusting it here can leave the phone broadcasting with a closed GATT server.
        advertising = false
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
        runCatching { server?.close() }
        server = null
        restoreName()
        built.set(false); advBlueprint = null
        chars.clear(); cache.clear(); subscribers.clear(); clients.clear(); pendingServices.clear()
    }

    /** A value arrived from the trainer: correct power, cache, and notify every subscribed client. */
    fun onZycleValue(charUuid: UUID, value: ByteArray) {
        val out = when {
            charUuid == GattUuids.INDOOR_BIKE_DATA -> PowerRewrite.correctIndoorBikeData(value, correction())
            charUuid == GattUuids.CYCLING_POWER_MEASUREMENT -> PowerRewrite.correctCyclingPower(value, correction())
            else -> value
        }
        cache[charUuid] = out
        val ch = chars[charUuid] ?: return
        val subs = subscribers[charUuid] ?: return
        if (subs.isEmpty()) return
        logRelay(charUuid, value, out, subs.size)
        handler.post {
            val srv = server ?: return@post
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val targets = synchronized(subs) { subs.toList() }   // copy under lock — a CCCD write may mutate it concurrently
            for (addr in targets) {
                val dev = clients[addr] ?: continue
                notify(srv, dev, ch, out, indicate)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(srv: BluetoothGattServer, dev: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray, indicate: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) srv.notifyCharacteristicChanged(dev, ch, indicate, value)
            else { ch.value = value; srv.notifyCharacteristicChanged(dev, ch, indicate) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) { addNextService(); if (pendingServices.isEmpty()) handler.post { startAdvertising() } }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                clients[device.address] = device; onStatus(context.getString(R.string.status_app_connected, clients.size))
                FileLog.event("app connected ${device.address}")
                // Android stops connectable advertising once a central connects — restart it so a SECOND
                // central (e.g. the Garmin) can still discover us.
                handler.post { restartAdvertising() }
            } else {
                clients.remove(device.address); subscribers.values.forEach { it.remove(device.address) }
                onStatus(context.getString(R.string.status_app_disconnected, clients.size))
                FileLog.event("app disconnected ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic?) {
            if (offset == 0) FileLog.event("app read ${shortUuid(ch?.uuid)}")   // e.g. does it read our identity/feature?
            val full = ch?.let { cache[it.uuid] } ?: ByteArray(0)
            val value = if (offset in 0..full.size) full.copyOfRange(offset, full.size) else ByteArray(0)
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, ch: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val uuid = ch?.uuid
            if (uuid != null && value != null) {
                val out = if (GattUuids.carriesControl(uuid)) PowerRewrite.inverseTargetPower(value, correction()) else value
                val withResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                FileLog.event("app write ${shortUuid(uuid)} = ${FileLog.hex(value)}" + if (!out.contentEquals(value)) " -> ${FileLog.hex(out)}" else "")
                toZycle(uuid, out, withResponse)   // relay to the trainer
            }
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor?.uuid == cccd && device != null) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val cUuid = descriptor.characteristic?.uuid
                if (cUuid != null) {
                    val set = subscribers.getOrPut(cUuid) { java.util.Collections.synchronizedSet(HashSet()) }
                    if (enabled) set.add(device.address) else set.remove(device.address)
                    FileLog.event("app subscribe ${shortUuid(cUuid)} enabled=$enabled")
                }
            }
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            // Report the client's current CCCD state so a read doesn't time out.
            val cUuid = descriptor?.characteristic?.uuid
            val on = device != null && cUuid != null && subscribers[cUuid]?.contains(device.address) == true
            val v = if (on) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v) }
        }
    }

    // ── advertising ──────────────────────────────────────────────────────────────────────────────────
    private val advCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; onAdvState(true); onStatus(context.getString(R.string.status_advertising, advertisedName)); FileLog.event("advertising as $advertisedName") }
        override fun onStartFailure(errorCode: Int) { advertising = false; onAdvState(false); onStatus(context.getString(R.string.status_advertise_failed, errorCode)); FileLog.event("advertise failed $errorCode") }
    }

    private fun startAdvertising() {
        // no trainer → stay off the air (see setTrainerLinked); not built → we'd advertise an empty GATT and
        // an app that connects in that window caches it. onServiceAdded calls back here once services land.
        if (advertising || !trainerLinked || !built.get()) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { onStatus(context.getString(R.string.status_ble_adv_unsupported)); onAdvState(false); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        // Clone the trainer's own advertising when we have it (same service UUIDs + manufacturer data), so a
        // head unit that filters on them sees us exactly like the real trainer. Fallback (sim / not yet
        // captured): the three standard cycling services.
        val builder = AdvertiseData.Builder().setIncludeDeviceName(true)
        val bp = advBlueprint
        if (bp != null && bp.serviceUuids.isNotEmpty()) {
            bp.serviceUuids.forEach { builder.addServiceUuid(it) }
            bp.manufacturerData.forEach { (id, d) -> runCatching { builder.addManufacturerData(id, d) } }
        } else {
            builder.addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1816)))
                .addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1818)))
                .addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1826)))
        }
        runCatching { advertiser.startAdvertising(settings, builder.build(), advCallback) }
    }

    /** Unconditional, for the same reason [stop] is: `advertising` is false between startAdvertising() and
     *  onStartSuccess, so an early return here can leave a pending advert running with no trainer behind it. */
    private fun stopAdvertising() {
        advertising = false
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
    }

    /** Force a fresh advertise (Android silently stopped it when a central connected, but our flag didn't
     *  know). Needed so more than one app can find us. */
    private fun restartAdvertising() {
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
        advertising = false
        startAdvertising()
    }

    private val relayLogMs = ConcurrentHashMap<UUID, Long>()
    private fun logRelay(uuid: UUID, inV: ByteArray, outV: ByteArray, nClients: Int) {
        val now = System.currentTimeMillis()
        if (now - (relayLogMs[uuid] ?: 0L) < 500L) return   // rate-limit power (4 Hz); other chars log promptly
        relayLogMs[uuid] = now
        val corr = if (!outV.contentEquals(inV)) " -> ${FileLog.hex(outV)}" else ""
        FileLog.event("relay ${shortUuid(uuid)} = ${FileLog.hex(inV)}$corr to $nClients")
    }

    private fun shortUuid(u: UUID?): String {
        val s = u?.toString() ?: return "?"
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
    }

    private companion object {
        const val KEY_ORIG_NAME = "origBtName"
    }
}
