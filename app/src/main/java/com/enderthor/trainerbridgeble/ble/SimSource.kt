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
    private var distanceM = 0.0   // reported in Indoor Bike Data, like the real trainer
    private var elapsedS = 0
    private var ticks = 0
    private var crankTurns = 0.0; private var wheelTurns = 0.0   // fractional accumulators, reported as integers
    private var crankRevs = 0; private var wheelRevs = 0
    private var crankEvt1024 = 0; private var wheelEvt2048 = 0   // CPS event timestamps, uint16 wrapping
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

    /** Report the resistance to the apps two ways, like a real Zycle: FTMS Machine Status (standard) AND the
     *  Zycle-style proprietary characteristic carrying the raw FE-C Basic Resistance page (buttons). */
    private fun emitResistance() {
        val level = resistance   // sim: level == % (0..100)
        onValue(STATUS, byteArrayOf(0x07, (level and 0xFF).toByte(), ((level shr 8) and 0xFF).toByte()))
        val units = (resistance * 2).coerceIn(0, 200)   // FE-C 0.5% units
        onValue(PROP_BUTTON, byteArrayOf(0x30, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), units.toByte()))
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
            distanceM += speedKmh / 3.6 * (TICK_MS / 1000.0)
            ticks++; elapsedS = ticks * TICK_MS / 1000
            crankTurns += cadence / 60.0 * (TICK_MS / 1000.0)
            wheelTurns += speedKmh / 3.6 * (TICK_MS / 1000.0) / WHEEL_M
            crankRevs = crankTurns.toInt() and 0xFFFF; wheelRevs = wheelTurns.toInt()
            crankEvt1024 = (crankEvt1024 + TICK_MS * 1024 / 1000) and 0xFFFF
            wheelEvt2048 = (wheelEvt2048 + TICK_MS * 2048 / 1000) and 0xFFFF
            onValue(IBD, indoorBikeData(power, cadence, speedKmh, resistance))
            onValue(CPM, cyclingPower(power))
            handler.postDelayed(this, TICK_MS.toLong())
        }
    }

    override fun start() {
        onState(true)
        onProfile(simProfile())
        FileLog.event("SIM trainer started")
        // Seed the mirror's read cache for the readable chars. These are the REAL trainer's values (captured
        // from a Zycle): apps gate their UI on them — declaring less than the trainer does (no resistance
        // level, no Indoor Bike Simulation Parameters) is what greys out an app's automatic/ERG mode.
        onValue(FEATURE, byteArrayOf(0x86.toByte(), 0x50, 0, 0, 0x0C, 0xE0.toByte(), 0, 0))
        onValue(POWER_RANGE, byteArrayOf(0, 0, 0xA0.toByte(), 0x0F, 1, 0))       // 0..4000 W
        onValue(RES_RANGE, byteArrayOf(0, 0, 0xC8.toByte(), 0, 1, 0))            // 0..200
        onValue(CP_FEATURE, byteArrayOf(0x0C, 0, 0x04, 0))
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
        // Control Point Response indication (0x80 <reqOp> <result>) — a real trainer sends this, and apps
        // gate their ERG handshake on it, so emit it or sim control never "takes". Spin Down (0x13) is
        // declared by the feature value we clone but not implemented: its success response must carry the
        // target speeds, so answer "op code not supported" (0x02) rather than hang a calibrating app.
        val result: Byte = if (op == 0x13) 0x02 else 0x01
        onValue(CONTROL, byteArrayOf(0x80.toByte(), (op and 0xFF).toByte(), result))
    }

    private fun indoorBikeData(power: Int, cadence: Int, speedKmh: Double, resistance: Int): ByteArray {
        val speed = (speedKmh * 100).roundToInt().coerceIn(0, 0xFFFF)   // 0.01 km/h
        val cad = (cadence * 2).coerceIn(0, 0xFFFF)                     // 0.5 rpm
        val dist = distanceM.toInt().coerceIn(0, 0xFFFFFF)              // uint24, metres
        val elapsed = elapsedS.coerceIn(0, 0xFFFF)                      // seconds
        // flags 0x0874, same set the real trainer sends: inst speed (bit0=0) + inst cadence (bit2) +
        // total distance (bit4) + resistance level (bit5) + inst power (bit6) + elapsed time (bit11)
        return byteArrayOf(0x74, 0x08, lo(speed), hi(speed), lo(cad), hi(cad),
            lo(dist), hi(dist), ((dist shr 16) and 0xFF).toByte(),
            lo(resistance), hi(resistance), lo(power), hi(power), lo(elapsed), hi(elapsed))
    }

    /** CPM (0x2A63): flags 0x0030 — wheel (bit4) + crank (bit5) revolution data, the same set the real
     *  trainer sends, and what [CP_FEATURE] now declares. Instantaneous power is mandatory, no flag.
     *  ponytail: the event timestamps advance every tick rather than on a real revolution — a consumer
     *  computes Δrevs/Δtime, which averages out correctly; emit on true revolutions if that ever matters. */
    private fun cyclingPower(power: Int): ByteArray = byteArrayOf(0x30, 0x00, lo(power), hi(power),
        lo(wheelRevs), hi(wheelRevs), ((wheelRevs shr 16) and 0xFF).toByte(), ((wheelRevs shr 24) and 0xFF).toByte(),
        lo(wheelEvt2048), hi(wheelEvt2048),
        lo(crankRevs), hi(crankRevs), lo(crankEvt1024), hi(crankEvt1024))

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
        // Zycle-style proprietary "FE-C over BLE" service — carries the button/resistance page.
        SvcSpec(PROP_SERVICE, true, listOf(CharSpec(PROP_BUTTON, N, 0, listOf(CCCD)))),
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
        val PROP_SERVICE: UUID = UUID.fromString("F03EEE01-4910-473C-BE46-960948C2F59C")
        val PROP_BUTTON: UUID = UUID.fromString("F03EE002-4910-473C-BE46-960948C2F59C")
        val CCCD: UUID = GattUuids.uuid16(0x2902)
        const val TICK_MS = 250
        const val WHEEL_M = 2.096   // 700x23c, for the simulated wheel revolutions
        const val R = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
        const val N = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val W = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
        const val IND = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
    }
}
