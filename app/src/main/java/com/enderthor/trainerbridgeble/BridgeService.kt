package com.enderthor.trainerbridgeble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.enderthor.trainerbridgeble.ant.AntFecTx
import com.enderthor.trainerbridgeble.ant.PowerSample
import com.enderthor.trainerbridgeble.ble.MirrorServer
import com.enderthor.trainerbridgeble.ble.SimSource
import com.enderthor.trainerbridgeble.ble.TrainerSource
import com.enderthor.trainerbridgeble.ble.ZycleClient

/**
 * Foreground service that runs the whole BLE proxy: a [ZycleClient] (central to the trainer) feeds a
 * [MirrorServer] (peripheral to the apps). Power is corrected inside the mirror using the LIVE [Config];
 * app control writes are relayed back to the trainer. Holds a wakelock so it keeps running screen-off.
 */
class BridgeService : Service() {

    inner class LocalBinder : Binder() { val service: BridgeService get() = this@BridgeService }
    private val binder = LocalBinder()

    private var client: TrainerSource? = null
    private var simSource: SimSource? = null
    private var mirror: MirrorServer? = null
    private var antTx: AntFecTx? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var status: String = "parado"; private set
    @Volatile var zycleConnected: Boolean = false; private set
    @Volatile var lastRawW: Int? = null; private set
    @Volatile var lastCorrectedW: Int? = null; private set
    @Volatile var lastSpeedKmh: Double? = null; private set
    @Volatile var lastCadence: Int? = null; private set
    @Volatile var lastControl: String? = null; private set
    @Volatile var lastSampleMs: Long = 0L; private set   // wall-clock of the last trainer sample, for UI staleness
    @Volatile private var lastResistance: Int? = null
    @Volatile private var antEnabled = false
    @Volatile var antOk = false; private set
    @Volatile var antStatus: String = ""; private set
    @Volatile var bleAdvOk = true; private set   // optimistic: only a real advertise FAILURE flips it false
    @Volatile var listener: (() -> Unit)? = null
    val isRunning: Boolean get() = mirror != null
    val isSimulating: Boolean get() = simSource != null
    val resistance: Int? get() = simSource?.resistance ?: lastResistance

    /** The single most important warning to surface, or null if healthy. Checked by the UI so a green
     *  "Conectado" can never hide a dead ANT dongle or a BLE that isn't actually advertising. */
    val alert: String? get() = when {
        !isRunning -> null
        !bleAdvOk -> "BLE no anuncia"
        antEnabled && !antOk -> "ANT+ sin señal (¿dongle?)"
        else -> null
    }

    /** Nudge the resistance from the on-screen buttons: the synthetic trainer in test mode, or the real
     *  Zycle over BLE (Set Target Resistance, the same op the apps use — stays transparent). */
    fun buttonUp() = nudgeResistance(+5)
    fun buttonDown() = nudgeResistance(-5)
    private fun nudgeResistance(delta: Int) {
        val sim = simSource
        if (sim != null) { if (delta > 0) sim.buttonUp() else sim.buttonDown() }
        else {
            val target = ((lastResistance ?: 0) + delta).coerceIn(0, 200)   // 0..200 per the Zycle's 0x2AD6 range
            // ponytail: 0x04+level% Set Target Resistance, no Request Control first — shares the FTMS control point with the app
            client?.write(com.enderthor.trainerbridgeble.ble.GattUuids.FTMS_CONTROL_POINT, byteArrayOf(0x04, target.toByte()), true)
            lastControl = "Resistencia → $target%"; FileLog.event("UI button → resistance target=$target%")
        }
        listener?.invoke()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { listener = null; return super.onUnbind(intent) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPipeline(); stopSelf() }
            else -> startPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        if (mirror != null) return
        val config = Config(this)
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Android 14+ rejects a connectedDevice FGS if no Bluetooth permission is granted yet → don't crash.
            FileLog.init(this); FileLog.event("startForeground failed: ${e.message}")
            status = "falta permiso Bluetooth"; listener?.invoke(); stopSelf(); return
        }
        acquireWakeLock()
        FileLog.init(this); FileLog.enabled = config.loggingEnabled
        FileLog.event("bridge start scaleAdj=${config.scaleAdjustPercent}% offset=${config.offsetW}W prefix=${config.namePrefix} sim=${config.simulate}")

