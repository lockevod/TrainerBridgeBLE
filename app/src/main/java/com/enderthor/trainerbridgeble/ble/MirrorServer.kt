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
    private val toZycle: (charUuid: UUID, bytes: ByteArray, withResponse: Boolean) -> Boolean,
    private val onStatus: (String) -> Unit = {},
    /** Health report to the UI: true once we're actually advertising; false if the server/advertising fails. */
    private val onAdvState: (Boolean) -> Unit = {},
    /** The trainer's own address. Android reports our CENTRAL link to it on the SERVER callback too, so
     *  without this it is counted as a connected app: the UI shows a client that isn't there and every
     *  trainer connect triggers a needless advertising restart. */
    private val isTrainer: (String) -> Boolean = { false },
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
    // touched from the GATT server binder thread, the client's binder thread and main — a plain ArrayDeque
    // can throw mid-poll when stop() clears it, and an exception on a binder callback kills the process
    private val pendingServices = java.util.concurrent.ConcurrentLinkedDeque<BluetoothGattService>()

    private val ADV_RESTART_MS = 250L
    private val ADV_RETRY_MS = 1000L
    private val ADV_MAX_RETRIES = 5
    private val SERVICE_RETRY_MS = 300L
    private val SERVICE_MAX_RETRIES = 5
    private val ADV_START_TIMEOUT_MS = 3000L
    @Volatile private var serviceRetries = 0
    private val serviceRetryRunnable = Runnable { if (server != null) addNextService() }
    private val ADVERTISE_FAILED_DATA_TOO_LARGE = 1
    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ATT_UNLIKELY_ERROR = 0x0E
    private val ATT_INVALID_OFFSET = 0x07
    private var originalName: String? = null
    @Volatile private var advertising = false
    @Volatile private var advStarting = false   // a start is in flight; `advertising` only flips in the callback
    private val built = java.util.concurrent.atomic.AtomicBoolean(false)   // build the mirrored GATT once; a trainer reconnect keeps it
    @Volatile private var advBlueprint: AdvBlueprint? = null   // the trainer's real advertising, to clone
    @Volatile private var trainerLinked = false   // advertise only while a trainer is actually feeding us
    @Volatile private var servicesReady = false     // every mirrored service has been ADDED (built != added)
    @Volatile private var advRetries = 0
    @Volatile private var dropNameFromAdv = false   // set after DATA_TOO_LARGE: the packet won't fit the name
    @Volatile private var dropMfrFromAdv = false    // shed the cloned manufacturer data first
    @Volatile private var dropBlueprintFromAdv = false   // then the cloned UUIDs, falling back to the standard pair

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
        advRetries = 0   // a new blueprint deserves a fresh retry budget — but keep what we learned about size
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
        // Remember every name WE have ever advertised. Without this the read-back after a stop→start (the
        // rename is asynchronous, so it still shows the PREVIOUS advertised name) gets stored as if it were
        // the user's real device name — which is how a Karoo ends up permanently called "ZycleBike7".
        val ours = prefs.getStringSet(KEY_ADV_NAMES, emptySet())!!.toMutableSet()
        if (ours.add(advertisedName)) prefs.edit().putStringSet(KEY_ADV_NAMES, ours).apply()
        // Capture the real name when we see one that is not ours — first run, or the user renamed the
        // device since. Never capture a name we know we put there.
        if (current != null && current != advertisedName && current !in ours &&
            current != prefs.getString(KEY_ORIG_NAME, null))
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
        // Restore unconditionally. Gating on `adapter.name == advertisedName` looked safer but is unsound:
        // the rename is asynchronous, so a stop right after a start reads back the OLD name, skips the
        // restore, and leaves the user's phone named after the trainer. Losing a rename the user made
        // mid-session is the lesser evil, and only a stale pref can cause it.
        val orig = originalName ?: prefs.getString(KEY_ORIG_NAME, null)
        if (orig != null) runCatching { adapter.name = orig }
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
        servicesReady = false
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
        if (pendingServices.isEmpty()) {
            // Advertising an empty GATT is worse than not advertising: an app connects and caches nothing.
            // Release the build latch so a later, real discovery can still populate the mirror.
            FileLog.event("mirror has NO services — not advertising, waiting for a real profile")
            built.set(false)
        } else addNextService()
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

    /** PEEK, don't poll: the service stays at the head until its own onServiceAdded confirms it. Removing it
     *  up front let a late success (the stack had queued the "refused" add after all) and the retry both
     *  drive the chain — adding a service twice and letting `servicesReady` fire with an add still in flight. */
    private fun addNextService() {
        val svc = pendingServices.peek() ?: return
        if (runCatching { server?.addService(svc) }.getOrNull() != true) {
            if (serviceRetries++ >= SERVICE_MAX_RETRIES) {
                FileLog.event("mirror addService REFUSED for ${shortUuid(svc.uuid)} — giving up, releasing build latch")
                built.set(false)   // so the next discovery can rebuild instead of staying silent forever
                return
            }
            FileLog.event("mirror addService REFUSED for ${shortUuid(svc.uuid)} — retry ${serviceRetries}")
            handler.removeCallbacks(serviceRetryRunnable); handler.postDelayed(serviceRetryRunnable, SERVICE_RETRY_MS)
        }
    }

    fun stop() {
        // Stop the advertiser UNCONDITIONALLY: the `advertising` flag is transiently false mid-restart, so
        // trusting it here can leave the phone broadcasting with a closed GATT server.
        advertising = false; advStarting = false; servicesReady = false
        handler.removeCallbacksAndMessages(null)   // pending adv starts / service retries must not outlive us
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
            // Machine Status "Target Power Changed" echoes the (inverse-corrected) watts we commanded, so
            // correct it forward or the app reads back a target it never asked for
            charUuid == GattUuids.MACHINE_STATUS -> PowerRewrite.correctMachineStatusTargetPower(value, correction())
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
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                srv.notifyCharacteristicChanged(dev, ch, indicate, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else {
                ch.value = value
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(dev, ch, indicate)
            }
            // a refused notification is a silently dropped sample — "power froze in the app"
            if (!ok) FileLog.event("notify ${shortUuid(ch.uuid)} REFUSED (buffer full?) -> ${dev.address}")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // don't poll it: retry the head rather than advertise a mirror missing a service
                FileLog.event("mirror addService FAILED status=$status for ${shortUuid(service?.uuid)} — retrying head")
                if (serviceRetries++ < SERVICE_MAX_RETRIES) {
                    handler.removeCallbacks(serviceRetryRunnable); handler.postDelayed(serviceRetryRunnable, SERVICE_RETRY_MS)
                } else { FileLog.event("mirror giving up on ${shortUuid(service?.uuid)} — releasing build latch"); built.set(false) }
                return
            }
            handler.removeCallbacks(serviceRetryRunnable)   // a stale retry would add the NEXT service twice
            pendingServices.poll()   // confirmed — NOW it leaves the queue
            serviceRetries = 0
            if (pendingServices.isEmpty()) {   // the mirrored GATT is complete
                servicesReady = true
                handler.post { startAdvertising() }
            } else addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (isTrainer(device.address)) {   // our own central link surfacing on the server callback
                FileLog.event("server sees the trainer link ${device.address} — not an app, ignored")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                clients[device.address] = device; onStatus(context.getString(R.string.status_app_connected, clients.size))
                FileLog.event("app connected ${device.address} status=$status (${clients.size} total)")
                // Android stops connectable advertising once a central connects — restart it so a SECOND
                // central (e.g. the Garmin) can still discover us.
                handler.post { restartAdvertising() }
            } else {
                clients.remove(device.address); subscribers.values.forEach { it.remove(device.address) }
                onStatus(context.getString(R.string.status_app_disconnected, clients.size))
                FileLog.event("app disconnected ${device.address} status=$status (${clients.size} left)")
                handler.post { restartAdvertising() }   // the controller stopped our advert when it connected
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic?) {
            val full = ch?.let { cache[it.uuid] }
            val who = "${shortUuid(ch?.uuid)}${if (offset > 0) " off=$offset" else ""} <- ${device?.address}"
            if (full == null) {
                // Not read from the trainer yet (its reads queue behind every subscribe). Answer ATT
                // "Unlikely Error" so the client can retry — an empty SUCCESS reads as "no capabilities".
                FileLog.event("app read $who — cache cold, answering 0x0E")
                runCatching { server?.sendResponse(device, requestId, ATT_UNLIKELY_ERROR, offset, ByteArray(0)) }
                return
            }
            if (offset < 0 || offset > full.size) {
                FileLog.event("app read $who — invalid offset, answering 0x07")
                runCatching { server?.sendResponse(device, requestId, ATT_INVALID_OFFSET, offset, ByteArray(0)) }
                return
            }
            val value = full.copyOfRange(offset, full.size)
            FileLog.event("app read $who = ${FileLog.hex(value)}")   // the VALUE, not just the request
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, ch: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val uuid = ch?.uuid
            var relayed = false
            val tag = "${shortUuid(uuid)} <- ${device?.address}" +
                (if (preparedWrite) " PREPARED off=$offset" else "") + (if (!responseNeeded) " noResp" else "")
            if (uuid != null && value != null) {
                val out = if (GattUuids.carriesControl(uuid)) PowerRewrite.inverseTargetPower(value, correction()) else value
                // what the client asked for, unless the trainer's characteristic can't take a Write Command
                val withResponse = responseNeeded ||
                    (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
                FileLog.event("app write $tag = ${FileLog.hex(value)}" + if (!out.contentEquals(value)) " -> ${FileLog.hex(out)}" else "")
                // ponytail: a prepared (long) write is relayed fragment-by-fragment rather than buffered
                // until onExecuteWrite. No FTMS/CPS characteristic exceeds one ATT payload, so this only
                // matters if some app starts using long writes — the log line above says when it happens.
                relayed = toZycle(uuid, out, withResponse)   // relay to the trainer
                if (!relayed) FileLog.event("app write $tag NOT RELAYED — answering failure")
            } else FileLog.event("app write $tag = <no value / unknown char>")
            // ATT response = "received", always. FTMS puts the OUTCOME in the control point indication.
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            // ...and if we could not hand it to the trainer, say so the way a trainer would: Response Code
            // 0x80, <op>, 0x04 Operation Failed. Without this the app waits forever for an indication.
            if (!relayed && uuid != null && device != null && value != null && value.isNotEmpty() &&
                !preparedWrite && GattUuids.carriesControl(uuid)) {
                // ONLY to the client that wrote, and NOT into the read cache: the response belongs to one
                // FTMS procedure, and fanning it out tells the other app its own request failed.
                val resp = byteArrayOf(0x80.toByte(), value[0], 0x04)
                val cp = ch
                // only if this client actually enabled the control point — never indicate unsolicited
                if (subscribers[uuid]?.contains(device.address) == true) handler.post {
                    val srv = server ?: return@post
                    notify(srv, device, cp, resp, cp.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                }
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor?.uuid == cccd && device != null) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val cUuid = descriptor.characteristic?.uuid
                if (cUuid != null) {
                    val set = subscribers.computeIfAbsent(cUuid) { java.util.Collections.synchronizedSet(HashSet()) }
                    if (enabled) set.add(device.address) else set.remove(device.address)
                    FileLog.event("app subscribe ${shortUuid(cUuid)} enabled=$enabled raw=${FileLog.hex(value ?: ByteArray(0))} <- ${device.address}")
                }
            } else FileLog.event("app descriptor write ${shortUuid(descriptor?.uuid)} = ${FileLog.hex(value ?: ByteArray(0))} <- ${device?.address}")
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        /** Long-write commit. We relay fragments as they arrive (see onCharacteristicWriteRequest), so this
         *  only has to answer — but log it, because its presence means an app IS using long writes. */
        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            FileLog.event("app execute write execute=$execute <- ${device?.address}")
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }

        /** Notification flow control: a failure here means our notifications stopped reaching the app. */
        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) FileLog.event("notify FAILED status=$status -> ${device?.address}")
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            FileLog.event("app mtu=$mtu <- ${device?.address}")
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            // Report the client's current CCCD state so a read doesn't time out.
            val cUuid = descriptor?.characteristic?.uuid
            val on = device != null && cUuid != null && subscribers[cUuid]?.contains(device.address) == true
            val indicate = descriptor?.characteristic?.properties
                ?.and(BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val v = if (!on) byteArrayOf(0x00, 0x00)
                    else if (indicate) byteArrayOf(0x02, 0x00) else byteArrayOf(0x01, 0x00)
            FileLog.event("app read descriptor ${shortUuid(descriptor?.uuid)} of ${shortUuid(cUuid)} = ${FileLog.hex(v)} <- ${device?.address}")
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v) }
        }
    }

    // ── advertising ──────────────────────────────────────────────────────────────────────────────────
    private val advCallback = object : android.bluetooth.le.AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { handler.removeCallbacks(advStartWatchdog); advStarting = false; advertising = true; advRetries = 0; onAdvState(true); onStatus(context.getString(R.string.status_advertising, advertisedName)); FileLog.event("advertising as $advertisedName")
            // a stop issued while this start was in flight is dropped by the stack — reconcile now
            if (!trainerLinked || server == null) { FileLog.event("advertising with no trainer — stopping"); stopAdvertising() }
        }
        override fun onStartFailure(errorCode: Int) {
            handler.removeCallbacks(advStartWatchdog); advStarting = false; advertising = false; onAdvState(false)
            onStatus(context.getString(R.string.status_advertise_failed, errorCode))
            FileLog.event("advertise failed $errorCode (retry ${advRetries + 1}/$ADV_MAX_RETRIES, name=${!dropNameFromAdv})")
            // 31-byte PDU: shed the cloned manufacturer data first (usually the culprit), the name only if
            // that still isn't enough — apps find us BY the name, so it is the last thing to go.
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) when {
                !dropMfrFromAdv -> dropMfrFromAdv = true          // the cloned manufacturer data usually is it
                !dropBlueprintFromAdv -> dropBlueprintFromAdv = true   // then the cloned UUIDs (128-bit won't fit)
                else -> dropNameFromAdv = true                    // last resort: apps find us BY the name
            }
            if (advRetries++ < ADV_MAX_RETRIES) {
                handler.removeCallbacks(startAdvRunnable); handler.postDelayed(startAdvRunnable, ADV_RETRY_MS)
            }
        }
    }

    private fun startAdvertising() {
        // no trainer → stay off the air (see setTrainerLinked); not built → we'd advertise an empty GATT and
        // an app that connects in that window caches it. onServiceAdded calls back here once services land.
        if (advertising || advStarting || !trainerLinked || !servicesReady) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { onStatus(context.getString(R.string.status_ble_adv_unsupported)); onAdvState(false); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        // Clone the trainer's own advertising when we have it (same service UUIDs + manufacturer data), so a
        // head unit that filters on them sees us exactly like the real trainer. Fallback (sim / not yet
        // captured): the three standard cycling services.
        val builder = AdvertiseData.Builder().setIncludeDeviceName(!dropNameFromAdv)
        val bp = if (dropBlueprintFromAdv) null else advBlueprint
        if (bp != null && bp.serviceUuids.isNotEmpty()) {
            bp.serviceUuids.forEach { builder.addServiceUuid(it) }
            if (!dropMfrFromAdv) bp.manufacturerData.forEach { (id, d) -> runCatching { builder.addManufacturerData(id, d) } }
        } else {
            // only what we actually serve — advertising CSC 0x1816 made head units connect and find nothing
            builder.addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1818)))
                .addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1826)))
        }
        advStarting = true
        if (runCatching { advertiser.startAdvertising(settings, builder.build(), advCallback) }.isFailure) advStarting = false
        else {
            // An accepted start normally answers with exactly one callback — except when the adapter is
            // turned off or the BT process dies under it, which drops the callback silently. Without this
            // the flag latches and we never advertise again.
            handler.removeCallbacks(advStartWatchdog); handler.postDelayed(advStartWatchdog, ADV_START_TIMEOUT_MS)
        }
    }

    /** Unconditional, for the same reason [stop] is: `advertising` is false between startAdvertising() and
     *  onStartSuccess, so an early return here can leave a pending advert running with no trainer behind it. */
    private fun stopAdvertising() {
        handler.removeCallbacks(startAdvRunnable)
        advertising = false
        // NOT advStarting: a stop issued while a start is in flight is dropped by the stack, so the start
        // is still coming. Only its callback may clear the flag, or we let a second start through.
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
    }

    /** Force a fresh advertise (Android silently stopped it when a central connected, but our flag didn't
     *  know). Needed so more than one app can find us. */
    private val startAdvRunnable = Runnable { startAdvertising() }
    private val advStartWatchdog = Runnable {
        if (advStarting) { FileLog.event("advertise start never answered — clearing in-flight flag"); advStarting = false }
    }

    private fun restartAdvertising() {
        stopAdvertising()
        // The stop is async and only frees this callback when it completes; starting in the same turn
        // answers ADVERTISE_FAILED_ALREADY_STARTED. Give it a turn — and collapse duplicate restarts
        // (connect + disconnect in quick succession) into a single pending start.
        handler.removeCallbacks(startAdvRunnable)
        handler.postDelayed(startAdvRunnable, ADV_RESTART_MS)
    }

    private fun logRelay(uuid: UUID, inV: ByteArray, outV: ByteArray, nClients: Int) {
        if (!FileLog.enabled) return   // don't build hex strings on the BLE thread when logging is off
        val corr = if (!outV.contentEquals(inV)) " -> ${FileLog.hex(outV)}" else ""
        FileLog.event("relay ${shortUuid(uuid)} = ${FileLog.hex(inV)}$corr to $nClients")
    }

    private fun shortUuid(u: UUID?): String {
        val s = u?.toString() ?: return "?"
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
    }

    private companion object {
        const val KEY_ADV_NAMES = "advertisedNamesUsed"
        const val KEY_ORIG_NAME = "origBtName"
    }
}
