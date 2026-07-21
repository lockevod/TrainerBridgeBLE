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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onAdv: (AdvBlueprint) -> Unit = {},   // the trainer's own advertising, for the mirror to clone
    private val onFound: (name: String?, address: String) -> Unit = { _, _ -> },   // for the config screen
) : TrainerSource {
    private val tag = "TBB/ZycleClient"
    private val handler = Handler(Looper.getMainLooper())
    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connecting = false
    @Volatile private var stopped = false
    @Volatile private var scanning = false
    @Volatile private var lastMessageMs = 0L   // wall-clock of the last notification, for the silent-link watchdog
    private val reconnectPending = AtomicBoolean(false)

    private val opQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val opBusy = AtomicBoolean(false)
    private val opToken = java.util.concurrent.atomic.AtomicInteger(0)   // guards the per-op watchdog vs a stale timeout

    private class WriteReq(val uuid: UUID, val bytes: ByteArray, val withResponse: Boolean, val retriesLeft: Int, val seq: Int)
    @Volatile private var inFlightWrite: WriteReq? = null   // the write currently on the wire, for retry on failure
    private val writeSeq = java.util.concurrent.atomic.AtomicInteger(0)  // bumps per write; a retry is dropped if superseded

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun start() {
        stopped = false
        startScan()
        handler.removeCallbacks(heartbeat)   // never stack a second heartbeat
        handler.postDelayed(heartbeat, HEARTBEAT_CHECK_MS)
    }

    override fun stop() {
        stopped = true
        connecting = false
        stopScan()
        handler.removeCallbacks(heartbeat)
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
            if (g != null && lastMessageMs != 0L && System.currentTimeMillis() - lastMessageMs > HEARTBEAT_TIMEOUT_MS) {
                FileLog.event("Zycle watchdog: silent ${System.currentTimeMillis() - lastMessageMs}ms -> reconnect")
                gatt = null; lastMessageMs = 0L
                onState(false)
                opQueue.clear(); opBusy.set(false)
                runCatching { g.disconnect() }; runCatching { g.close() }
                scheduleReconnect()
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

    /** Forward a write to the trainer's characteristic [charUuid] (control relay). Queued. */
    override fun write(charUuid: UUID, bytes: ByteArray, withResponse: Boolean) =
        writeInternal(charUuid, bytes, withResponse, CONTROL_WRITE_RETRIES)

    private fun writeInternal(charUuid: UUID, bytes: ByteArray, withResponse: Boolean, retriesLeft: Int) {
        FileLog.event("Zycle write $charUuid = ${FileLog.hex(bytes)}")
        val g = gatt ?: return
        val ch = g.services.firstNotNullOfOrNull { s -> s.getCharacteristic(charUuid) } ?: return
        val seq = writeSeq.incrementAndGet()
        enqueue {
            @Suppress("DEPRECATION")
            run {
                inFlightWrite = WriteReq(charUuid, bytes, withResponse, retriesLeft, seq)
                ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = bytes
                if (g.writeCharacteristic(ch) != true) { inFlightWrite = null; opDone() }   // couldn't start → free the queue
            }
        }
    }

    // ── scan ────────────────────────────────────────────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (stopped) return   // results already queued in the controller when stop() landed
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name
            // the scan filter already guarantees FTMS/CPS, so unpaired = take the first trainer that answers
            val match = pairedAddress.isEmpty() || dev.address == pairedAddress
            if (match) {
                if (gatt != null || connecting) return   // already connecting/connected — ignore duplicate adverts
                connecting = true
                FileLog.event("Zycle found '$name' ${dev.address} rssi=${result.rssi}")
                onFound(name, dev.address)
                captureAdv(result)
                stopScan(); connect(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) { Log.w(tag, "scan failed $errorCode"); FileLog.event("Zycle scan FAILED code=$errorCode"); if (!stopped) handler.postDelayed({ startScan() }, 2000) }
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

    private fun startScan() {
        if (stopped || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanning = true
        runCatching {
            // Paired → filter by address only: the trainer's advert may carry its UUIDs in the scan
            // response, which the controller's offloaded UUID filter never sees. Unpaired → FTMS only:
            // a bare power meter advertises CPS but can't take ERG, so it must not win the race.
            val filters = if (pairedAddress.isNotEmpty())
                listOf(android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(pairedAddress).build())
            else GattUuids.scanFilters(0x1826)
            // default scan mode (LOW_POWER) as before — this scan runs until the trainer appears
            scanner.startScan(filters, android.bluetooth.le.ScanSettings.Builder().build(), scanCallback)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ── connect / GATT ──────────────────────────────────────────────────────────────────────────────
    private fun connect(device: BluetoothDevice) {
        if (stopped) return
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt !== g) { runCatching { g.close() }; return }   // orphaned handle — drop it
                FileLog.event("Zycle connected status=$status")
                connecting = false
                lastMessageMs = System.currentTimeMillis()   // start the silent-link window at connect
                onState(true)
                handler.post { runCatching { g.discoverServices() } }
            } else {
                FileLog.event("Zycle disconnected status=$status")
                runCatching { g.close() }
                if (gatt !== g) return   // a stale/superseded handle — don't touch the live connection's state
                onState(false)
                opQueue.clear(); opBusy.set(false)
                gatt = null; connecting = false; lastMessageMs = 0L
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (stopped) return   // a stopped client must not hand its profile to whatever replaced it
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(tag, "discover failed $status"); FileLog.event("Zycle discover FAILED status=$status"); return
            }
            val profile = buildProfile(g)
            FileLog.event("Zycle profile: " + profile.services.joinToString("; ") { s ->
                "${s.uuid}[" + s.chars.joinToString(",") { "${shortUuid(it.uuid)}(p=${it.properties})" } + "]"
            })
            onProfile(profile)
            // Subscribe to every notify/indicate char, and read every readable char once — all serialised.
            for (svc in g.services) {
                if (GattUuids.isStackService(svc.uuid)) continue
                for (ch in svc.characteristics) {
                    val p = ch.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                        enqueueSubscribe(g, ch)
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                        enqueueRead(g, ch)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // a failed CCCD write means that characteristic silently never notifies — never let it pass quietly
            if (status != BluetoothGatt.GATT_SUCCESS)
                FileLog.event("Zycle subscribe ${shortUuid(descriptor.characteristic.uuid)} FAILED status=$status")
            opDone()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            FileLog.event("Zycle mtu=$mtu status=$status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val v = @Suppress("DEPRECATION") (ch.value?.copyOf() ?: ByteArray(0))
                FileLog.event("Zycle read ${shortUuid(ch.uuid)} = ${FileLog.hex(v)}")   // identity/feature/ranges values
                onValue(ch.uuid, v)
            } else FileLog.event("Zycle read ${shortUuid(ch.uuid)} failed status=$status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val w = inFlightWrite; inFlightWrite = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Retry a failed CONTROL write (the trainer occasionally NAKs with status 133), but only if a
                // newer control write hasn't superseded it (w.seq == writeSeq) — never re-send a stale target.
                val retry = w != null && w.retriesLeft > 0 && GattUuids.carriesControl(w.uuid) && w.seq == writeSeq.get() && !stopped
                FileLog.event("Zycle write ${shortUuid(ch.uuid)} status=$status" + if (retry) " — retry ${w!!.retriesLeft}" else "")
                if (retry) handler.postDelayed({ writeInternal(w!!.uuid, w.bytes, w.withResponse, w.retriesLeft - 1) }, CONTROL_RETRY_DELAY_MS)
            }
            opDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (stopped) return   // in-flight notification after stop() — not our data any more
            val value = @Suppress("DEPRECATION") (ch.value?.copyOf() ?: ByteArray(0))
            lastMessageMs = System.currentTimeMillis()   // feed the silent-link watchdog
            logNotif(ch.uuid, value)   // all notifications (power included), rate-limited per char
            onValue(ch.uuid, value)
        }
    }

    /** Every notification, unthrottled: a dropped sample is exactly what you need when diagnosing a gap. */
    private fun logNotif(uuid: UUID, value: ByteArray) {
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

    private fun enqueueSubscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) = enqueue {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(cccd)
        if (d == null) { FileLog.event("Zycle subscribe ${shortUuid(ch.uuid)} — no CCCD"); opDone(); return@enqueue }
        FileLog.event("Zycle subscribe ${shortUuid(ch.uuid)}")
        @Suppress("DEPRECATION")
        run {
            d.value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (g.writeDescriptor(d) != true) opDone()
        }
    }

    private fun enqueueRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic) = enqueue {
        if (g.readCharacteristic(ch) != true) opDone()
    }

    // ── GATT op serialisation ────────────────────────────────────────────────────────────────────────
    private fun enqueue(op: () -> Unit) { opQueue.add(op); pump() }
    @Volatile private var opWatchdog: Runnable? = null
    private fun pump() {
        if (opBusy.compareAndSet(false, true)) {
            val op = opQueue.poll()
            if (op == null) { opBusy.set(false); return }
            // Per-op watchdog: a LOST GATT callback (flaky link) would otherwise latch opBusy forever and every
            // later control/ERG write would sit undispatched while power keeps streaming (invisible failure).
            // opDone() cancels this on normal completion; the token guard covers the concurrent-fire edge.
            // ponytail: a real callback arriving >OP_TIMEOUT_MS LATE (not lost — link already badly degraded)
            // can still double-advance for an instant. Self-healing and strictly better than the old permanent
            // wedge; fully closing it needs matching each callback to its op (fragile) — not worth it.
            val token = opToken.incrementAndGet()
            val w = Runnable {
                if (opBusy.get() && opToken.get() == token) {
                    FileLog.event("Zycle GATT op timeout ${OP_TIMEOUT_MS}ms -> unstick queue")
                    inFlightWrite = null
                    opDone()
                }
            }
            opWatchdog = w
            handler.postDelayed(w, OP_TIMEOUT_MS)
            runCatching { op() }.onFailure { opDone() }
        }
    }
    private fun opDone() { opWatchdog?.let { handler.removeCallbacks(it) }; opToken.incrementAndGet(); opBusy.set(false); pump() }

    private companion object {
        const val RECONNECT_DELAY_MS = 2000L
        const val HEARTBEAT_CHECK_MS = 7000L
        const val HEARTBEAT_TIMEOUT_MS = 15000L   // silent this long while "connected" → recycle the link (~15s, ANT-learned)
        const val CONTROL_WRITE_RETRIES = 2       // resend a control write that NAKs (status 133) up to twice
        const val CONTROL_RETRY_DELAY_MS = 250L
        const val OP_TIMEOUT_MS = 4000L           // unstick the GATT queue if a callback is ever lost (flaky link)
    }
}
