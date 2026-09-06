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
    private var wakeLock: PowerManager.WakeLock? = null
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
    @Volatile private var pendingErgBiasW: Int? = null   // learned but not yet written to prefs (see learnErgBias)
    @Volatile private var lastBiasPersistMs = 0L
    /** Bumped by every start AND stop of the receive half. The source's callbacks capture the value they were
     *  created with and no-op once it moves: a GATT callback already past its own `gatt === g` check when a
     *  source switch lands would otherwise write this session's state (and take the wake lock) on behalf of a
     *  source that no longer exists. */
    @Volatile private var receiveGen = 0
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
        if (sim != null) { if (delta > 0) sim.buttonUp() else sim.buttonDown() }
        else {
            val target = ((lastResistance ?: 0) + delta).coerceIn(0, 200)   // 0..200 per the Zycle's 0x2AD6 range
            // ponytail: 0x04+level% Set Target Resistance, no Request Control first — shares the FTMS control point with the app
            // Deliberately NOT routed through the mirror, so it arms no servo-step budget: this button is the
            // rider, exactly like the bike's own, and the level move it causes SHOULD reach the app.
            // optimistic, and only if the write was at least QUEUED (no link / unknown char → don't move the
            // tile). A stack refusal after queueing still shows briefly; the trainer's own IBD corrects it.
            val bytes = byteArrayOf(0x04, target.toByte())
            if (client?.write(com.enderthor.trainerbridgeble.ble.GattUuids.FTMS_CONTROL_POINT, bytes, true) == true) {
                // The mirror's toZycle lambda is where ErgBias sees control ops, and this path deliberately
                // bypasses it — so tell the learner directly. 0x04 takes the trainer OUT of ERG, and without
                // this its commandedRaw stays pinned to the app's last target: every later reading is then
                // measured against a command no longer in force, saturating the bias and PERSISTING it.
                // ponytail: `write() == true` means QUEUED, not accepted by the stack, so a 0x04 that dies in
                // the queue still retires the command here. That only makes the learner stop learning until
                // the next 0x05 — it cannot poison the bias, which is what this fix is for. Closing it needs
                // the write path to report terminal completion back (the same plumbing an FTMS failure
                // indication would need); do both together or neither.
                ErgBias.onControl(bytes, android.os.SystemClock.elapsedRealtime())
                lastResistance = target
                lastControl = getString(R.string.control_resistance_target, target)
                FileLog.event("UI button → resistance target=$target")
            } else FileLog.event("UI button → resistance target=$target NOT DISPATCHED")
        }
        listener?.invoke()
    }

    /** The BLE stack does not survive a Bluetooth off/on (or a crash of com.android.bluetooth): the GATT
     *  server and the advertising set die with it, and nothing reopens them — the mirror goes silently mute
     *  for the rest of the ride. The central half recovers on its own (its scan retries), so only the emit
     *  half is cycled here. */
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
    }

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
        // Before the try, not after: the catch below logs WHY startForeground failed, and FileLog silently
        // drops anything written before init. One File object is not what blows the 5 s window.
        FileLog.init(this); FileLog.enabled = Config(this).loggingEnabled
        // Session header. Two builds are now in play and a log with no version is a log you cannot trust to
        // be about the code you think it is; the correction values matter because every wattage below is
        // relative to them.
        Config(this).let { c ->
            // via PackageManager rather than BuildConfig: AGP 8 does not generate that class unless
            // buildFeatures.buildConfig is turned on, and one log line does not justify a build change.
            val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
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
        handler.removeCallbacks(snapshot); handler.post(snapshot)   // stops itself once foreground goes false
        // NOT here: the wake lock follows the TRAINER LINK, not the master switch (see the onState callback in
        // startReceive). Held from here it blocked suspend for every hour the master was left on with no
        // trainer in the room — which is most of the day, and the single biggest idle drain in the app.
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
        pendingErgBiasW = null; lastBiasPersistMs = 0L   // 0 = the first learned value of the ride writes at once
        ErgBias.seed(config.ergBiasW)   // start calibrated; there is no live command to measure against yet
        FileLog.event("receive start paired=${config.pairedAddress.ifEmpty { "any" }} sim=${config.simulate} ergBias=${config.ergBiasW}W")
        val gen = ++receiveGen
        // Hopping to main is what makes the generation check MEAN anything: read on a binder thread it is
        // check-then-act, and a source switch landing between the check and the body would let a replaced
        // source drive the live one. startReceive/stopReceive both run on main, so validating there is
        // genuinely serialised against them. Only for the callbacks that can LATCH something.
        val onProfile: (GattProfile) -> Unit = { profile ->
            // The worst of them: MirrorServer.build() is a one-shot latch, so a late profile from the source
            // we just replaced wins it and the new source's real profile is then ignored for the session.
            handler.post { if (gen == receiveGen) { lastProfile = profile; mirror?.build(profile) } }
        }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value ->
            // NOT posted: this is the 4 Hz relay path and a main-looper hop is exactly the latency R2 is
            // about. A plain volatile compare is free, and the residue of check-then-act here is one stale
            // sample relayed — nothing latches, unlike onProfile above.
            if (gen == receiveGen) {
                cacheForUi(config, uuid, value)
                lastValues[uuid] = value      // the one-shot reads happen long before Broadcast is pressed
                mirror?.onZycleValue(uuid, value)
            } else staleCallbacks.incrementAndGet()   // counted, not logged: it would be per-packet
        }
        val onState: (Boolean) -> Unit = { connected ->
          handler.post { if (gen == receiveGen) {   // see onProfile: validated on main, so it is atomic
            zycleConnected = connected
            // The wake lock lives HERE, not in goForeground(): there is data to keep the CPU awake for only
            // while a trainer is actually feeding us.
            // On the DROP it lingers instead of releasing at once. The reconnect is a postDelayed 2 s away,
            // and postDelayed does not wake a suspended CPU — releasing immediately can leave us suspended
            // with NO scan running, and then the trainer's advertising has nothing to arrive at. Once a scan
            // is actually up the controller wakes the AP on a match, so the lock is only needed to bridge
            // that gap. (Residual: if startScan itself keeps failing, its backoff windows are unscanned.)
            if (connected) acquireWakeLock() else {
                handler.removeCallbacks(releaseWakeLockLater)
                handler.postDelayed(releaseWakeLockLater, WAKELOCK_LINGER_MS)
            }
            // Only the DROP is immediate; going on the air waits for onSynced below.
            if (!connected) { zycleSynced = false; mirror?.setTrainerLinked(false); ErgBias.forget() }
            if (!connected) { config.lastSeenAddress = ""; config.lastSeenName = "" }   // the config screen offers it only while live
            status = if (connected) getString(R.string.status_trainer_connected) else getString(R.string.status_searching_trainer); listener?.invoke()
          } }
        }
        // Same treatment: a stale onSynced would put the mirror on the air with no trainer behind it, and
        // setTrainerLinked is edge-triggered, so it would STAY there.
        val onSynced: () -> Unit = { handler.post { if (gen == receiveGen) { zycleSynced = true; mirror?.setTrainerLinked(true) } } }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState, onSynced).also { simSource = it }
        else ZycleClient(this, config.pairedAddress, onProfile, onValue, onState, onSynced,
            // Guarded too, or the replaced source's advertising blueprint and address get written over the
            // live one's. Plain compare: neither latches anything, so the post is not worth the hop.
            onAdv = { bp -> if (gen == receiveGen) { lastAdvBlueprint = bp; mirror?.setAdvBlueprint(bp) } },
            onFound = { name, addr -> if (gen == receiveGen) { config.lastSeenName = name ?: ""; config.lastSeenAddress = addr } })
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
        receiveGen++        // invalidate this source's callbacks BEFORE anything else reads or writes state
        releaseWakeLock()   // no source → nothing to stay awake for (the master switch keeps the FGS alive)
        mirror?.setTrainerLinked(false)   // no source → nothing to advertise, whatever the call order
        Config(this).let { it.lastSeenAddress = ""; it.lastSeenName = "" }
        client?.stop(); client = null; simSource = null; lastProfile = null; lastAdvBlueprint = null; currentSourceKey = null
        // Flush after client.stop() — but note stop() is NOT a callback barrier: a notification already past
        // its `stopped` check can still publish a pending value after this runs, and that last whole-watt
        // step is then lost. Immaterial (the EMA moves ~0.05 W a sample and is re-seeded next ride) and it is
        // the SAFE direction: the dangerous half — a stale sample being persisted into the NEXT session — is
        // closed by the receiveGen guard on onValue, which is what feeds learnErgBias.
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
        val m = MirrorServer(
            context = this,
            advertisedName = config.advertisedName,
            correction = { config.correction() },
            toZycle = { uuid, bytes, withResponse ->
                if (com.enderthor.trainerbridgeble.ble.GattUuids.carriesControl(uuid)) {
                    // `bytes` is already inverse-corrected: exactly the raw watts the trainer is told to hold,
                    // which is what the measured power has to be compared against.
                    ErgBias.onControl(bytes, android.os.SystemClock.elapsedRealtime())
                    lastControl = describeControl(bytes); listener?.invoke()
                }
                client?.write(uuid, bytes, withResponse) ?: false   // false → the mirror answers the app with failure
            },
            onStatus = { s -> status = s; listener?.invoke() },
            onAdvState = { ok -> bleAdvOk = ok; listener?.invoke() },
            isTrainer = { addr ->
                val c = Config(this)
                addr.equals(c.lastSeenAddress, true) || (c.pairedAddress.isNotEmpty() && addr.equals(c.pairedAddress, true))
            },
        )
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
        val w = ErgBias.onPower(raw, android.os.SystemClock.elapsedRealtime()) ?: return
        pendingErgBiasW = w
        // Rate-limited: onPower is fed at 4 Hz, and a converged bias sitting near an integer boundary (the
        // measured overshoot is ~8 W) flips across it over and over — each flip a full rewrite+fsync of the
        // prefs XML, in flash. The stated goal ("start the next ride where this one finished") is met just as
        // well at one-minute granularity, and stopReceive flushes whatever is still pending.
        val now = android.os.SystemClock.elapsedRealtime()
        // `!= 0L` matters: elapsedRealtime is measured from BOOT, so in the first minute of uptime — which on
        // a Karoo that reboots daily and auto-starts this extension is a real moment — `now - 0` is under the
        // interval and the FIRST learned value of the ride would be held back rather than written at once.
        if (lastBiasPersistMs != 0L && now - lastBiasPersistMs < ERG_BIAS_PERSIST_MS) return
        lastBiasPersistMs = now
        persistErgBias(config)
    }

    /** Write out the latest learned bias, if it moved since the last write. */
    private fun persistErgBias(config: Config) {
        val w = pendingErgBiasW ?: return
        pendingErgBiasW = null
        config.ergBiasW = w
        FileLog.event("ERG bias learned: ${w}W (trainer settles above its command)")
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16signed(b: ByteArray, i: Int) = le16(b, i).toShort().toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(btStateReceiver) }
        stopEmit(); stopReceive(); releaseWakeLock(); super.onDestroy()
    }
    override fun onTimeout(startId: Int) {
        stopEmit(); stopReceive(); releaseWakeLock()
        foreground = false   // or a later EMIT_START would pass the foreground gate on a dying service
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

    // Synchronized since the trainer link drives these: onState fires from the GATT binder thread AND from
    // the client's main-thread heartbeat, and two concurrent acquires would strand a lock nothing releases.
    @Synchronized private fun acquireWakeLock() {
        handler.removeCallbacks(releaseWakeLockLater)   // a reconnect inside the linger window keeps the lock
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrainerBridgeBLE:session").also { runCatching { it.acquire() } }
        FileLog.event("wakelock ACQUIRED (trainer linked)")   // the two lines that make E1 verifiable
    }

    @Synchronized private fun releaseWakeLock() {
        handler.removeCallbacks(releaseWakeLockLater)
        val held = wakeLock?.isHeld == true
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }; wakeLock = null
        if (held) FileLog.event("wakelock RELEASED — the CPU may suspend from here")
    }

    /** Deferred release after a trainer drop — see the onState callback in [startReceive]. Re-checks, so a
     *  reconnect inside the linger window keeps the lock. */
    private val releaseWakeLockLater = Runnable { if (!zycleConnected) releaseWakeLock() }

    /**
     * One compact state line a minute. Without it a quiet stretch of log is ambiguous — nothing happened, or
     * the bridge stalled? — and every "permanent death" bug in this project's history looked exactly like
     * silence. It also carries the values you would otherwise have to reconstruct: whether ERG is live, what
     * the learner has settled on, and how far the level we SHOW has drifted from the machine's.
     */
    private val snapshot = object : Runnable {
        override fun run() {
            if (!foreground) return   // master off / service dying: stop the loop, don't outlive it
            if (FileLog.enabled) FileLog.event(
                "state master=${Config(this@BridgeService).masterEnabled} recv=$receiving emit=$emitting " +
                "trainer=${if (zycleSynced) "synced" else if (zycleConnected) "connected" else "-"} " +
                "adv=$bleAdvOk apps=${mirror?.clientCount ?: 0} " +
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
        private const val WAKELOCK_LINGER_MS = 6000L     // hold past the reconnect delay, until a scan is up
        private const val SNAPSHOT_MS = 60_000L          // one state line a minute while the service is up
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
