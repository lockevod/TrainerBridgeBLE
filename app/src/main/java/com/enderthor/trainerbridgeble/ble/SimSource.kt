package com.enderthor.trainerbridgeble.ble

import android.os.Handler
import android.os.Looper
import com.enderthor.trainerbridgeble.FileLog
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Test-mode trainer: instead of a real Zycle over BLE, presents a synthetic FTMS + Cycling Power GATT and
 * emits power-meter-like Indoor Bike Data / Cycling Power notifications at ~4 Hz, so the whole mirror
 * pipeline (correction → peripheral → apps) can be exercised with no hardware. Control writes are accepted
 * and used to bias the simulated power (so ERG control is visibly reflected).
 */
class SimSource(
    private val onProfile: (GattProfile) -> Unit,
    private val onValue: (charUuid: UUID, value: ByteArray) -> Unit,
    private val onState: (connected: Boolean) -> Unit,
) : TrainerSource {
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random(1)

    private var current = 170.0
    private var target = 170.0
    private var holdTicks = 0
    @Volatile private var ergTarget: Int? = null   // set by a Set Target Power write
    @Volatile var resistance = 20                   // 0..100 %, changed by the emulated buttons or app control
        private set

    /** Emulate the trainer's resistance buttons (test mode). Changes the level and notifies the apps. */
    fun buttonUp() = changeResistance(+5)
    fun buttonDown() = changeResistance(-5)
    private fun changeResistance(delta: Int) {
        resistance = (resistance + delta).coerceIn(0, 100)
        FileLog.event("SIM button → resistance=$resistance%")
        emitResistance()
    }

    /** Report the resistance level to the apps: FTMS Machine Status "Target Resistance Level Changed" (0x07). */
    private fun emitResistance() {
        val level = resistance   // sim: level == % (0..100)
        onValue(STATUS, byteArrayOf(0x07, (level and 0xFF).toByte(), ((level shr 8) and 0xFF).toByte()))
    }

    private val ticker = object : Runnable {
        override fun run() {
            val erg = ergTarget
            if (erg != null) {
                target = erg.toDouble()
            } else if (holdTicks <= 0) {
                if (rng.nextInt(5) == 0) { target = 0.0; holdTicks = 12 + rng.nextInt(20) }
                else { target = (120 + rng.nextInt(141)).toDouble(); holdTicks = 80 + rng.nextInt(161) }
            }
            holdTicks--
            current += (target - current).coerceIn(-2.5, 2.5)
            val power = (current + (rng.nextInt(11) - 5)).roundToInt().coerceAtLeast(0)
            val cadence = 87 + (rng.nextInt(7) - 3)
            val speedKmh = 18.0 + power * 0.055
            onValue(IBD, indoorBikeData(power, cadence, speedKmh, resistance))
            onValue(CPM, cyclingPower(power))
            handler.postDelayed(this, 250)
        }
    }

    override fun start() {
        onState(true)
        onProfile(simProfile())
        FileLog.event("SIM trainer started")
        // Seed the mirror's read cache for the readable chars.
        onValue(FEATURE, byteArrayOf(0x02, 0x40, 0, 0, 0x0C, 0, 0, 0))          // machine 0x4002, target 0x000C
        onValue(POWER_RANGE, byteArrayOf(0, 0, 0xD0.toByte(), 0x07, 1, 0))       // 0..2000 W
        onValue(RES_RANGE, byteArrayOf(0, 0, 0xC8.toByte(), 0, 1, 0))            // 0..200
        onValue(CP_FEATURE, byteArrayOf(0, 0, 0, 0))
        onValue(SENSOR_LOC, byteArrayOf(0))
        emitResistance()
        handler.post(ticker)
    }

    override fun stop() { handler.removeCallbacks(ticker); onState(false); FileLog.event("SIM trainer stopped") }

    override fun write(charUuid: UUID, bytes: ByteArray, withResponse: Boolean) {
        FileLog.event("SIM write ${bytes.joinToString("") { "%02X".format(it) }}")
        if (charUuid != CONTROL || bytes.isEmpty()) return
        val op = bytes[0].toInt() and 0xFF
        when (op) {
            0x05 -> if (bytes.size >= 3) ergTarget = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)  // Set Target Power
            0x04 -> if (bytes.size >= 2) { resistance = (bytes[1].toInt() and 0xFF).coerceIn(0, 100); emitResistance() }   // Set Target Resistance (app → down)
            0x01 -> ergTarget = null   // Reset
        }
        // Control Point Response indication (0x80 <reqOp> <success>) — a real trainer sends this, and apps
        // gate their ERG handshake on it, so emit it or sim control never "takes".
        onValue(CONTROL, byteArrayOf(0x80.toByte(), (op and 0xFF).toByte(), 0x01))
    }

    private fun indoorBikeData(power: Int, cadence: Int, speedKmh: Double, resistance: Int): ByteArray {
        val speed = (speedKmh * 100).roundToInt().coerceIn(0, 0xFFFF)   // 0.01 km/h
        val cad = (cadence * 2).coerceIn(0, 0xFFFF)                     // 0.5 rpm
        // flags 0x0064: inst speed (bit0=0) + inst cadence (bit2) + resistance level (bit5) + inst power (bit6)
        return byteArrayOf(0x64, 0x00, lo(speed), hi(speed), lo(cad), hi(cad), lo(resistance), hi(resistance), lo(power), hi(power))
    }

    // CPM (0x2A63): flags 0x0000 (power-only; instantaneous power is mandatory at bytes 2-3, no flag).
    private fun cyclingPower(power: Int): ByteArray = byteArrayOf(0x00, 0x00, lo(power), hi(power))

    private fun lo(v: Int) = (v and 0xFF).toByte()
    private fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()

    private fun simProfile() = GattProfile(listOf(
        SvcSpec(GattUuids.uuid16(0x1826), true, listOf(
            CharSpec(FEATURE, R, 0),
            CharSpec(IBD, N, 0, listOf(CCCD)),
            CharSpec(CONTROL, W or IND, 0, listOf(CCCD)),
            CharSpec(STATUS, N, 0, listOf(CCCD)),
            CharSpec(POWER_RANGE, R, 0),
            CharSpec(RES_RANGE, R, 0),
        )),
        SvcSpec(GattUuids.uuid16(0x1818), true, listOf(
            CharSpec(CPM, N, 0, listOf(CCCD)),
            CharSpec(CP_FEATURE, R, 0),
            CharSpec(SENSOR_LOC, R, 0),
        )),
    ))

    private companion object {
        val IBD = GattUuids.uuid16(0x2AD2)
        val CPM = GattUuids.uuid16(0x2A63)
        val CONTROL = GattUuids.uuid16(0x2AD9)
        val STATUS = GattUuids.uuid16(0x2ADA)
        val FEATURE = GattUuids.uuid16(0x2ACC)
        val POWER_RANGE = GattUuids.uuid16(0x2AD8)
        val RES_RANGE = GattUuids.uuid16(0x2AD6)
        val CP_FEATURE = GattUuids.uuid16(0x2A65)
        val SENSOR_LOC = GattUuids.uuid16(0x2A5D)
        val CCCD: UUID = GattUuids.uuid16(0x2902)
        const val R = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
        const val N = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val W = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
        const val IND = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}
