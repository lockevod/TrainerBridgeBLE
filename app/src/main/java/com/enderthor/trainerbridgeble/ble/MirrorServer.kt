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
    private val correction: () -> PowerCorrection,
    private val toZycle: (charUuid: UUID, bytes: ByteArray, withResponse: Boolean) -> Unit,
    private val onStatus: (String) -> Unit = {},
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

    fun start() {
        val srv = runCatching { mgr.openGattServer(context, serverCallback) }.getOrNull()
        if (srv == null) { onStatus("no se pudo abrir el servidor BLE"); return }
        server = srv
        originalName = runCatching { adapter.name }.getOrNull()
        runCatching { if (adapter.name != ADVERTISED_NAME) adapter.name = ADVERTISED_NAME }
    }

    /** Build the mirrored server from the trainer's discovered GATT. Services are added one at a time. */
    fun build(profile: GattProfile) {
        val srv = server ?: return
        chars.clear(); pendingServices.clear()
        for (svc in profile.services) {
            if (GattUuids.isStackService(svc.uuid)) continue
            val service = BluetoothGattService(svc.uuid,
                if (svc.primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY)
            for (cs in svc.chars) {
                val ch = BluetoothGattCharacteristic(cs.uuid, cs.properties, cs.permissions)
                for (dU in cs.descriptors) ch.addDescriptor(BluetoothGattDescriptor(dU,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                // Ensure a CCCD exists on notify/indicate chars even if the trainer didn't list it.
                if (cs.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && ch.getDescriptor(cccd) == null)
                    ch.addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                service.addCharacteristic(ch)
                chars[cs.uuid] = ch
            }
            pendingServices.add(service)
        }
        addNextService()
    }

    private fun addNextService() { pendingServices.poll()?.let { s -> runCatching { server?.addService(s) } } }

    fun stop() {
        stopAdvertising()
        runCatching { server?.close() }
        server = null
        originalName?.let { orig -> runCatching { adapter.name = orig } }; originalName = null
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
        handler.post {
            val srv = server ?: return@post
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            for (addr in subs.toList()) {
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
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) { addNextService() ; if (pendingServices.isEmpty()) startAdvertising() }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                clients[device.address] = device; onStatus("app conectada (${clients.size})")
            } else {
                clients.remove(device.address); subscribers.values.forEach { it.remove(device.address) }
                onStatus("app desconectada (${clients.size})")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic?) {
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
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; onStatus("anunciando $ADVERTISED_NAME") }
        override fun onStartFailure(errorCode: Int) { advertising = false; onStatus("fallo al anunciar ($errorCode)") }
    }

    private fun startAdvertising() {
        if (advertising) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { onStatus("este móvil no soporta anunciar BLE"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1826)))   // FTMS — apps scan for this
            .build()
        val scanResp = AdvertiseData.Builder().setIncludeDeviceName(true).build()   // carries the renamed name
        runCatching { advertiser.startAdvertising(settings, data, scanResp, advCallback) }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
    }

    private companion object {
        const val ADVERTISED_NAME = "ZycleBike2 TB"
    }
}
