package com.enderthor.trainerbridgeble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.enderthor.trainerbridgeble.FileLog
import com.enderthor.trainerbridgeble.GattSessionCoordinator
import com.enderthor.trainerbridgeble.IdentityOwner
import com.enderthor.trainerbridgeble.TrainerWriteTicket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BLE central to the trainer (Zycle). Scans filtered by FTMS/address, connects, discovers the FULL GATT, subscribes
 * to every notify/indicate characteristic, reads the readable ones once (for the mirror's read cache), and
 * relays incoming values. Exposes [write] so the mirror can forward app writes (control) to the trainer.
 *
 * All BluetoothGatt operations are SERIALISED through [opQueue] — Android allows only one GATT op in flight;
 * issuing a second before the first's callback silently drops it.
 */
@SuppressLint("MissingPermission")   // BLUETOOTH_SCAN/CONNECT are declared and requested by the UI before start()
class ZycleClient(
    private val context: Context,
    private val pairedAddress: String,      // exact device to connect to; empty → first trainer the scan filter yields
    private val onProfile: (GattProfile) -> Unit,
    private val onValue: (charUuid: UUID, value: ByteArray) -> Unit,   // notifications AND initial reads
    private val onState: (connected: Boolean) -> Unit,
    // Fired once per connection when the opening burst of reads+subscribes has drained, i.e. when we
    // actually HAVE the trainer's values. The mirror waits for this before advertising: services are added
    // locally in milliseconds, but the reads take 0.5-2 s over the air, and an app that connects in that
    // window reads an empty FTMS Feature and decides the machine has no ERG for the whole session.
    private val onSynced: () -> Unit = {},
    private val onAdv: (AdvBlueprint) -> Unit = {},   // the trainer's own advertising, for the mirror to clone
    private val onFound: (name: String?, address: String) -> Unit = { _, _ -> },   // for the config screen
) : TrainerSource {
    private val tag = "TBB/ZycleClient"
    private val handler = Handler(Looper.getMainLooper())
    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    private val gattSessions = GattSessionCoordinator<BluetoothGatt>(
        resetRuntime = {
            lastMessageMs = 0L
            onState(false)
            completePendingWrites(false)
            opQueue.clear(); opBusy.set(false); syncOwed.set(false); burstEnqueued = false
            connecting.set(false); inFlightWrite = null
        },
        disconnect = { runCatching { it.disconnect() } },
        close = { runCatching { it.close() } },
        reconnect = { scheduleReconnect() },
    )
    private var gatt: BluetoothGatt?
        get() = gattSessions.current
        set(value) { gattSessions.replace(value) }
    /** Owns the ONE connect attempt allowed to publish a handle. connectGatt() is a blocking Binder call and
     *  stop() can run to completion inside it, so a plain `stopped` check cannot gate the publication: the
     *  check would pass, stop() would find no GATT to close, and the attempt would then install an orphan
     *  that keeps the trainer to itself for the rest of the ride. Distinct from [connecting], which stops
     *  concurrent connectGatt() calls; this decides which attempt still OWNS the outcome. */
    private val connectAttempts = IdentityOwner<Any>()
    private val connecting = AtomicBoolean(false)   // CAS: scan results arrive on a binder thread pool
    @Volatile private var stopped = false
    @Volatile private var scanning = false
    // elapsedRealtime, not wall-clock: a mid-ride clock re-sync would otherwise either trip the watchdog on
    // a healthy link or delay it past a real one, by the size of the correction.
    @Volatile private var lastMessageMs = 0L   // last notification, for the silent-link watchdog
    private val reconnectPending = AtomicBoolean(false)
    @Volatile private var retryMs = SCAN_RETRY_MS   // scan backoff, reset on a good connection
    // Have we ever had this trainer on the line in THIS session? Splits the two scans that look alike:
    // a cold search (trainer not powered on — may run for hours) from a mid-ride reacquisition.
    @Volatile private var everConnected = false

    private val opQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val opBusy = AtomicBoolean(false)
    private val syncOwed = AtomicBoolean(false)      // onSynced not yet delivered for THIS connection
    @Volatile private var burstEnqueued = false      // the opening read/subscribe burst is in the queue

    /** Deliver [onSynced] at most once per connection, on the main thread, and never for a link that has
     *  dropped in the meantime: a stale delivery would put the mirror on the air with no trainer behind it,
     *  and setTrainerLinked is edge-triggered, so it would STAY there. */
    private fun fireSynced(g: BluetoothGatt) {
        gattSessions.runIfCurrent(g) {
            if (!syncOwed.compareAndSet(true, false)) return@runIfCurrent
            handler.post { if (!stopped) gattSessions.runIfCurrent(g) { onSynced() } }
        }
    }
    private val opToken = java.util.concurrent.atomic.AtomicInteger(0)   // guards the per-op watchdog vs a stale timeout

    private data class WriteReq(
        val session: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val uuid: UUID,
        val bytes: ByteArray,
        val withResponse: Boolean,
        val retriesLeft: Int,
        val ticket: TrainerWriteTicket,
    )
    @Volatile private var inFlightWrite: WriteReq? = null   // the write currently on the wire, for retry on failure
    private val pendingWrites = ConcurrentHashMap.newKeySet<TrainerWriteTicket>()
    private val writeSeq = AtomicLong(0)  // bumps per write; a retry is dropped if superseded

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun start() {
        stopped = false
        reconnectPending.set(false); retryMs = SCAN_RETRY_MS   // stop() may have eaten the runnable that clears these
        startScan()
        handler.removeCallbacks(heartbeat)   // never stack a second heartbeat
        handler.postDelayed(heartbeat, HEARTBEAT_CHECK_MS)
    }

    override fun stop() {
        stopped = true
        // BEFORE anything reads `gatt`: an in-flight connectGatt() then fails its ownership check and closes
        // its own handle. Both paths cross this monitor, and this clear precedes the `gatt` read below, so
        // either the attempt published first (and the read closes it) or it never publishes at all.
        connectAttempts.clear()
        connecting.set(false)
        completePendingWrites(false)
        inFlightWrite = null
        stopScan()
        handler.removeCallbacksAndMessages(null)   // heartbeat, rescan, connect/op watchdogs, write retries
        opQueue.clear(); opBusy.set(false)
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    /** ANT-learned: a GATT link can stay "connected" while notifications silently stop (no disconnect
     *  callback fires), so nothing else recovers it. Poll, and if the trainer has been silent past the
     *  timeout, tear the link down and re-scan. Masters/notifications refresh [lastMessageMs] constantly,
     *  so this only trips on a genuinely stuck link. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (stopped) return
            val g = gatt
            if (g != null && lastMessageMs != 0L && android.os.SystemClock.elapsedRealtime() - lastMessageMs > HEARTBEAT_TIMEOUT_MS) {
                recycleGatt(g, "silent ${android.os.SystemClock.elapsedRealtime() - lastMessageMs}ms")
            }
            handler.postDelayed(this, HEARTBEAT_CHECK_MS)
        }
    }

    /** Idempotent re-scan scheduler — a self-induced teardown and the disconnect callback can both ask. */
    private fun scheduleReconnect() {
        if (stopped) return
        if (reconnectPending.compareAndSet(false, true))
            handler.postDelayed({ reconnectPending.set(false); startScan() }, RECONNECT_DELAY_MS)
    }

    /** A timed-out Android GATT operation has no cancellation API. The only safe queue reset is therefore
     *  to retire the whole handle; callbacks already queued for it then fail the same identity gate. */
    private fun recycleGatt(g: BluetoothGatt, reason: String) {
        gattSessions.retireIfCurrent(g) { FileLog.event("Zycle watchdog: $reason -> reconnect") }
    }

    /** Forward a write to the trainer's characteristic [charUuid] (control relay). Queued. */
    override fun write(
        charUuid: UUID,
        bytes: ByteArray,
        withResponse: Boolean,
        onComplete: (Boolean) -> Unit,
    ): Boolean {
        val ticket = TrainerWriteTicket(writeSeq.incrementAndGet(), onComplete)
        if (stopped) {
            FileLog.event("Zycle write ${shortUuid(charUuid)} DROPPED — client stopped")
            ticket.complete(false)
            return false
        }
        val g = gatt ?: run {
            FileLog.event("Zycle write ${shortUuid(charUuid)} DROPPED — no trainer link")
            ticket.complete(false)
            return false
        }
        // g.services is repopulated by discovery while this runs on the GATT-server binder thread
        val ch = runCatching { g.services.firstNotNullOfOrNull { s -> s.getCharacteristic(charUuid) } }.getOrNull()
            ?: run {
                FileLog.event("Zycle write ${shortUuid(charUuid)} DROPPED — characteristic not found")
                ticket.complete(false)
                return false
            }
        FileLog.event("Zycle write ${shortUuid(charUuid)} = ${FileLog.hex(bytes)}")
        return enqueueWrite(WriteReq(g, ch, charUuid, bytes, withResponse, CONTROL_WRITE_RETRIES, ticket))
    }

    private fun enqueueWrite(request: WriteReq): Boolean {
        var queued = false
        gattSessions.runIfCurrent(request.session) {
            pendingWrites.add(request.ticket)
            opQueue.add { executeWrite(request) }
            queued = true
        }
        if (!queued) {
            completeWrite(request, false)
            return false
        }
        pump()
        return true
    }

    private fun executeWrite(request: WriteReq) {
        if (stopped || gattSessions.current !== request.session) {
            completeWrite(request, false)
            opDone()
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            run {
                inFlightWrite = request
                request.characteristic.writeType = if (request.withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                request.characteristic.value = request.bytes
                if (request.session.writeCharacteristic(request.characteristic) != true) {
                    FileLog.event("Zycle write ${shortUuid(request.uuid)} REFUSED by stack" +
                        if (canRetry(request)) " — retry ${request.retriesLeft}" else "")
                    inFlightWrite = null
                    if (canRetry(request)) scheduleWriteRetry(request) else completeWrite(request, false)
                    opDone()
                }
            }
        }.onFailure {
            FileLog.event("Zycle write ${shortUuid(request.uuid)} FAILED before dispatch")
            inFlightWrite = null
            completeWrite(request, false)
            opDone()
        }
    }

    private fun canRetry(request: WriteReq): Boolean =
        request.retriesLeft > 0 && GattUuids.carriesControl(request.uuid)

    private fun scheduleWriteRetry(request: WriteReq) {
        handler.postDelayed({
            if (!stopped && writeSeq.get() == request.ticket.sequence && gattSessions.current === request.session) {
                enqueueWrite(request.copy(retriesLeft = request.retriesLeft - 1))
            } else completeWrite(request, false)
        }, CONTROL_RETRY_DELAY_MS)
    }

    private fun completeWrite(request: WriteReq, success: Boolean) {
        pendingWrites.remove(request.ticket)
        runCatching { request.ticket.complete(success) }
            .onFailure { FileLog.event("Zycle write ${shortUuid(request.uuid)} completion callback FAILED") }
    }

    private fun completePendingWrites(success: Boolean) {
        pendingWrites.forEach { ticket ->
            pendingWrites.remove(ticket)
            runCatching { ticket.complete(success) }
                .onFailure { FileLog.event("Zycle write completion callback FAILED") }
        }
    }

    // ── scan ────────────────────────────────────────────────────────────────────────────────────────
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (stopped) return   // results already queued in the controller when stop() landed
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name
            // the scan filter already guarantees FTMS/CPS, so unpaired = take the first trainer that answers
            val match = pairedAddress.isEmpty() || dev.address == pairedAddress
            if (match) {
                if (gatt != null || !connecting.compareAndSet(false, true)) return   // duplicate advert / already busy
                FileLog.event("Zycle found '$name' ${dev.address} rssi=${result.rssi}")
                onFound(name, dev.address)
                captureAdv(result)
                stopScan(); connect(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "scan failed $errorCode"); FileLog.event("Zycle scan FAILED code=$errorCode")
            stopScan()         // clears `scanning` — without that every later startScan() early-returns
            scanning = false   // ...and clear it even if stopScan() found nothing to stop
            retryScanLater()
        }
    }

    /** Snapshot the trainer's advertised service UUIDs + manufacturer data so the mirror re-advertises an
     *  identical packet (see [AdvBlueprint]). */
    private fun captureAdv(result: ScanResult) {
        val rec = result.scanRecord ?: return
        val uuids = rec.serviceUuids ?: emptyList()
        val mfr = mutableListOf<Pair<Int, ByteArray>>()
        rec.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfr.add(sa.keyAt(i) to sa.valueAt(i)) }
        FileLog.event("Zycle adv: uuids=${uuids.joinToString { it.toString() }} mfr=${mfr.joinToString { "0x%04X:${FileLog.hex(it.second)}".format(it.first) }}")
        onAdv(AdvBlueprint(uuids, mfr))
    }

    private fun startScan(): Unit {
        if (stopped || scanning) return
        if (gatt != null || connecting.get()) return   // already linked/linking — a stray retry must not scan
        // Bluetooth off (or the stack restarting) → no scanner at all. There is no adapter-state receiver,
        // so without a retry here the client would stay dead for the rest of the session.
        val scanner = adapter?.bluetoothLeScanner ?: run {
            FileLog.event("Zycle scan: no LE scanner (Bluetooth off?) — retry in ${retryMs}ms"); retryScanLater(); return
        }
        // Paired → filter by address only: the trainer's advert may carry its UUIDs in the scan
        // response, which the controller's offloaded UUID filter never sees. Unpaired → FTMS only:
        // a bare power meter advertises CPS but can't take ERG, so it must not win the race.
        val filters = if (pairedAddress.isNotEmpty())
            listOf(android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(pairedAddress).build())
        else GattUuids.scanFilters(0x1826)
        // BALANCED, not LOW_POWER, for a REACQUISITION: with the screen off LOW_POWER's duty cycle can take
        // minutes to find the trainer again mid-ride. That argument is about a scan following a connection we
        // already had. A cold search is the other case and is not the same: the trainer simply isn't powered
        // on, nothing stops a successful scan, and BALANCED's 25% radio duty then runs for hours. Cost of
        // telling them apart: the first acquisition of the day takes a few seconds longer.
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(if (everConnected) android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
                         else android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER).build()
        // A revoked BLUETOOTH_SCAN throws here; swallowing it while `scanning` was already true left the
        // client permanently dead and silent. Set the flag ONLY once the scan really started.
        // Log the START, not just failures: without it a log cannot tell "scanning and the trainer is off"
        // from "never started scanning", and it is the only way to time how long a cold acquisition takes
        // (the cost E2's LOW_POWER duty cycle trades against) or to prove a reacquisition ran at BALANCED.
        FileLog.event("Zycle scan start mode=${if (everConnected) "BALANCED" else "LOW_POWER"} " +
            "filter=${if (pairedAddress.isNotEmpty()) "addr" else "FTMS"}")
        val started = runCatching { scanner.startScan(filters, settings, scanCallback) }
        if (started.isFailure) {
            FileLog.event("Zycle scan start threw: ${started.exceptionOrNull()}"); retryScanLater(); return
        }
        scanning = true   // NOT the place to reset the backoff: a scan that starts can still fail async
    }

    private val rescan: Runnable = Runnable { startScan() }

    /** Backoff, so a connect/fail flap can't drive us into Android's 5-scans-per-30s throttle — which
     *  answers with onScanFailed(6) and used to latch the client dead. */
    private fun retryScanLater(): Unit {
        if (stopped) return
        handler.removeCallbacks(rescan)
        handler.postDelayed(rescan, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(rescan)   // a retry firing later would start a scan nothing ever stops
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ── connect / GATT ──────────────────────────────────────────────────────────────────────────────
    private fun connect(device: BluetoothDevice) {
        if (stopped) { connecting.set(false); return }   // stop() raced a scan result on a binder thread
        // TRANSPORT_LE explicitly: with TRANSPORT_AUTO a device that ever bonded as DUAL is attempted over
        // BR/EDR and fails with status=133 every time.
        val attempt = Any()
        connectAttempts.replace(attempt)
        // Re-checked AFTER the token exists: stop() could have landed between the check above and this line,
        // and it clears no token that had not been registered yet. From here on a stop is guaranteed to
        // invalidate us. No monitor is held across connectGatt() — it can block for as long as the stack likes.
        if (stopped) { connectAttempts.clearIfCurrent(attempt) { connecting.set(false) }; return }
        val g = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (g == null) {   // registerClient failed (client-interface exhaustion / stack restart)
            // Only if we still own the attempt: `connecting` and the retry belong to whoever replaced us.
            if (connectAttempts.clearIfCurrent(attempt) { connecting.set(false) }) {
                FileLog.event("Zycle connectGatt returned null — rescheduling")
                scheduleReconnect()
            }
            return
        }
        // A handle nobody owns: stop(), or a newer attempt, landed while connectGatt blocked. Close it and
        // touch NOTHING else — clearing `connecting` here would release the latch the live attempt holds,
        // letting a scan result start a third connection behind its back.
        if (!connectAttempts.clearIfCurrent(attempt) { gatt = g }) {
            FileLog.event("Zycle connectGatt returned for a retired attempt — closing it")
            runCatching { g.disconnect() }; runCatching { g.close() }
            return
        }
        // Bound to THIS handle: a timeout left over from a previous attempt used to tear down the next one.
        val timeout = Runnable {
            if (!stopped && connecting.get() && gattSessions.clearIfCurrent(g) {
                FileLog.event("Zycle connect timeout ${CONNECT_TIMEOUT_MS}ms -> retry")
                connecting.set(false)
            }) {
                runCatching { g.disconnect() }; runCatching { g.close() }
                scheduleReconnect()
            }
        }
        // No cancellation needed: the Runnable checks `connecting` and its own handle, so once this attempt
        // connects, dies or is superseded it is a no-op. Cancelling it from another thread could — and did —
        // remove the timeout belonging to a LATER attempt.
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gattSessions.runIfCurrent(g) {
                    FileLog.event("Zycle connected status=$status")
                    connecting.set(false)
                    retryMs = SCAN_RETRY_MS   // a good connection resets the backoff
                    everConnected = true      // ...and promotes every later scan to the reacquisition duty cycle
                    lastMessageMs = android.os.SystemClock.elapsedRealtime()   // start the silent-link window at connect
                    syncOwed.set(true); burstEnqueued = false
                    onState(true)
                    handler.post { runCatching { g.discoverServices() } }
                    // Floor under the mirror going on the air. Discovery can fail, be refused by the stack, or
                    // yield a profile with nothing to read; and a lost GATT callback costs OP_TIMEOUT_MS each.
                    // Waiting forever for a perfect sync is worse than advertising with a partial cache.
                    handler.postDelayed({
                        if (syncOwed.get()) FileLog.event("Zycle sync fallback ${SYNC_FALLBACK_MS}ms -> advertising anyway")
                        fireSynced(g)
                    }, SYNC_FALLBACK_MS)
                }) runCatching { g.close() }   // orphaned handle — drop it
            } else {
                FileLog.event("Zycle disconnected status=$status")
                runCatching { g.close() }
                if (gattSessions.clearIfCurrent(g) {
                    onState(false)
                    completePendingWrites(false)
                    opQueue.clear(); opBusy.set(false); syncOwed.set(false); burstEnqueued = false
                    connecting.set(false); inFlightWrite = null; lastMessageMs = 0L
                }) scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (stopped) return
            gattSessions.runIfCurrent(g) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(tag, "discover failed $status"); FileLog.event("Zycle discover FAILED status=$status")
                    return@runIfCurrent
                }
                lastMessageMs = android.os.SystemClock.elapsedRealtime()
                val profile = buildProfile(g)
                FileLog.event("Zycle profile: " + profile.services.joinToString("; ") { s ->
                    "${s.uuid}[" + s.chars.joinToString(",") { "${shortUuid(it.uuid)}(p=${it.properties})" } + "]"
                })
                onProfile(profile)
                // Subscribe to every notify/indicate char, and read every readable char once — all serialised.
                val svcs = g.services.filterNot { GattUuids.isStackService(it.uuid) }
                // BEFORE the loops: an op the stack refuses completes synchronously inside pump(), so the
                // whole burst can drain right here. The mirror must wait for the FULL queue to drain, not
                // merely for the first subscription, or its read cache is still cold when it advertises.
                burstEnqueued = true
                // try/finally is load-bearing: an exception while building leaves no permanent queue latch.
                burstBuilding = true
                try {
                    // DATA STREAMS FIRST. Reads can take 0.5-2 s over the air; putting them first punches that
                    // gap in every reconnect. The cold-cache invariant still gates advertising on full drain.
                    for (svc in svcs) for (ch in svc.characteristics)
                        if ((ch.uuid == GattUuids.INDOOR_BIKE_DATA || ch.uuid == GattUuids.CYCLING_POWER_MEASUREMENT) &&
                            ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                            enqueueSubscribe(g, ch)
                    // READS NEXT: apps read FTMS Feature immediately and cache an empty answer for the session.
                    for (svc in svcs) for (ch in svc.characteristics)
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) enqueueRead(g, ch)
                    for (svc in svcs) for (ch in svc.characteristics)
                        if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                            enqueueSubscribe(g, ch)
                } finally { burstBuilding = false }
                // Also drains a profile with nothing readable/notifiable instead of hanging the sync latch.
                pump()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattSessions.runIfCurrent(g) {
                val u = descriptor.characteristic.uuid
                if (status != BluetoothGatt.GATT_SUCCESS)
                    FileLog.event("Zycle subscribe ${shortUuid(u)} FAILED status=$status")
                lastMessageMs = android.os.SystemClock.elapsedRealtime()
                opDone()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            FileLog.event("Zycle mtu=$mtu status=$status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            gattSessions.runIfCurrent(g) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val v = @Suppress("DEPRECATION") (ch.value?.copyOf() ?: ByteArray(0))
                    FileLog.event("Zycle read ${shortUuid(ch.uuid)} = ${FileLog.hex(v)}")   // identity/feature/ranges values
                    onValue(ch.uuid, v)
                } else FileLog.event("Zycle read ${shortUuid(ch.uuid)} failed status=$status")
                lastMessageMs = android.os.SystemClock.elapsedRealtime()
                opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            gattSessions.runIfCurrent(g) {
                lastMessageMs = android.os.SystemClock.elapsedRealtime()
                // Attribute a status only to the write this callback names. A late callback must not retry
                // whichever newer ERG target happens to occupy the slot.
                val w = inFlightWrite?.takeIf { it.session === g && it.uuid == ch.uuid }
                if (w != null) inFlightWrite = null
                if (w != null && status == BluetoothGatt.GATT_SUCCESS) {
                    completeWrite(w, true)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Decide supersession when the delayed retry runs. A newer command must not relabel a
                    // write the Android stack already accepted while this callback was outstanding.
                    val retry = w != null && canRetry(w)
                    FileLog.event("Zycle write ${shortUuid(ch.uuid)} status=$status" + if (retry) " — retry ${w!!.retriesLeft}" else "")
                    if (retry) scheduleWriteRetry(w!!) else if (w != null) completeWrite(w, false)
                }
                opDone()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (stopped) return   // in-flight notification after stop() — not our data any more
            // The whole relay stays INSIDE the session gate. receiveOwner identifies the source OBJECT, not
            // the GATT handle, so a frame from a handle this client already retired still passes it — and in
            // MirrorServer it would consume the reanchorLevel the disconnect just armed for the replacement,
            // reporting an outage's level movement as a rider button press. Cheap to hold: the notify
            // fan-out is posted to main by MirrorServer (see onZycleValue), never called under this monitor.
            gattSessions.runIfCurrent(g) {
                val value = @Suppress("DEPRECATION") (ch.value?.copyOf() ?: ByteArray(0))
                lastMessageMs = android.os.SystemClock.elapsedRealtime()
                logNotif(ch.uuid, value)
                onValue(ch.uuid, value)
            }
        }
    }

    /** Every notification, unthrottled: a dropped sample is exactly what you need when diagnosing a gap. */
    private fun logNotif(uuid: UUID, value: ByteArray) {
        if (!FileLog.enabled) return
        FileLog.event("Zycle notif ${shortUuid(uuid)} = ${FileLog.hex(value)}")
    }

    private fun shortUuid(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
    }

    private fun buildProfile(g: BluetoothGatt): GattProfile = GattProfile(
        g.services.filterNot { GattUuids.isStackService(it.uuid) }.map { svc ->
            SvcSpec(svc.uuid, svc.type == BluetoothGattService.SERVICE_TYPE_PRIMARY, svc.characteristics.map { ch ->
                CharSpec(ch.uuid, ch.properties, ch.permissions, ch.descriptors.map { it.uuid })
            })
        }
    )

    private fun enqueueSubscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, retry: Boolean = true): Unit = enqueue {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(cccd)
        if (d == null) { FileLog.event("Zycle subscribe ${shortUuid(ch.uuid)} — no CCCD"); opDone(); return@enqueue }
        FileLog.event("Zycle subscribe ${shortUuid(ch.uuid)}")
        @Suppress("DEPRECATION")
        run {
            d.value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (g.writeDescriptor(d) != true) {
                FileLog.event("Zycle subscribe ${shortUuid(ch.uuid)} REFUSED by stack" + if (retry) " — requeueing" else " — giving up")
                if (retry) handler.postDelayed({ if (!stopped && gatt === g) enqueueSubscribe(g, ch, retry = false) }, OP_REQUEUE_MS)
                opDone()
            }
        }
    }

    private fun enqueueRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, retry: Boolean = true): Unit = enqueue {
        if (g.readCharacteristic(ch) != true) {
            FileLog.event("Zycle read ${shortUuid(ch.uuid)} REFUSED by stack" + if (retry) " — requeueing" else " — giving up")
            if (retry) handler.postDelayed({ if (!stopped && gatt === g) enqueueRead(g, ch, retry = false) }, OP_REQUEUE_MS)
            opDone()
        }
    }


    // ── GATT op serialisation ────────────────────────────────────────────────────────────────────────
    private fun enqueue(op: () -> Unit) { opQueue.add(op); pump() }
    /** Set while the opening burst is still being ENQUEUED. `enqueue` pumps on every add, so without this the
     *  first op runs immediately — and an op the stack refuses completes synchronously, draining a queue whose
     *  remaining ops have not been added yet. `pump()` then sees it empty and fires onSynced, putting the
     *  mirror on the air with a cold read cache: the very thing `burstEnqueued` exists to prevent. */
    @Volatile private var burstBuilding = false
    @Volatile private var opWatchdog: Runnable? = null
    private fun pump() {
        if (burstBuilding) return   // nothing may run until the whole burst is queued — see burstBuilding
        if (opBusy.compareAndSet(false, true)) {
            val op = opQueue.poll()
            if (op == null) {
                opBusy.set(false)
                // Drained: the opening burst is done, so the mirror now has every readable value we can give it.
                if (burstEnqueued) gatt?.let { fireSynced(it) }
                return
            }
            val session = gatt
            if (session == null) { opQueue.clear(); opBusy.set(false); return }
            // Per-op watchdog: a LOST GATT callback (flaky link) would otherwise latch opBusy forever and every
            // later control/ERG write would sit undispatched while power keeps streaming (invisible failure).
            // opDone() cancels this on normal completion; token + session identity cover the concurrent edge.
            val token = opToken.incrementAndGet()
            val w = Runnable {
                gattSessions.timeoutIfCurrent(
                    session,
                    stillPending = { opBusy.get() && opToken.get() == token },
                    onRetiring = { FileLog.event("Zycle watchdog: GATT op timeout ${OP_TIMEOUT_MS}ms -> reconnect") },
                )
            }
            opWatchdog = w
            handler.postDelayed(w, OP_TIMEOUT_MS)
            runCatching { op() }.onFailure { opDone() }
        }
    }
    private fun opDone() { opWatchdog?.let { handler.removeCallbacks(it) }; opToken.incrementAndGet(); opBusy.set(false); pump() }

    private companion object {
        const val RECONNECT_DELAY_MS = 2000L
        const val SCAN_RETRY_MS = 2000L
        const val SCAN_RETRY_MAX_MS = 60000L      // backoff ceiling: stay clear of the 5-scans-per-30s throttle
        const val CONNECT_TIMEOUT_MS = 20000L     // no connection callback ever arrives on some stack failures
        const val OP_REQUEUE_MS = 300L
        const val HEARTBEAT_CHECK_MS = 7000L
        const val HEARTBEAT_TIMEOUT_MS = 15000L   // silent this long while "connected" → recycle the link (~15s, ANT-learned)
        const val CONTROL_WRITE_RETRIES = 2       // resend a control write that NAKs (status 133) up to twice
        const val CONTROL_RETRY_DELAY_MS = 250L
        const val OP_TIMEOUT_MS = 4000L           // unstick the GATT queue if a callback is ever lost (flaky link)
        const val SYNC_FALLBACK_MS = 6000L        // go on the air with a partial cache rather than never
    }
}
