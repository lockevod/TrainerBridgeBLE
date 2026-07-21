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

    @Volatile private var client: TrainerSource? = null      // all four are written on the main looper and
    @Volatile private var simSource: SimSource? = null       // read from GATT binder / server callback
    @Volatile private var mirror: MirrorServer? = null       // threads, so the reference itself must be
    @Volatile private var antTx: AntFecTx? = null            // safely published
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lastValues = java.util.concurrent.ConcurrentHashMap<java.util.UUID, ByteArray>()   // every value seen, for a late mirror
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
    @Volatile var lastPowerMs: Long = 0L; private set    // ...and of the last packet that actually CARRIED power

    /** Power specifically — a packet can arrive without the power field, and a sticky last value must not be
     *  reported as live to ANT, the Karoo recording, or the tiles. A short grace covers one dropped frame. */
    val powerFresh: Boolean get() = lastPowerMs != 0L && System.currentTimeMillis() - lastPowerMs <= POWER_STALE_MS
    private fun freshPowerOrNull(): Int? = if (powerFresh) lastCorrectedW else null
    @Volatile private var lastResistance: Int? = null
    @Volatile private var sawIndoorBikeData = false   // NOT `lastRawW == null`: that is set by the fallback itself
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
            // optimistic, and only if the write was at least QUEUED (no link / unknown char → don't move the
            // tile). A stack refusal after queueing still shows briefly; the trainer's own IBD corrects it.
            if (client?.write(com.enderthor.trainerbridgeble.ble.GattUuids.FTMS_CONTROL_POINT, byteArrayOf(0x04, target.toByte()), true) == true) {
                lastResistance = target
                lastControl = getString(R.string.control_resistance_target, target)
                FileLog.event("UI button → resistance target=$target")
            } else FileLog.event("UI button → resistance target=$target NOT DISPATCHED")
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
                Config(this).emitEnabled = false
                foreground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            }
            // master off: nothing to reconfigure, and don't leave an idle started service behind
            ACTION_RECONFIGURE -> if (foreground) { FileLog.enabled = Config(this).loggingEnabled; applyConfigChange() }
                                 else if (!Config(this).masterEnabled) stopSelf()   // don't kill a service the master wants alive
            ACTION_EMIT_START -> { if (foreground) startEmit() }   // never emit from a non-foreground (master-off) service
            ACTION_EMIT_STOP -> { Config(this).emitEnabled = false; stopEmit() }
            else -> {
                // Null intent = a START_STICKY restart after a process kill. Re-derive from persisted config:
                // if the master is on, come back up (foreground + receive); otherwise there's nothing to do.
                val cfg = Config(this)
                if (cfg.masterEnabled) {
                    if (goForeground()) { maybeStartReceive(); if (cfg.emitEnabled) startEmit() }
                } else stopSelf()
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
            // connectedDevice only: dataSync would add a ~6h/24h cumulative FGS timeout on Android 14+ that
            // could kill the bridge mid-ride, and the BLE companion link doesn't need it.
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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

    /** Which source config asks for. The source is picked ONCE in [startReceive], so ticking simulation or
     *  pairing a different trainer while the bridge runs would otherwise change nothing until a restart. */
    private fun sourceKey(config: Config) = if (config.simulate) "sim" else config.pairedAddress.ifEmpty { "any" }
    @Volatile private var currentSourceKey: String? = null

    /** What the emit half was built with. Kept SEPARATE: cycling the mirror drops every connected app, so
     *  an ANT-only change must never touch it (and vice versa). */
    private fun mirrorKey(config: Config) = config.advertisedName
    private fun antKey(config: Config) = "${config.antOutputEnabled}|${config.antDeviceId}"
    @Volatile private var currentMirrorKey: String? = null
    @Volatile private var currentAntKey: String? = null

    /** Config was saved — re-derive whatever was captured at start time. A source change already cycles the
     *  emit half, so it subsumes an emit change; both are no-ops when nothing relevant moved. */
    private fun applyConfigChange() {
        val config = Config(this)
        if (restartReceiveIfSourceChanged()) return
        if (!emitting) return
        if (mirrorKey(config) != currentMirrorKey) {   // advertised name — the mirror can't change it live
            FileLog.event("mirror name changed $currentMirrorKey -> ${mirrorKey(config)}, restarting emit")
            stopEmit(); startEmit()
            return                                     // startEmit picked up the ANT settings too
        }
        if (antKey(config) != currentAntKey) restartAnt(config)
    }

    /** @return true if the receive half (and with it the emit half) was restarted. */
    private fun restartReceiveIfSourceChanged(): Boolean {
        val config = Config(this)
        val wanted = sourceKey(config)
        if (!receiving || wanted == currentSourceKey) return false   // nothing running, or the same source → don't drop a live link
        FileLog.event("source changed $currentSourceKey -> $wanted, restarting receive")
        // The mirror builds its GATT once (a trainer reconnect keeps it), so a genuine source change has to
        // cycle the emit half too or it keeps serving the previous source's services. Apps reconnect.
        val wasEmitting = emitting
        if (wasEmitting) stopEmit()
        stopReceive()
        // Emit half FIRST: the mirror must exist before the new source starts, or its opening profile and
        // seed reads (SimSource emits both synchronously in start()) land with mirror == null — leaving the
        // mirror with no services, which also means it never advertises.
        if (wasEmitting) startEmit()
        maybeStartReceive()
        return true
    }

    // ── receive half (trainer link → CorrectedFeed + tiles + ANT) ─────────────────────────────────────
    private fun startReceive() {
        if (client != null || simSource != null) return   // idempotent
        val config = Config(this)
        config.lastSeenAddress = ""; config.lastSeenName = ""   // runtime state; a process kill leaves it stale
        FileLog.event("receive start paired=${config.pairedAddress.ifEmpty { "any" }} sim=${config.simulate}")
        val onProfile: (GattProfile) -> Unit = { profile -> lastProfile = profile; mirror?.build(profile) }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value ->
            cacheForUi(config, uuid, value)
            lastValues[uuid] = value      // the one-shot reads happen long before Broadcast is pressed
            mirror?.onZycleValue(uuid, value)
        }
        val onState: (Boolean) -> Unit = { connected ->
            zycleConnected = connected; mirror?.setTrainerLinked(connected)
            if (!connected) { config.lastSeenAddress = ""; config.lastSeenName = "" }   // the config screen offers it only while live
            status = if (connected) getString(R.string.status_trainer_connected) else getString(R.string.status_searching_trainer); listener?.invoke() }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState).also { simSource = it }
        else ZycleClient(this, config.pairedAddress, onProfile, onValue, onState,
            onAdv = { bp -> lastAdvBlueprint = bp; mirror?.setAdvBlueprint(bp) },   // clone the trainer's real advertising
            onFound = { name, addr -> config.lastSeenName = name ?: ""; config.lastSeenAddress = addr })
        currentSourceKey = sourceKey(config)
        c.start()
        client = c
        // after start(): SimSource reports connected synchronously, so don't overwrite it with "searching"
        status = getString(if (zycleConnected) R.string.status_trainer_connected else R.string.status_searching_trainer)
        updateNotification(); listener?.invoke()
    }

    private fun stopReceive() {
        if (client == null && simSource == null) return
        FileLog.event("receive stop")
        mirror?.setTrainerLinked(false)   // no source → nothing to advertise, whatever the call order
        Config(this).let { it.lastSeenAddress = ""; it.lastSeenName = "" }
        client?.stop(); client = null; simSource = null; lastProfile = null; lastAdvBlueprint = null; currentSourceKey = null
        lastValues.clear()
        CorrectedFeed.clear()
        zycleConnected = false; sawIndoorBikeData = false; lastPowerMs = 0L; lastRawW = null; lastCorrectedW = null; lastSpeedKmh = null; lastCadence = null; lastResistance = null; lastSampleMs = 0L
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
                client?.write(uuid, bytes, withResponse) ?: false   // false → the mirror answers the app with failure
            },
            onStatus = { s -> status = s; listener?.invoke() },
            onAdvState = { ok -> bleAdvOk = ok; listener?.invoke() },
        )
        mirror = m
        m.start()
        m.setTrainerLinked(zycleConnected)            // Start pressed with the trainer already connected
        lastProfile?.let { m.build(it) }              // mirror started after the trainer was already discovered → build now
        // ...and hand it everything we have already seen. Without this its read cache is empty until the
        // trainer is re-read, which never happens: an app asking for FTMS Feature gets nothing and decides
        // the machine has no capabilities (no ERG, no automatic mode) for the whole session.
        if (lastValues.isNotEmpty()) {
            FileLog.event("mirror seeded with ${lastValues.size} cached values")
            lastValues.forEach { (u, v) -> m.onZycleValue(u, v) }
        }
        lastAdvBlueprint?.let { m.setAdvBlueprint(it) }
        if (config.antOutputEnabled) startAntTx(config)
        emitting = true
        Config(this).emitEnabled = true               // so a START_STICKY restart brings the mirror back
        currentMirrorKey = mirrorKey(config); currentAntKey = antKey(config)
        updateNotification(); listener?.invoke()
    }

    private fun startAntTx(config: Config) {
        antEnabled = true; antOk = false; antStatus = getString(R.string.status_starting)
        antTx = AntFecTx(this, deviceNumber = config.antDeviceId, onState = { ok, detail -> antOk = ok; antStatus = detail; listener?.invoke() })
            .also { it.start(); FileLog.event("ANT+ output enabled id=${config.antDeviceId}") }
    }

    /** Cycle ONLY the ANT channel — the BLE mirror and every app connected to it stay up. The ANT service
     *  releases its channel asynchronously, so re-acquiring immediately races the release and loses on
     *  single-channel hardware. ponytail: fixed delay; wait for a release callback if it still races. */
    private fun restartAnt(config: Config) {
        FileLog.event("ANT config changed $currentAntKey -> ${antKey(config)}")
        antTx?.stop(); antTx = null
        antEnabled = false; antOk = false; antStatus = ""
        currentAntKey = antKey(config)
        listener?.invoke()
        if (!config.antOutputEnabled) return
        antEnabled = true; antStatus = getString(R.string.status_starting)
        handler.postDelayed({
            val c = Config(this)
            if (emitting && antTx == null && c.antOutputEnabled) startAntTx(c)
        }, ANT_RESTART_DELAY_MS)
    }

    private fun stopEmit() {
        if (mirror == null) return
        FileLog.event("emit stop")
        mirror?.stop(); mirror = null
        antTx?.stop(); antTx = null
        antEnabled = false; antOk = false; antStatus = ""; bleAdvOk = true; lastControl = null
        emitting = false; currentMirrorKey = null; currentAntKey = null
        updateNotification(); listener?.invoke()
    }

    /** Human-readable summary of a control write the app sent (shown in the UI: "app → trainer"). */
    private fun describeControl(b: ByteArray): String = when {
        b.isEmpty() -> "?"
        // b is already inverse-corrected (what the trainer is commanded). Re-apply the correction so the
        // tile shows the wattage the app will SEE, not the raw command. Below the ERG floor these differ
        // from what the app asked for — deliberately, that is the floor's documented cost.
        (b[0].toInt() and 0xFF) == 0x05 && b.size >= 3 ->
            getString(R.string.control_erg, Config(this).correction().correct(((b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)).toShort().toInt()))
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
                if (flags and (1 shl 5) != 0) { if (off + 2 <= value.size) lastResistance = le16signed(value, off); off += 2 }
                var havePower = false
                if (flags and (1 shl 6) != 0 && off + 2 <= value.size) {
                    val raw = le16signed(value, off); lastRawW = raw; lastCorrectedW = config.correction().correct(raw); havePower = true
                }
                // Only a packet that actually CARRIED power may refresh ANT's power-freshness clock, or a
                // dropout would be transmitted as live. Speed/cadence still flow to the Karoo sensor.
                if (havePower) sawIndoorBikeData = true   // IBD really carries power here — only now disable the CPM fallback
                if (havePower) antTx?.setLatest(PowerSample(lastCorrectedW, lastCadence, lastSpeedKmh?.let { it / 3.6 }))
                // power only if THIS packet carried it: CorrectedSource stops emitting power on null while
                // speed and cadence keep flowing, instead of recording a frozen value as live data
                // A single truncated frame must not punch a hole in the recording, and a real dropout must
                // not be recorded as live watts: the grace window decides, not this one packet.
                CorrectedFeed.push(freshPowerOrNull(), lastSpeedKmh?.let { it / 3.6 }, lastCadence, System.currentTimeMillis())
                listener?.invoke()
            }
            com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT -> {
                if (!sawIndoorBikeData && value.size >= 4) {   // fallback only if the trainer sends no IBD
                    val raw = le16signed(value, 2); lastRawW = raw; lastCorrectedW = config.correction().correct(raw)
                    lastPowerMs = System.currentTimeMillis()
                    antTx?.setLatest(PowerSample(freshPowerOrNull(), lastCadence, lastSpeedKmh?.let { it / 3.6 }))   // ANT too, or FE-C stays blank
                    CorrectedFeed.push(freshPowerOrNull(), lastSpeedKmh?.let { it / 3.6 }, lastCadence, System.currentTimeMillis())
                    listener?.invoke()
                }
            }
        }
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()

    override fun onDestroy() { stopEmit(); stopReceive(); releaseWakeLock(); super.onDestroy() }
    override fun onTimeout(startId: Int) {
        stopEmit(); stopReceive(); releaseWakeLock()
        foreground = false   // or a later EMIT_START would pass the foreground gate on a dying service
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

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
        private const val POWER_STALE_MS = 2000L   // ~8 missed frames at 4 Hz: covers a hiccup, not a dropout
        private const val ANT_RESTART_DELAY_MS = 1500L   // let the ANT service release the channel first
        const val ACTION_MASTER_ON = "com.enderthor.trainerbridgeble.MASTER_ON"
        const val ACTION_MASTER_OFF = "com.enderthor.trainerbridgeble.MASTER_OFF"
        const val ACTION_EMIT_START = "com.enderthor.trainerbridgeble.EMIT_START"
        const val ACTION_EMIT_STOP = "com.enderthor.trainerbridgeble.EMIT_STOP"
        const val ACTION_RECONFIGURE = "com.enderthor.trainerbridgeble.RECONFIGURE"

        private fun intent(context: android.content.Context, action: String) =
            Intent(context, BridgeService::class.java).setAction(action)

        /** Master switch: ON starts the foreground service (receive comes up gated); OFF tears everything down. */
        fun setMaster(context: android.content.Context, on: Boolean) =
            if (on) { context.startForegroundService(intent(context, ACTION_MASTER_ON)); Unit }
            else { context.startService(intent(context, ACTION_MASTER_OFF)); Unit }

        /** Config was saved: the running source may no longer be the one config asks for. Sent from the
         *  config screen's onStop too, so swallow a background-start refusal instead of crashing. */
        fun reconfigure(context: android.content.Context) {
            runCatching { context.startService(intent(context, ACTION_RECONFIGURE)) }
        }

        fun startEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_START)) }
        fun stopEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_STOP)) }
    }
}
