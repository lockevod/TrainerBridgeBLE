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
 * BLE central to the trainer (Zycle). Scans by name prefix, connects, discovers the FULL GATT, subscribes
 * to every notify/indicate characteristic, reads the readable ones once (for the mirror's read cache), and
 * relays incoming values. Exposes [write] so the mirror can forward app writes (control) to the trainer.
 *
 * All BluetoothGatt operations are SERIALISED through [opQueue] — Android allows only one GATT op in flight;
 * issuing a second before the first's callback silently drops it.
 */
@SuppressLint("MissingPermission")   // BLUETOOTH_SCAN/CONNECT are declared and requested by the UI before start()
class ZycleClient(
    private val context: Context,
    private val namePrefix: String,
    private val onProfile: (GattProfile) -> Unit,
    private val onValue: (charUuid: UUID, value: ByteArray) -> Unit,   // notifications AND initial reads
    private val onState: (connected: Boolean) -> Unit,
) {
    private val tag = "TBB/ZycleClient"
    private val handler = Handler(Looper.getMainLooper())
    private val adapter by lazy { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var stopped = false
    @Volatile private var scanning = false

    private val opQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val opBusy = AtomicBoolean(false)

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun start() {
        stopped = false
        startScan()
    }

    fun stop() {
        stopped = true
        stopScan()
        opQueue.clear(); opBusy.set(false)
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    /** Forward a write to the trainer's characteristic [charUuid] (control relay). Queued. */
    fun write(charUuid: UUID, bytes: ByteArray, withResponse: Boolean) {
        FileLog.event("Zycle write $charUuid = ${FileLog.hex(bytes)}")
        val g = gatt ?: return
        val ch = g.services.firstNotNullOfOrNull { s -> s.getCharacteristic(charUuid) } ?: return
        enqueue {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = bytes
                if (g.writeCharacteristic(ch) != true) opDone()   // couldn't start → free the queue
            }
        }
    }

    // ── scan ────────────────────────────────────────────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name
            if (name != null && name.startsWith(namePrefix, ignoreCase = true)) {
                FileLog.event("Zycle found '$name' ${dev.address} rssi=${result.rssi}"); stopScan(); connect(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) { Log.w(tag, "scan failed $errorCode"); if (!stopped) handler.postDelayed({ startScan() }, 2000) }
    }

    private fun startScan() {
        if (stopped || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanning = true
        runCatching { scanner.startScan(scanCallback) }
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
                FileLog.event("Zycle connected status=$status")
                onState(true)
                handler.post { runCatching { g.discoverServices() } }
            } else {
                FileLog.event("Zycle disconnected status=$status")
                onState(false)
                opQueue.clear(); opBusy.set(false)
                runCatching { g.close() }
                if (gatt === g) gatt = null
                if (!stopped) handler.postDelayed({ startScan() }, 2000)   // reconnect
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(tag, "discover failed $status"); return }
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

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { opDone() }

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
            if (status != BluetoothGatt.GATT_SUCCESS) FileLog.event("Zycle write ${shortUuid(ch.uuid)} status=$status")
            opDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = @Suppress("DEPRECATION") (ch.value?.copyOf() ?: ByteArray(0))
            logNotif(ch.uuid, value)   // all notifications (power included), rate-limited per char
            onValue(ch.uuid, value)
        }
    }

    private val notifLogMs = java.util.concurrent.ConcurrentHashMap<UUID, Long>()
    private fun logNotif(uuid: UUID, value: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - (notifLogMs[uuid] ?: 0L) < 500L) return
        notifLogMs[uuid] = now
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
    private fun pump() { if (opBusy.compareAndSet(false, true)) { val op = opQueue.poll(); if (op == null) opBusy.set(false) else runCatching { op() }.onFailure { opDone() } } }
    private fun opDone() { opBusy.set(false); pump() }
}