        val m = MirrorServer(
            context = this,
            advertisedName = config.advertisedName,
            correction = { config.correction() },
            toZycle = { uuid, bytes, withResponse ->
                if (com.enderthor.trainerbridgeble.ble.GattUuids.carriesControl(uuid)) { lastControl = describeControl(bytes); listener?.invoke() }
                client?.write(uuid, bytes, withResponse)
            },
            onStatus = { s -> status = s; listener?.invoke() },
            onAdvState = { ok -> bleAdvOk = ok; listener?.invoke() },
        )
        val onProfile: (com.enderthor.trainerbridgeble.ble.GattProfile) -> Unit = { profile -> m.build(profile) }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value -> m.onZycleValue(uuid, value); cacheForUi(config, uuid, value) }
        val onState: (Boolean) -> Unit = { connected -> zycleConnected = connected; status = if (connected) "trainer conectado" else "buscando trainer…"; listener?.invoke() }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState).also { simSource = it }
        else ZycleClient(this, config.namePrefix, config.pairedAddress, onProfile, onValue, onState,
            onAdv = { bp -> m.setAdvBlueprint(bp) })   // clone the trainer's real advertising
        m.start()
        c.start()
        if (config.antOutputEnabled) {
            antEnabled = true; antOk = false; antStatus = "iniciando…"
            antTx = AntFecTx(this, deviceNumber = config.antDeviceId, onState = { ok, detail -> antOk = ok; antStatus = detail; listener?.invoke() })
                .also { it.start(); FileLog.event("ANT+ output enabled id=${config.antDeviceId}") }
        }
        mirror = m; client = c
    }

    /** Human-readable summary of a control write the app sent (shown in the UI: "app → trainer"). */
    private fun describeControl(b: ByteArray): String = when {
        b.isEmpty() -> "?"
        (b[0].toInt() and 0xFF) == 0x05 && b.size >= 3 -> "ERG ${(b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)} W"
        (b[0].toInt() and 0xFF) == 0x04 && b.size >= 2 -> "Resistencia ${b[1].toInt() and 0xFF}"
        (b[0].toInt() and 0xFF) == 0x01 -> "Reset"
        (b[0].toInt() and 0xFF) == 0x07 -> "Start"
        (b[0].toInt() and 0xFF) == 0x08 -> "Stop"
        else -> "op 0x%02X".format(b[0].toInt() and 0xFF)
    }

    /** Update the monitor tiles from the trainer's data. Indoor Bike Data (0x2AD2) carries speed + cadence
     *  + power; Cycling Power (0x2A63) is a power-only fallback. Power is the RAW value (we show raw →
     *  corrected); speed/cadence pass through. */
    private fun cacheForUi(config: Config, uuid: java.util.UUID, value: ByteArray) {
        if (uuid == com.enderthor.trainerbridgeble.ble.GattUuids.INDOOR_BIKE_DATA ||
            uuid == com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT) lastSampleMs = System.currentTimeMillis()
        when (uuid) {
            com.enderthor.trainerbridgeble.ble.GattUuids.INDOOR_BIKE_DATA -> {
                if (value.size < 2) return
                val flags = le16(value, 0); var off = 2
                if (flags and (1 shl 0) == 0) { if (off + 2 <= value.size) lastSpeedKmh = le16(value, off) * 0.01; off += 2 }
                if (flags and (1 shl 1) != 0) off += 2
                if (flags and (1 shl 2) != 0) { if (off + 2 <= value.size) lastCadence = le16(value, off) / 2; off += 2 }
                if (flags and (1 shl 3) != 0) off += 2
                if (flags and (1 shl 4) != 0) off += 3
                if (flags and (1 shl 5) != 0) { if (off + 2 <= value.size) lastResistance = le16(value, off); off += 2 }
                if (flags and (1 shl 6) != 0 && off + 2 <= value.size) { val raw = le16signed(value, off); lastRawW = raw; lastCorrectedW = config.correction().correct(raw) }
                antTx?.setLatest(PowerSample(lastCorrectedW, lastCadence, lastSpeedKmh?.let { it / 3.6 }))   // corrected → Garmin over ANT+
                listener?.invoke()
            }
            com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT -> {
                if (lastRawW == null && value.size >= 4) {   // fallback only if no Indoor Bike Data
                    val raw = le16signed(value, 2); lastRawW = raw; lastCorrectedW = config.correction().correct(raw); listener?.invoke()
                }
            }
        }
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()

    private fun stopPipeline() {
        if (mirror != null) FileLog.event("bridge stop")
        client?.stop(); client = null; simSource = null
        antTx?.stop(); antTx = null
        mirror?.stop(); mirror = null
        zycleConnected = false; lastRawW = null; lastCorrectedW = null; lastSpeedKmh = null; lastCadence = null; lastResistance = null; lastControl = null; lastSampleMs = 0L; status = "parado"
        antEnabled = false; antOk = false; antStatus = ""; bleAdvOk = true
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        listener?.invoke()   // let a bound UI re-render (Stop → Start) after the async stop
    }

    override fun onDestroy() { stopPipeline(); super.onDestroy() }
    override fun onTimeout(startId: Int) { stopPipeline(); stopSelf() }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrainerBridgeBLE:session").also { runCatching { it.acquire() } }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) runCatching { it.release() } }; wakeLock = null }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TrainerBridge BLE", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MonitorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TrainerBridge BLE")
            .setContentText("Proxy BLE activo")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        private const val CHANNEL_ID = "bridge-ble"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.enderthor.trainerbridgeble.STOP"

        fun start(context: android.content.Context) = context.startForegroundService(Intent(context, BridgeService::class.java))
        fun stop(context: android.content.Context) = context.startService(Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
    }
}
