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
import com.enderthor.trainerbridgeble.ble.AdvBlueprint
import com.enderthor.trainerbridgeble.ble.GattProfile
import com.enderthor.trainerbridgeble.ble.MirrorServer
import com.enderthor.trainerbridgeble.ble.SimSource
import com.enderthor.trainerbridgeble.ble.TrainerSource
import com.enderthor.trainerbridgeble.ble.ZycleClient

/**
 * Foreground service split into two independently-controlled halves under a master switch:
 *  - RECEIVE (always-on while master): a [ZycleClient]/[SimSource] central
 *    to the trainer, feeding [CorrectedFeed] + the monitor tiles + optional ANT output.
 *  - EMIT (Start-gated): a [MirrorServer] peripheral to the apps (Bestcycling + Garmin) that clones the
 *    trainer's GATT and corrects power; app control writes relay back to the trainer via the receive central.
 * The service stays foreground while master is on; only ACTION_MASTER_OFF shuts it down.
 */
class BridgeService : Service() {

    inner class LocalBinder : Binder() { val service: BridgeService get() = this@BridgeService }
    private val binder = LocalBinder()

    private var client: TrainerSource? = null
    private var simSource: SimSource? = null
    private var mirror: MirrorServer? = null
    private var antTx: AntFecTx? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var lastProfile: GattProfile? = null       // trainer GATT, cached so a late-started mirror can build
    @Volatile private var lastAdvBlueprint: AdvBlueprint? = null  // trainer's advertising, cached for a late-started mirror

    @Volatile var status: String = ""; private set
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
    @Volatile var emitting = false; private set   // the mirror + ANT half is active (Start pressed)
    @Volatile private var foreground = false
    @Volatile var listener: (() -> Unit)? = null
    val receiving: Boolean get() = client != null || simSource != null
    val isRunning: Boolean get() = receiving || emitting
    val isSimulating: Boolean get() = simSource != null
    val resistance: Int? get() = simSource?.resistance ?: lastResistance

