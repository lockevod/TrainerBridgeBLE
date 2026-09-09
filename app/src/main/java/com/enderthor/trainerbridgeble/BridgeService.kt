package com.enderthor.trainerbridgeble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.enderthor.trainerbridgeble.correction.ErgBias

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
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val lastValues = java.util.concurrent.ConcurrentHashMap<java.util.UUID, ByteArray>()   // every value seen, for a late mirror
    @Volatile private var lastProfile: GattProfile? = null       // trainer GATT, cached so a late-started mirror can build
    @Volatile private var lastAdvBlueprint: AdvBlueprint? = null  // trainer's advertising, cached for a late-started mirror

    @Volatile var status: String = ""; private set
    @Volatile var zycleConnected: Boolean = false; private set
    // Connected AND its opening reads have landed. The mirror advertises on THIS, not on zycleConnected:
    // going on the air with an empty read cache is what makes an app decide the machine has no ERG.
    @Volatile private var zycleSynced: Boolean = false
    @Volatile var lastRawW: Int? = null; private set
    @Volatile var lastCorrectedW: Int? = null; private set
    @Volatile var lastSpeedKmh: Double? = null; private set
    @Volatile var lastCadence: Int? = null; private set
    @Volatile var lastControl: String? = null; private set
    // elapsedRealtime, NOT wall-clock, for both — the reason CorrectedFeed already documents: the Karoo
    // re-syncs its clock mid-ride, and a jump either blanks live data or hides a real dropout.
    @Volatile var lastSampleMs: Long = 0L; private set   // last trainer sample, for UI staleness
    @Volatile var lastPowerMs: Long = 0L; private set    // ...and the last packet that actually CARRIED power

    // FTMS says Instantaneous Speed is 0.01 km/h. Some trainers (the Zycle among them) report 0.1 km/h,
    // which silently makes speed AND the recorded distance ten times too small. Rather than hardcode either,
    // believe the spec until the trainer's OWN distance/time contradicts it: dividing metres by seconds is
    // an in-band measurement no unit confusion can fake.
    @Volatile private var speedUnit = 0.01
    @Volatile private var speedUnitLocked = false
    private var prevDistM = 0; private var prevElapsedS = 0

    private fun speedUnitKmh(distM: Int?, elapsedS: Int?): Double {
        if (speedUnitLocked || distM == null || elapsedS == null) return speedUnit
        val dd = distM - prevDistM; val dt = elapsedS - prevElapsedS
        prevDistM = distM; prevElapsedS = elapsedS
        if (dt !in 1..10 || dd < 3) return speedUnit          // need a real, recent movement to judge
        val measuredKmh = dd / dt.toDouble() * 3.6
        val reportedKmh = lastSpeedKmh ?: return speedUnit
        if (reportedKmh > 0.1 && measuredKmh / reportedKmh > 5.0) {
            speedUnit = 0.1; speedUnitLocked = true
            FileLog.event("speed unit: trainer reports 0.1 km/h, not the spec's 0.01 " +
                "(measured ${"%.1f".format(measuredKmh)} km/h vs reported ${"%.2f".format(reportedKmh)})")
        } else if (dd > 10) speedUnitLocked = true            // the spec value held up — stop second-guessing
        return speedUnit
    }

    /** Power specifically — a packet can arrive without the power field, and a sticky last value must not be
     *  reported as live to ANT, the Karoo recording, or the tiles. A short grace covers one dropped frame. */
    val powerFresh: Boolean get() = lastPowerMs != 0L && android.os.SystemClock.elapsedRealtime() - lastPowerMs <= POWER_STALE_MS
    private fun freshPowerOrNull(): Int? = if (powerFresh) lastCorrectedW else null
    @Volatile private var lastResistance: Int? = null
    @Volatile private var sawIndoorBikeData = false   // NOT `lastRawW == null`: that is set by the fallback itself
    private val pendingErgBias = ErgBiasPersistence(ERG_BIAS_PERSIST_MS, ErgBias::onPower)
    /** Owns one receive source at a time. Callback validation and mutation share this monitor with source
     *  replacement, so teardown cannot overtake a callback that already passed its ownership check. */
    private val receiveOwner = IdentityOwner<Any>()
    /** Owns one emit instance. MirrorServer.stop() is not a callback barrier, and the toZycle lambda reads
     *  `client` dynamically — so an app's control write that entered the OLD mirror could change resistance
     *  or the ERG target on the REPLACEMENT trainer. Validation, the ErgBias/lastControl mutations and the
     *  capture of the target source happen under this; the write itself is dispatched outside it, because
     *  writeCharacteristic is a Binder call into the Bluetooth process and stopEmit() waits on this monitor
     *  from the main thread. */
    private val emitOwner = IdentityOwner<Any>()
    private var receiveGeneration = 0L
    private var ftmsReleaseGeneration: Long? = null
    /** How many callbacks the generation guard rejected. Zero all ride means the races the guard exists for
     *  never happened; a climbing number is itself the finding. Reported by the periodic snapshot. */
    private val staleCallbacks = java.util.concurrent.atomic.AtomicInteger(0)
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
        if (sim != null) {
            if (delta > 0) sim.buttonUp() else sim.buttonDown()
            listener?.invoke()
        }
        else {
            val target = ((lastResistance ?: 0) + delta).coerceIn(0, 200)   // 0..200 per the Zycle's 0x2AD6 range
            val bytes = encodeTargetResistance(target)
            val localMirror = mirror
            val procedure = localMirror?.admitLocalControl(0x04, bytes)
            if (localMirror != null && procedure == null) {
                lastControl = getString(R.string.status_control_busy)
                FileLog.event("UI button → resistance target=$target BLOCKED — FTMS control busy")
                listener?.invoke()
                return
            }
            val source = client
            if (source == null) {
                procedure?.let { localMirror.localControlTransportFailed(it) }
                FileLog.event("UI button → resistance target=$target FAILED")
                listener?.invoke()
            } else source.write(
                com.enderthor.trainerbridgeble.ble.GattUuids.FTMS_CONTROL_POINT,
                bytes,
                true,
            ) { success ->
                // Arm/close the procedure HERE, on the write callback, not inside the UI post below:
                // that post is a second main-loop hop, and an arm that lands after a newer procedure was
                // admitted used to strip the newer one of its deadline.
                if (success) procedure?.let { localMirror.localControlDispatched(it) }
                else procedure?.let { localMirror.localControlTransportFailed(it) }
                handler.post {
                    if (client !== source) return@post
                    if (success) {
                        // Transport only. With the mirror up the trainer's FTMS Response Code decides
                        // acceptance, and onControlAccepted commits it — including telling ErgBias to
                        // retire any armed ERG target. The response clock starts now, not at admission.
                        lastResistance = target
                        if (procedure == null) {
                            // No mirror: nothing will ever correlate a response, so the write is all we
                            // have. A trainer that rejects the procedure is indistinguishable from one
                            // that accepts it on this path.
                            ErgBias.onControl(bytes, android.os.SystemClock.elapsedRealtime())
                            lastControl = getString(R.string.control_resistance_target, target)
                        }
                        FileLog.event("UI button → resistance target=$target sent" +
                            if (procedure != null) " (awaiting trainer verdict #${procedure.id})" else "")
                    } else FileLog.event("UI button → resistance target=$target FAILED")
                    listener?.invoke()
                }
            }
        }
    }

    /** The BLE stack does not survive a Bluetooth off/on (or a crash of com.android.bluetooth): the GATT
     *  server and the advertising set die with it, and nothing reopens them — the mirror goes silently mute
     *  for the rest of the ride. The central half recovers on its own (its scan retries), so only the emit
     *  half is cycled here. */
    // ── Karoo idle-shutdown keep-alive ──────────────────────────────────────────────────────────────
    /** `persist.hx.idle_shutdown_delay` is 600000 ms on this firmware. Poke at 5 min, not 8: a single
     *  missed tick (Karoo system briefly unbound, a late handler) would otherwise eat the whole margin
     *  and the device powers off anyway. Hard-coded because the property is not readable from an app. */
    private val KEEP_AWAKE_MS = 5 * 60_000L
    /** A dispatch that did not reach the host retries in seconds, not at the next 5-minute slot. */
    private val KEEP_AWAKE_RETRY_MS = 20_000L
    private var karooSystem: io.hammerhead.karooext.KarooSystemService? = null
    /** Recording (or paused) already prevents the idle shutdown, so poking then is pure cost — and it
     *  relights a display the rider deliberately let sleep. */
    @Volatile private var rideActive = false
    /** Bounds the screen-on skip to one interval; see the branch that uses it. */
    private var screenOnSkips = 0

    private val keepAwakeTick = object : Runnable {
        override fun run() {
            if (!foreground || !Config(this@BridgeService).keepAwake) { karooDisconnect(); return }
            // ONE policy with the wakelock idle guard. That guard releases the lock after
            // WAKELOCK_IDLE_MS with no trainer, precisely so an abandoned session can sleep; keeping the
            // whole DEVICE alive past that point would defeat it with a costlier resource. It is also the
            // self-healing signal: noteTrainerLink() re-takes the lock the moment a trainer returns.
            // (And once the lock is gone the CPU can suspend, which freezes this postDelayed anyway —
            // uptimeMillis does not advance in suspend — so the poke could not be trusted regardless.)
            val next = when {
                wakeLock?.isHeld != true -> { FileLog.event("keep-awake: session idle — not poking"); KEEP_AWAKE_MS }
                rideActive -> { FileLog.event("keep-awake: ride active — shutdown already suppressed"); KEEP_AWAKE_MS }
                // Nothing to disarm while the screen is already awake — the countdown only starts when it
                // sleeps. Matters when "keep this screen on" is also enabled: without this the poke fired
                // every interval doing nothing, and the log said it had done something.
                // NEVER twice running, though: isInteractive is a proxy, and AOSP counts a DREAMING state
                // as interactive. If a firmware ever armed the countdown in a state that reads interactive,
                // an unlimited skip would be the one error the 5-vs-10-minute margin cannot absorb — it
                // would persist. A poke with the screen genuinely on is a no-op, so being wrong this way
                // is free; being wrong the other way costs the device.
                screenOn() && screenOnSkips == 0 -> {
                    screenOnSkips++
                    FileLog.event("keep-awake: screen already on — no countdown to disarm, poke not needed")
                    KEEP_AWAKE_MS
                }
                else -> { screenOnSkips = 0; pokeScreen() }
            }
            handler.postDelayed(this, next)
        }
    }

    private fun screenOn(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(false)   // unknown -> poke anyway; a wasted wake beats a missed deadline

    /** @return the delay until the next attempt: a short retry if the host did not take the call. */
    private fun pokeScreen(): Long {
        val ks = karooSystem
        if (ks == null) { karooConnect(); return KEEP_AWAKE_RETRY_MS }
        // dispatch() returns whether a controller RECEIVED the call — it is still no acknowledgement
        // that the host acted on it, so this line says "submitted", never "the screen woke".
        val taken = runCatching { ks.dispatch(io.hammerhead.karooext.models.TurnScreenOn) }
            .onFailure { FileLog.event("keep-awake: TurnScreenOn threw — ${it.message}") }
            .getOrDefault(false)
        FileLog.event("keep-awake: TurnScreenOn submitted=$taken")
        return if (taken) KEEP_AWAKE_MS else KEEP_AWAKE_RETRY_MS
    }

    private fun karooConnect() {
        if (karooSystem != null) return
        val ks = io.hammerhead.karooext.KarooSystemService(this)
        karooSystem = ks
        runCatching {
            ks.connect { ok ->
                FileLog.event("keep-awake: Karoo system connected=$ok")
                if (ok) runCatching {
                    ks.addConsumer<io.hammerhead.karooext.models.RideState> { st ->
                        rideActive = st !is io.hammerhead.karooext.models.RideState.Idle
                    }
                }
            }
        }.onFailure { FileLog.event("keep-awake: connect FAILED — ${it.message}"); karooSystem = null }
    }

    private fun karooDisconnect() {
        handler.removeCallbacks(keepAwakeTick)
        // disconnect() unregisters every consumer with it; it also unbinds unconditionally, which throws
        // if the bind never took — hence the guard.
        karooSystem?.let { runCatching { it.disconnect() } }
        karooSystem = null
        rideActive = false
    }

    /** Called at master-on and whenever config changes, so the toggle takes effect without a restart.
     *  Posts a tick in 20 s rather than waiting a full interval: a START_STICKY restart can land well into
     *  an already-running countdown. (That tick may SKIP if the screen is on — which is sound, because a
     *  countdown can only have been armed by the screen sleeping.) */
    private fun applyKeepAwake() {
        handler.removeCallbacks(keepAwakeTick)
        if (foreground && Config(this).keepAwake) {
            screenOnSkips = 0
            karooConnect()
            handler.postDelayed(keepAwakeTick, KEEP_AWAKE_RETRY_MS)
        } else karooDisconnect()
    }

    /** The Karoo powers ITSELF off when idle: HxStateManagerService flips "can shutdown" the moment the
     *  screen goes off, and with no ride recording it takes the device down — at any battery level. A
     *  wakelock cannot veto that; nothing an app can do can. What we CAN do is say so, because otherwise
     *  the log simply stops mid-line and a reader cannot tell a device shutdown from a crashed bridge. */
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val battery = runCatching {
                (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrDefault(-1)
            // Synchronous: the log executor will not get another slice.
            FileLog.eventNow("=== device ${intent?.action?.substringAfterLast('.') ?: "SHUTDOWN"} " +
                "— uptime ${android.os.SystemClock.elapsedRealtime() / 60_000}m, battery $battery%, " +
                "master=${Config(this@BridgeService).masterEnabled}, wake=${wakeLock?.isHeld == true}")
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
            if (!emitting) return
            // NOT immediately: STATE_ON means the adapter flipped, not that the stack can serve a GATT
            // server yet. openGattServer() returning null here leaves the mirror dead with no retry.
            FileLog.event("bluetooth back on — rebuilding the mirror shortly")
            handler.postDelayed({ if (emitting) { stopEmit(); startEmit() } }, BT_RESTART_SETTLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate(); status = getString(R.string.status_stopped)
        // Exempt from the API-34 exported-flag requirement only because STATE_CHANGED is a protected system
        // broadcast — guarded anyway, so adding a non-protected action here can't kill the service at birth.
        runCatching { registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
            .onFailure { FileLog.event("bt state receiver not registered: ${it.message}") }
        // ACTION_SHUTDOWN is not exempt from the implicit-broadcast ban, so it must be registered here
        // rather than in the manifest — a manifest receiver would simply never fire.
        runCatching {
            registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN).apply {
                addAction(Intent.ACTION_REBOOT)
            })
        }.onFailure { FileLog.event("shutdown receiver not registered: ${it.message}") }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { listener = null; return super.onUnbind(intent) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MASTER_ON -> { if (goForeground()) maybeStartReceive() }
            ACTION_MASTER_OFF -> {
                stopEmit(); stopReceive(); releaseWakeLock(); karooDisconnect()
                Config(this).emitEnabled = false
                foreground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            }
            // master off: nothing to reconfigure, and don't leave an idle started service behind
            ACTION_RECONFIGURE -> if (foreground) { FileLog.enabled = Config(this).loggingEnabled; applyConfigChange(); applyKeepAwake() }
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
        // Before the try, not after: the catch below logs WHY startForeground failed, and FileLog silently
        // drops anything written before init. One File object is not what blows the 5 s window.
        FileLog.init(this); FileLog.enabled = Config(this).loggingEnabled
        // Session header. Two builds are now in play and a log with no version is a log you cannot trust to
        // be about the code you think it is; the correction values matter because every wattage below is
        // relative to them.
        Config(this).let { c ->
            // via PackageManager rather than BuildConfig: AGP 8 does not generate that class unless
            // buildFeatures.buildConfig is turned on, and one log line does not justify a build change.
            // versionName is nullable and the lookup can throw: both used to read as "vnull" in the header.
            val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            FileLog.event("=== session start v$ver " +
                "${android.os.Build.MODEL} api${android.os.Build.VERSION.SDK_INT} — " +
                "scale=+${c.scaleAdjustPercent}% offset=${c.offsetW}W floor=${c.invertFloorW}W " +
                "ergBias=${c.ergBiasW}W advName='${c.advertisedName}' sim=${c.simulate} ant=${c.antOutputEnabled}")
        }

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
        lastTrainerLinkMs = android.os.SystemClock.elapsedRealtime()
        if (!acquireWakeLock()) {
            foreground = false
            status = getString(R.string.status_wakelock_failed); listener?.invoke()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            return false
        }
        applyKeepAwake()
        handler.removeCallbacks(snapshot); handler.post(snapshot)   // stops itself once foreground goes false
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
        pendingErgBias.reset()
        ErgBias.seed(config.ergBiasW)   // start calibrated; there is no live command to measure against yet
        FileLog.event("receive start paired=${config.pairedAddress.ifEmpty { "any" }} sim=${config.simulate} ergBias=${config.ergBiasW}W")
        val owner = Any()
        val sourceGeneration = ++receiveGeneration
        receiveOwner.replace(owner)
        // Low-rate callbacks still hop to main; onValue stays on the BLE thread and uses receiveOwner's
        // monitor to make validation + mutation atomic with stopReceive().
        val onProfile: (GattProfile) -> Unit = { profile ->
            // The worst of them: MirrorServer.build() is a one-shot latch, so a late profile from the source
            // we just replaced wins it and the new source's real profile is then ignored for the session.
            handler.post { receiveOwner.runIfCurrent(owner) { lastProfile = profile; mirror?.build(profile) } }
        }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value ->
            // Keep the 4 Hz relay off the main looper, but make ownership check + every source mutation one
            // critical section. stopReceive() clears the same owner before teardown.
            if (!receiveOwner.runIfCurrent(owner) {
                cacheForUi(config, uuid, value)
                lastValues[uuid] = value      // the one-shot reads happen long before Broadcast is pressed
                mirror?.onZycleValue(uuid, value)
            }) staleCallbacks.incrementAndGet()   // counted, not logged: it would be per-packet
        }
        val onState: (Boolean) -> Unit = { connected ->
          handler.post { receiveOwner.runIfCurrent(owner) {
            zycleConnected = connected
            if (connected) noteTrainerLink()
            // Only the DROP is immediate; going on the air waits for onSynced below.
            if (!connected) { zycleSynced = false; mirror?.setTrainerLinked(false); ErgBias.forget() }
            if (!connected) { config.lastSeenAddress = ""; config.lastSeenName = "" }   // the config screen offers it only while live
            status = if (connected) getString(R.string.status_trainer_connected) else getString(R.string.status_searching_trainer); listener?.invoke()
          } }
        }
        // Same treatment: a stale onSynced would put the mirror on the air with no trainer behind it, and
        // setTrainerLinked is edge-triggered, so it would STAY there.
        val onSynced: () -> Unit = { handler.post { receiveOwner.runIfCurrent(owner) {
            val activeMirror = mirror
            if (activeMirror != null && ftmsReleaseGeneration?.let { sourceGeneration >= it } == true) {
                activeMirror.releaseFtmsQuarantine()
                ftmsReleaseGeneration = null
            }
            zycleSynced = true
            noteTrainerLink()
            activeMirror?.setTrainerLinked(true)
        } } }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState, onSynced).also { simSource = it }
        else ZycleClient(this, config.pairedAddress, onProfile, onValue, onState, onSynced,
            // Guarded too, or the replaced source's advertising blueprint and address get written over the
            // live one's. Neither needs a main-looper hop; the owner monitor provides the ordering.
            onAdv = { bp -> receiveOwner.runIfCurrent(owner) { lastAdvBlueprint = bp; mirror?.setAdvBlueprint(bp) } },
            onFound = { name, addr -> receiveOwner.runIfCurrent(owner) {
                // Binder thread: touch the @Volatile stamp only, never noteTrainerLink() — that would
                // block a binder thread on the service monitor. A trainer stuck in a connect flap
                // (status=133) is present, and must not be counted as absent by the idle guard.
                lastTrainerLinkMs = android.os.SystemClock.elapsedRealtime()
                config.lastSeenName = name ?: ""; config.lastSeenAddress = addr
            } })
        currentSourceKey = sourceKey(config)
        c.start()
        client = c
        // SimSource reports connected synchronously inside start(), but onState now hops to main (see the
        // lambdas above), so zycleConnected is still false here and this reads "searching". The posted body
        // runs right after and corrects it — a one-frame flash, not a wrong end state.
        status = getString(if (zycleConnected) R.string.status_trainer_connected else R.string.status_searching_trainer)
        updateNotification(); listener?.invoke()
    }

    private fun stopReceive() {
        if (client == null && simSource == null) return
        FileLog.event("receive stop")
        receiveOwner.clear()   // waits for an admitted callback, then rejects every later one
        mirror?.setTrainerLinked(false)   // no source → nothing to advertise, whatever the call order
        Config(this).let { it.lastSeenAddress = ""; it.lastSeenName = "" }
        client?.stop(); client = null; simSource = null; lastProfile = null; lastAdvBlueprint = null; currentSourceKey = null
        // Flush after client.stop() — but note stop() is NOT a callback barrier: a notification already past
        // its `stopped` check can still publish a pending value after this runs, and that last whole-watt
        // step is then lost. Immaterial (the EMA moves ~0.05 W a sample and is re-seeded next ride) and it is
        // the SAFE direction: the dangerous half — a stale sample being persisted into the NEXT session — is
        // closed by the receiveOwner guard on onValue, which is what feeds learnErgBias.
        persistErgBias(Config(this)); ErgBias.forget()
        lastValues.clear()
        CorrectedFeed.clear()
        speedUnit = 0.01; speedUnitLocked = false; prevDistM = 0; prevElapsedS = 0
        zycleConnected = false; zycleSynced = false; sawIndoorBikeData = false; lastPowerMs = 0L; lastRawW = null; lastCorrectedW = null; lastSpeedKmh = null; lastCadence = null; lastResistance = null; lastSampleMs = 0L
        status = getString(R.string.status_stopped)
        updateNotification(); listener?.invoke()
    }

    // ── emit half (mirror peripheral + ANT, Start-gated) ──────────────────────────────────────────────
    private fun startEmit() {
        if (mirror != null) return   // idempotent
        val config = Config(this)
        FileLog.event("emit start scaleAdj=${config.scaleAdjustPercent}% offset=${config.offsetW}W")
        val emitToken = Any()
        val m = MirrorServer(
            context = this,
            advertisedName = config.advertisedName,
            correction = { config.correction() },
            toZycle = { uuid, bytes, withResponse, onComplete ->
                // CAPTURE the source under the owner; dispatch outside it (see [emitOwner]). A callback from
                // a stopped mirror captures nothing and relays nothing; one admitted before the clear still
                // targets the source it was admitted for, never the replacement.
                var target: com.enderthor.trainerbridgeble.ble.TrainerSource? = null
                emitOwner.runIfCurrent(emitToken) { target = client }
                val source = target
                if (source == null) {
                    onComplete(false)
                    false
                } else source.write(uuid, bytes, withResponse) { success ->
                    // An accepted ATT write is NOT an accepted procedure: the trainer can still answer
                    // Control Not Permitted / Invalid Parameter. Committing here taught ERG bias from
                    // targets the machine refused. onControlAccepted below is the real commit point.
                    onComplete(success)
                }
            },
            onControlAccepted = { bytes ->
                // Delivered on the trainer's binder thread. The mutations must happen UNDER the monitor,
                // not after a check-then-act: stopEmit() runs on main and a callback that merely passed
                // the check could otherwise publish into an already torn-down session.
                var moved = false
                emitOwner.runIfCurrent(emitToken) {
                    // `bytes` is already inverse-corrected: exactly the raw watts the trainer was told to
                    // hold, which is what measured power has to be compared against.
                    ErgBias.onControl(bytes, android.os.SystemClock.elapsedRealtime())
                    lastControl = describeControl(bytes)
                    moved = true
                }
                if (moved) listener?.invoke()
            },
            onTrainerRecycle = {
                var current = false
                emitOwner.runIfCurrent(emitToken) { current = true }
                if (current && receiving) {
                    FileLog.event("FTMS origin disconnected mid-procedure — recycling trainer link")
                    ftmsReleaseGeneration = receiveGeneration + 1
                    stopReceive()
                    maybeStartReceive()
                }
            },
            onStatus = { s -> status = s; listener?.invoke() },
            onAdvState = { ok -> bleAdvOk = ok; listener?.invoke() },
            isTrainer = { addr ->
                val c = Config(this)
                addr.equals(c.lastSeenAddress, true) || (c.pairedAddress.isNotEmpty() && addr.equals(c.pairedAddress, true))
            },
        )
        emitOwner.replace(emitToken)   // published before start(): the constructor raises no callbacks
        mirror = m
        m.start()
        m.setTrainerLinked(zycleSynced)               // Start pressed with the trainer already connected AND read
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
        // Before the early return: a mirror whose construction or start() failed still left callbacks able
        // to run against this token.
        emitOwner.clear()
        ftmsReleaseGeneration = null
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
            getString(R.string.control_erg, Config(this).correction().correctCommanded(((b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)).toShort().toInt()))
        (b[0].toInt() and 0xFF) == 0x04 && b.size >= 2 -> getString(R.string.control_resistance, b[1].toInt() and 0xFF)
        (b[0].toInt() and 0xFF) == 0x00 -> getString(R.string.control_request)   // the ERG handshake's first step
        (b[0].toInt() and 0xFF) == 0x01 -> getString(R.string.control_reset)
        (b[0].toInt() and 0xFF) == 0x07 -> getString(R.string.monitor_start)
        (b[0].toInt() and 0xFF) == 0x08 -> getString(R.string.monitor_stop)
        (b[0].toInt() and 0xFF) == 0x11 -> getString(R.string.control_sim)       // slope / indoor bike simulation
        (b[0].toInt() and 0xFF) == 0x13 -> getString(R.string.control_spindown)
        else -> getString(R.string.control_op, b[0].toInt() and 0xFF)
    }

    /** Update the monitor tiles + [CorrectedFeed] from the trainer's data. Indoor Bike Data (0x2AD2) carries
     *  speed + cadence + power; Cycling Power (0x2A63) is a power-only fallback. Power is the RAW value (we
     *  show raw → corrected); speed/cadence pass through. */
    private fun cacheForUi(config: Config, uuid: java.util.UUID, value: ByteArray) {
        if (client == null && simSource == null) return   // a late BLE callback after stopReceive() — no phantom
        if (uuid == com.enderthor.trainerbridgeble.ble.GattUuids.INDOOR_BIKE_DATA ||
            uuid == com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT) lastSampleMs = android.os.SystemClock.elapsedRealtime()
        when (uuid) {
            com.enderthor.trainerbridgeble.ble.GattUuids.INDOOR_BIKE_DATA -> {
                if (value.size < 2) return
                val flags = le16(value, 0); var off = 2
                var speedRaw: Int? = null
                if (flags and (1 shl 0) == 0) { if (off + 2 <= value.size) speedRaw = le16(value, off); off += 2 }
                if (flags and (1 shl 1) != 0) off += 2
                if (flags and (1 shl 2) != 0) { if (off + 2 <= value.size) lastCadence = le16(value, off) / 2; off += 2 }
                if (flags and (1 shl 3) != 0) off += 2
                var distM: Int? = null
                if (flags and (1 shl 4) != 0) {
                    if (off + 3 <= value.size) distM = (value[off].toInt() and 0xFF) or
                        ((value[off + 1].toInt() and 0xFF) shl 8) or ((value[off + 2].toInt() and 0xFF) shl 16)
                    off += 3
                }
                if (flags and (1 shl 5) != 0) { if (off + 2 <= value.size) lastResistance = le16signed(value, off); off += 2 }
                // Total Distance is at a known offset only after the fields above, so grab elapsed time too:
                // together they are the only in-band way to tell what the speed field's unit really is.
                var elapsedS: Int? = null
                run {
                    var o = off
                    if (flags and (1 shl 6) != 0) o += 2      // instantaneous power
                    if (flags and (1 shl 7) != 0) o += 2      // average power
                    if (flags and (1 shl 8) != 0) o += 5      // expended energy
                    if (flags and (1 shl 9) != 0) o += 1      // heart rate
                    if (flags and (1 shl 10) != 0) o += 1     // metabolic equivalent
                    if (flags and (1 shl 11) != 0 && o + 2 <= value.size) elapsedS = le16(value, o)
                }
                speedRaw?.let { lastSpeedKmh = it * speedUnitKmh(distM, elapsedS) }
                var havePower = false
                if (flags and (1 shl 6) != 0 && off + 2 <= value.size) {
                    val raw = le16signed(value, off); lastRawW = raw; lastCorrectedW = config.correction().correct(raw); havePower = true
                    learnErgBias(config, raw)
                }
                // Only a packet that actually CARRIED power may refresh ANT's power-freshness clock, or a
                // dropout would be transmitted as live. Speed/cadence still flow to the Karoo sensor.
                if (havePower) {
                    sawIndoorBikeData = true       // IBD really carries power — only now disable the CPM fallback
                    lastPowerMs = android.os.SystemClock.elapsedRealtime()   // the clock powerFresh/freshPowerOrNull read
                }
                // ALWAYS report to ANT, with a null power once it has gone stale: gating the CALL froze
                // speed and cadence too and starved ANT on a trainer whose IBD carries no power field.
                antTx?.setLatest(PowerSample(freshPowerOrNull(), lastCadence, lastSpeedKmh?.let { it / 3.6 }))
                // power only if THIS packet carried it: CorrectedSource stops emitting power on null while
                // speed and cadence keep flowing, instead of recording a frozen value as live data
                // A single truncated frame must not punch a hole in the recording, and a real dropout must
                // not be recorded as live watts: the grace window decides, not this one packet.
                CorrectedFeed.push(freshPowerOrNull(), lastSpeedKmh?.let { it / 3.6 }, lastCadence, android.os.SystemClock.elapsedRealtime())
                // NOT listener?.invoke(): the Monitor already polls at 1 Hz, and driving it from here re-ran a
                // full render (a fresh GradientDrawable + autosize on seven TextViews) at the trainer's ~4 Hz —
                // on the same main looper the mirror's notify fan-out posts to. State CHANGES still notify
                // immediately (connect/disconnect, control write, status, adv state); only the tiles wait.
            }
            com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT -> {
                if (!sawIndoorBikeData && value.size >= 4) {   // fallback only if the trainer sends no IBD
                    val raw = le16signed(value, 2); lastRawW = raw; lastCorrectedW = config.correction().correct(raw)
                    learnErgBias(config, raw)
                    lastPowerMs = android.os.SystemClock.elapsedRealtime()
                    antTx?.setLatest(PowerSample(freshPowerOrNull(), lastCadence, lastSpeedKmh?.let { it / 3.6 }))   // ANT too, or FE-C stays blank
                    CorrectedFeed.push(freshPowerOrNull(), lastSpeedKmh?.let { it / 3.6 }, lastCadence, android.os.SystemClock.elapsedRealtime())
                    // same as the IBD branch above: the 1 Hz poller owns the tiles
                }
            }
        }
    }

    /** Feed the ERG-bias learner and persist it when it moves a whole watt, so the next ride starts where
     *  this one finished instead of spending its first minutes re-converging. */
    private fun learnErgBias(config: Config, raw: Int) {
        // SimSource tracks the ERG target exactly, so it would teach a bias of ~0 and PERSIST it — running
        // the simulator for three minutes would quietly wipe the real trainer's calibration.
        if (config.simulate) return
        val now = android.os.SystemClock.elapsedRealtime()
        val due = pendingErgBias.onPower(raw, now) ?: return
        // Rate-limited: onPower is fed at 4 Hz, and a converged bias sitting near an integer boundary (the
        // measured overshoot is ~8 W) flips across it over and over — each flip a full rewrite+fsync of the
        // prefs XML, in flash. The stated goal ("start the next ride where this one finished") is met just as
        // well at one-minute granularity, and stopReceive flushes whatever is still pending.
        persistErgBias(config, due)
    }

    /** Write out the latest learned bias, if it moved since the last write. */
    private fun persistErgBias(config: Config) {
        val w = pendingErgBias.drain() ?: return
        persistErgBias(config, w)
    }

    private fun persistErgBias(config: Config, w: Int) {
        config.ergBiasW = w
        FileLog.event("ERG bias learned: ${w}W (trainer settles above its command)")
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(btStateReceiver) }
        runCatching { unregisterReceiver(shutdownReceiver) }
        karooDisconnect()
        stopEmit(); stopReceive(); releaseWakeLock()
        foreground = false   // ...or the snapshot keeps reposting itself and holds the Service alive
        handler.removeCallbacks(snapshot)
        super.onDestroy()
    }
    override fun onTimeout(startId: Int) {
        stopEmit(); stopReceive(); releaseWakeLock(); karooDisconnect()
        foreground = false   // or a later EMIT_START would pass the foreground gate on a dying service
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

    /** elapsedRealtime of the last moment a trainer was linked, or of master-on. The lock is for an
     *  ACTIVE session: it must survive a trainer drop mid-ride, but not an evening of the master being
     *  left on with no trainer in the room, where it would block suspend for hours. */
    @Volatile private var lastTrainerLinkMs = 0L

    /** Called on every trainer link and once a minute while one is up. Re-takes the lock if the idle
     *  guard released it. A failure here only degrades the session — never tears it down, unlike the
     *  acquisition at master-on, because by this point a ride is already in progress. */
    private fun noteTrainerLink() {
        lastTrainerLinkMs = android.os.SystemClock.elapsedRealtime()
        if (foreground && wakeLock?.isHeld != true) acquireWakeLock("trainer back after idle release")
    }

    @Synchronized private fun acquireWakeLock(why: String = "master active"): Boolean {
        if (wakeLock?.isHeld == true) return true
        val candidate = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrainerBridgeBLE:session")
        val failure = runCatching { candidate.acquire(); check(candidate.isHeld) { "lock not held" } }.exceptionOrNull()
        if (failure != null) {
            runCatching { if (candidate.isHeld) candidate.release() }
            FileLog.event("wakelock ACQUIRE FAILED ($why): ${failure.message ?: failure.javaClass.simpleName}")
            return false
        }
        wakeLock = candidate
        FileLog.event("wakelock ACQUIRED ($why)")
        return true
    }

    @Synchronized private fun releaseWakeLock(why: String = "master inactive") {
        val lock = wakeLock ?: return
        val failure = runCatching { if (lock.isHeld) lock.release() }.exceptionOrNull()
        val heldAfter = runCatching { lock.isHeld }
        if (heldAfter.getOrNull() == false) {
            wakeLock = null
            FileLog.event("wakelock RELEASED ($why)")
        } else {
            val cause = failure ?: heldAfter.exceptionOrNull()
            FileLog.event("wakelock RELEASE FAILED ($why): ${cause?.message ?: "lock still held"}")
        }
    }

    /**
     * One compact state line a minute. Without it a quiet stretch of log is ambiguous — nothing happened, or
     * the bridge stalled? — and every "permanent death" bug in this project's history looked exactly like
     * silence. It also carries the values you would otherwise have to reconstruct: whether ERG is live, what
     * the learner has settled on, and how far the level we SHOW has drifted from the machine's.
     */
    private val snapshot = object : Runnable {
        override fun run() {
            if (!foreground) return   // master off / service dying: stop the loop, don't outlive it
            // Idle guard. A trainer drop mid-ride is seconds to minutes, so the whole-session hold that
            // recovers from one is untouched; the master left on all afternoon is not, and that case used
            // to block CPU suspend until someone remembered. Runs before the log line: it must not depend
            // on diagnostics being switched on.
            if (zycleConnected || zycleSynced) noteTrainerLink()
            // Release ONLY once the controller is holding the search for us. With no scan registered, a
            // postDelayed retry cannot wake a suspended CPU and the trainer's advertising has nowhere to
            // land — the guard would trade battery drain for a bridge that never comes back.
            else if (wakeLock?.isHeld == true && client?.searching == true &&
                android.os.SystemClock.elapsedRealtime() - lastTrainerLinkMs > WAKELOCK_IDLE_MS)
                releaseWakeLock("idle: no trainer seen for ${WAKELOCK_IDLE_MS / 60_000}m")
            if (FileLog.enabled) FileLog.event(
                "state master=${Config(this@BridgeService).masterEnabled} recv=$receiving emit=$emitting " +
                "trainer=${if (zycleSynced) "synced" else if (zycleConnected) "connected" else "-"} " +
                "adv=$bleAdvOk apps=${mirror?.clientCount ?: 0} wake=${wakeLock?.isHeld == true} " +
                "powerFresh=$powerFresh raw=$lastRawW corr=$lastCorrectedW cad=$lastCadence res=$resistance " +
                "erg=${ErgBias.commanded} bias=${ErgBias.watts}W " +
                "level=${mirror?.levelDebug ?: "-"} ant=${if (antEnabled) antOk else null} stale=${staleCallbacks.get()}")
            handler.postDelayed(this, SNAPSHOT_MS)
        }
    }

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
        private const val BT_RESTART_SETTLE_MS = 2000L   // let the BT stack settle before reopening the server
        private const val POWER_STALE_MS = 2000L   // ~8 missed frames at 4 Hz: covers a hiccup, not a dropout
        private const val ANT_RESTART_DELAY_MS = 1500L   // let the ANT service release the channel first
        private const val ERG_BIAS_PERSIST_MS = 60_000L  // at most one prefs write a minute; stopReceive flushes
        private const val SNAPSHOT_MS = 60_000L          // one state line a minute while the service is up
        // Deliberately generous: a mechanical stop, a phone call or a bathroom break must NOT cost the
        // lock mid-session. Only "left on and walked away" reaches this. NOT 30 min: that is exactly where
        // Android 12 silently downgrades a long-running scan to opportunistic, and a device test could not
        // then tell that apart from this release breaking rediscovery.
        private const val WAKELOCK_IDLE_MS = 45 * 60_000L
        const val ACTION_MASTER_ON = "com.enderthor.trainerbridgeble.MASTER_ON"
        const val ACTION_MASTER_OFF = "com.enderthor.trainerbridgeble.MASTER_OFF"
        const val ACTION_EMIT_START = "com.enderthor.trainerbridgeble.EMIT_START"
        const val ACTION_EMIT_STOP = "com.enderthor.trainerbridgeble.EMIT_STOP"
        const val ACTION_RECONFIGURE = "com.enderthor.trainerbridgeble.RECONFIGURE"

        private fun intent(context: android.content.Context, action: String) =
            Intent(context, BridgeService::class.java).setAction(action)

        /** Master switch: ON starts the foreground service (receive comes up gated); OFF tears everything down. */
        /** Also called from the Karoo extension's connectDevice, i.e. possibly with the app in background —
         *  where Android 12+ refuses to start a foreground service. Swallow it: the alternative is an
         *  uncaught ForegroundServiceStartNotAllowedException that takes the extension down with it. */
        fun setMaster(context: android.content.Context, on: Boolean) {
            runCatching {
                if (on) context.startForegroundService(intent(context, ACTION_MASTER_ON))
                else context.startService(intent(context, ACTION_MASTER_OFF))
            }.onFailure { FileLog.event("setMaster($on) refused: ${it.message}") }
        }

        /** Config was saved: the running source may no longer be the one config asks for. Sent from the
         *  config screen's onStop too, so swallow a background-start refusal instead of crashing. */
        fun reconfigure(context: android.content.Context) {
            runCatching { context.startService(intent(context, ACTION_RECONFIGURE)) }
        }

        fun startEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_START)) }
        fun stopEmit(context: android.content.Context) { context.startService(intent(context, ACTION_EMIT_STOP)) }
    }
}