    /** The single most important warning to surface, or null if healthy. Checked by the UI so a green
     *  "Conectado" can never hide a dead ANT dongle or a BLE that isn't actually advertising. */
    val alert: String? get() = when {
        !emitting -> null   // ble/ant health only meaningful while the mirror is broadcasting
        !bleAdvOk -> getString(R.string.alert_ble_not_advertising)
        antEnabled && !antOk -> getString(R.string.alert_ant_no_channel) + (if (antStatus.isNotBlank()) " ($antStatus)" else "")
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
            lastControl = getString(R.string.control_resistance_target, target); FileLog.event("UI button → resistance target=$target%")
        }
        listener?.invoke()
    }

    override fun onCreate() { super.onCreate(); status = getString(R.string.status_stopped) }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { listener = null; return super.onUnbind(intent) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MASTER_ON -> { if (goForeground()) maybeStartReceive() }
            ACTION_MASTER_OFF -> {
                stopEmit(); stopReceive(); releaseWakeLock()
                foreground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            }
            ACTION_EMIT_START -> { if (foreground) startEmit() }   // never emit from a non-foreground (master-off) service
            ACTION_EMIT_STOP -> stopEmit()
            else -> {
                // Null intent = a START_STICKY restart after a process kill. Re-derive from persisted config:
                // if the master is on, come back up (foreground + receive); otherwise there's nothing to do.
                if (Config(this).masterEnabled) { if (goForeground()) maybeStartReceive() } else stopSelf()
            }
        }
        return START_STICKY
    }

    /** Enter the foreground (master on). Returns false if startForeground was rejected (missing BT perm). */
    private fun goForeground(): Boolean {
        if (foreground) return true
        createChannel()
        FileLog.init(this); FileLog.enabled = Config(this).loggingEnabled
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Android 14+ rejects a connectedDevice FGS if no Bluetooth permission is granted yet → don't crash.
            FileLog.event("startForeground failed: ${e.message}")
            status = getString(R.string.status_missing_bt_permission); listener?.invoke(); stopSelf(); return false
        }
        foreground = true
        acquireWakeLock()
        return true
    }

    private fun maybeStartReceive() {
        val config = Config(this)
        if (config.masterEnabled && client == null && simSource == null) startReceive()
    }

    // ── receive half (trainer link → CorrectedFeed + tiles + ANT) ─────────────────────────────────────
    private fun startReceive() {
        if (client != null || simSource != null) return   // idempotent
        val config = Config(this)
        FileLog.event("receive start prefix=${config.namePrefix} sim=${config.simulate}")
        val onProfile: (GattProfile) -> Unit = { profile -> lastProfile = profile; mirror?.build(profile) }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value ->
            cacheForUi(config, uuid, value)
            mirror?.onZycleValue(uuid, value)
        }
        val onState: (Boolean) -> Unit = { connected -> zycleConnected = connected; status = if (connected) getString(R.string.status_trainer_connected) else getString(R.string.status_searching_trainer); listener?.invoke() }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState).also { simSource = it }
        else ZycleClient(this, config.namePrefix, config.pairedAddress, onProfile, onValue, onState,
            onAdv = { bp -> lastAdvBlueprint = bp; mirror?.setAdvBlueprint(bp) })   // clone the trainer's real advertising
        c.start()
        client = c
        status = getString(R.string.status_searching_trainer)
        updateNotification(); listener?.invoke()
    }

    private fun stopReceive() {
        if (client == null && simSource == null) return
        FileLog.event("receive stop")
        client?.stop(); client = null; simSource = null; lastProfile = null; lastAdvBlueprint = null
        CorrectedFeed.clear()
        zycleConnected = false; lastRawW = null; lastCorrectedW = null; lastSpeedKmh = null; lastCadence = null; lastResistance = null; lastSampleMs = 0L
        status = getString(R.string.status_stopped)
        updateNotification(); listener?.invoke()
    }

    // ── emit half (mirror peripheral + ANT, Start-gated) ──────────────────────────────────────────────
    private fun startEmit() {
        if (mirror != null) return   // idempotent
        val config = Config(this)
        FileLog.event("emit start scaleAdj=${config.scaleAdjustPercent}% offset=${config.offsetW}W")
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
        mirror = m
        m.start()
        lastProfile?.let { m.build(it) }              // mirror started after the trainer was already discovered → build now
        lastAdvBlueprint?.let { m.setAdvBlueprint(it) }
        if (config.antOutputEnabled) {
            antEnabled = true; antOk = false; antStatus = getString(R.string.status_starting)
            antTx = AntFecTx(this, deviceNumber = config.antDeviceId, onState = { ok, detail -> antOk = ok; antStatus = detail; listener?.invoke() })
                .also { it.start(); FileLog.event("ANT+ output enabled id=${config.antDeviceId}") }
        }
        emitting = true
        updateNotification(); listener?.invoke()
    }

    private fun stopEmit() {
        if (mirror == null) return
        FileLog.event("emit stop")
        mirror?.stop(); mirror = null
        antTx?.stop(); antTx = null
        antEnabled = false; antOk = false; antStatus = ""; bleAdvOk = true; lastControl = null
        emitting = false
        updateNotification(); listener?.invoke()
    }

    /** Human-readable summary of a control write the app sent (shown in the UI: "app → trainer"). */
    private fun describeControl(b: ByteArray): String = when {
        b.isEmpty() -> "?"
        (b[0].toInt() and 0xFF) == 0x05 && b.size >= 3 -> getString(R.string.control_erg, (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8))
        (b[0].toInt() and 0xFF) == 0x04 && b.size >= 2 -> getString(R.string.control_resistance, b[1].toInt() and 0xFF)
        (b[0].toInt() and 0xFF) == 0x01 -> getString(R.string.control_reset)
        (b[0].toInt() and 0xFF) == 0x07 -> getString(R.string.monitor_start)
        (b[0].toInt() and 0xFF) == 0x08 -> getString(R.string.monitor_stop)
        else -> getString(R.string.control_op, b[0].toInt() and 0xFF)
    }

    /** Update the monitor tiles + [CorrectedFeed] from the trainer's data. Indoor Bike Data (0x2AD2) carries
     *  speed + cadence + power; Cycling Power (0x2A63) is a power-only fallback. Power is the RAW value (we
     *  show raw → corrected); speed/cadence pass through. */
    private fun cacheForUi(config: Config, uuid: java.util.UUID, value: ByteArray) {
        if (client == null && simSource == null) return   // a late BLE callback after stopReceive() — no phantom
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
                CorrectedFeed.push(lastCorrectedW, lastSpeedKmh?.let { it / 3.6 }, lastCadence, System.currentTimeMillis())
                listener?.invoke()
            }
            com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT -> {
                if (lastRawW == null && value.size >= 4) {   // fallback only if no Indoor Bike Data
                    val raw = le16signed(value, 2); lastRawW = raw; lastCorrectedW = config.correction().correct(raw)
                    CorrectedFeed.push(lastCorrectedW, lastSpeedKmh?.let { it / 3.6 }, lastCadence, System.currentTimeMillis())
                    listener?.invoke()
                }
            }
        }
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()

    override fun onDestroy() { stopEmit(); stopReceive(); releaseWakeLock(); super.onDestroy() }
    override fun onTimeout(startId: Int) { stopEmit(); stopReceive(); releaseWakeLock(); stopSelf() }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrainerBridgeBLE:session").also { runCatching { it.acquire() } }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) runCatching { it.release() } }; wakeLock = null }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MonitorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (emitting) R.string.notif_emitting else R.string.notif_receiving))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setContentIntent(open).build()
    }

    /** Re-post the ongoing notification so its text tracks receive-only vs emitting. No-op unless foreground. */
    private fun updateNotification() {
        if (!foreground) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "bridge-ble"
        private const val NOTIF_ID = 1
        const val ACTION_MASTER_ON = "com.enderthor.trainerbridgeble.MASTER_ON"
        const val ACTION_MASTER_OFF = "com.enderthor.trainerbridgeble.MASTER_OFF"
        const val ACTION_EMIT_START = "com.enderthor.trainerbridgeble.EMIT_START"
        const val ACTION_EMIT_STOP = "com.enderthor.trainerbridgeble.EMIT_STOP"

        private fun intent(context: android.content.Context, action: String) =
            Intent(context, BridgeService::class.java).setAction(action)

        /** Master switch: ON starts the foreground service (receive comes up gated); OFF tears everything down. */
        fun setMaster(context: android.content.Context, on: Boolean) =
            if (on) { context.startForegroundService(intent(context, ACTION_MASTER_ON)); Unit }
            else { context.startService(intent(context, ACTION_MASTER_OFF)); Unit }

        fun startEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_START)) }
        fun stopEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_STOP)) }
    }
}
