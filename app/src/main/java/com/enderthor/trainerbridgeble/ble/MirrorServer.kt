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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.enderthor.trainerbridgeble.AdvertisingAttemptCoordinator
import com.enderthor.trainerbridgeble.FileLog
import com.enderthor.trainerbridgeble.FtmsControlCoordinator
import com.enderthor.trainerbridgeble.IdentityOwner
import com.enderthor.trainerbridgeble.R
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
 * @param toZycle    forwards an app write to the trainer's matching characteristic and reports its terminal result.
 */
@SuppressLint("MissingPermission")
class MirrorServer(
    private val context: Context,
    private val advertisedName: String,
    private val correction: () -> PowerCorrection,
    private val toZycle: (charUuid: UUID, bytes: ByteArray, withResponse: Boolean, onComplete: (Boolean) -> Unit) -> Boolean,
    private val onTrainerRecycle: () -> Unit = {},
    /** The trainer ACCEPTED a control procedure (FTMS Response Code = SUCCESS) with these exact bytes.
     *  ERG bias learning and the UI must hang off this, not off the ATT write callback. */
    private val onControlAccepted: (ByteArray) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    /** Health report to the UI: true once we're actually advertising; false if the server/advertising fails. */
    private val onAdvState: (Boolean) -> Unit = {},
    /** The trainer's own address. Android reports our CENTRAL link to it on the SERVER callback too, so
     *  without this it is counted as a connected app: the UI shows a client that isn't there and every
     *  trainer connect triggers a needless advertising restart. */
    private val isTrainer: (String) -> Boolean = { false },
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
    private val ftmsControl = FtmsControlCoordinator()
    /** Deadline for the admitted FTMS procedure. Without it one unanswered Control Point response wedges
     *  the gate and every later request — Request Control included — is refused for the rest of the ride. */
    /** The live procedure and its timer, kept together so a cancellation can prove it owns the deadline.
     *  Mostly main-looper, but rebuildServer() reaches it from a binder thread, hence @Volatile. */
    @Volatile private var procedureDeadline: Pair<FtmsControlCoordinator.Procedure, Runnable>? = null
    /** The client whose TERMINAL Control Point result is currently in flight, so a later failure report
     *  from onNotificationSent can be attributed. Measurement notifications never set this. */
    @Volatile private var terminalIndication: FtmsControlCoordinator.Client? = null
    // touched from the GATT server binder thread, the client's binder thread and main — a plain ArrayDeque
    // can throw mid-poll when stop() clears it, and an exception on a binder callback kills the process
    private val pendingServices = java.util.concurrent.ConcurrentLinkedDeque<BluetoothGattService>()

    // Armed only AFTER the trainer write completes, so it measures the machine and not our own op queue.
    // FTMS conformance ties the collector's wait to the ATT transaction timeout (~30 s); anything shorter
    // recycles the trainer link on a healthy-but-slow machine, which costs the rider far more than waiting.
    private val PROCEDURE_TIMEOUT_MS = 30_000L
    private val ADV_RESTART_MS = 250L
    private val ADV_RETRY_MS = 1000L
    private val ADV_RETRY_MAX_MS = 30_000L   // backoff ceiling; there is no attempt cap (see scheduleAdvRetry)
    private val SERVICE_RETRY_MS = 300L
    private val SERVICE_MAX_RETRIES = 5
    private val SERVICE_ADD_TIMEOUT_MS = 8000L
    private val SERVICE_REBUILD_MS = 2000L
    private val SERVICE_REBUILD_MAX_MS = 30_000L
    // Generous on purpose: this must only ever fire for a callback that is genuinely LOST (adapter off, BT
    // process died). Firing it for one that is merely slow starts a second attempt against the same shared
    // callback object — see scheduleAdvRetry's note.
    private val ADV_START_TIMEOUT_MS = 8000L
    private val SERVER_RETRY_MS = 2000L
    private val SERVER_RETRY_MAX_MS = 30_000L   // Bluetooth off is a whole-ride failure, not a hiccup
    /** First re-stop of an orphan; doubles to [ADV_SWEEP_MAX_MS] while any remain. Not a bound on when the
     *  stack answers a start — there is none — just the rate at which we keep asking. */
    private val ADV_SWEEP_MS = 1000L
    private val ADV_SWEEP_MAX_MS = 30000L
    /** How long after a control write the trainer's level is still settling on it. Observed on the 28-jul
     *  ride: every servo-driven level step landed 0.19-2.6 s after the write that caused it. */
    private val LEVEL_SETTLE_MS = 3000L
    @Volatile private var serviceRetries = 0
    private val serviceRetryRunnable = Runnable { if (server != null) addNextService() }
    private val serviceAddOwner = IdentityOwner<BluetoothGattService>()
    @Volatile private var serviceAddWatchdog: Runnable? = null
    @Volatile private var serviceRebuildMs = SERVICE_REBUILD_MS
    private val ADVERTISE_FAILED_DATA_TOO_LARGE = 1
    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ATT_UNLIKELY_ERROR = 0x0E
    private val ATT_INVALID_OFFSET = 0x07
    private val ATT_REQUEST_NOT_SUPPORTED = 0x06
    private val ATT_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D
    private var originalName: String? = null
    @Volatile private var advertising = false
    @Volatile private var advStarting = false   // a start is in flight; `advertising` only flips in the callback
    private val advAttempts = AdvertisingAttemptCoordinator<AdvertiseCallback>()
    private val built = java.util.concurrent.atomic.AtomicBoolean(false)   // build the mirrored GATT once; a trainer reconnect keeps it
    @Volatile private var advBlueprint: AdvBlueprint? = null   // the trainer's real advertising, to clone
    @Volatile private var trainerLinked = false   // advertise only while a trainer is actually feeding us
    @Volatile private var servicesReady = false     // every mirrored service has been ADDED (built != added)
    @Volatile private var advRetries = 0        // diagnostic count only; the retry policy is advRetryMs
    @Volatile private var advRetryMs = ADV_RETRY_MS
    @Volatile private var dropNameFromAdv = false   // set after DATA_TOO_LARGE: the packet won't fit the name
    @Volatile private var dropMfrFromAdv = false    // shed the cloned manufacturer data first
    @Volatile private var dropBlueprintFromAdv = false   // then the cloned UUIDs, falling back to the standard pair

    /** Gate advertising on the trainer link. We advertise under the trainer's own name, and the trainer
     *  starts advertising again the moment it drops — so advertising with no trainer behind us puts two
     *  identical devices in the air and lets an app bind to a bridge that has no data to give it. */
    fun setTrainerLinked(linked: Boolean) {
        val controller = if (linked) null else ftmsControl.trainerDropped()
        if (trainerLinked == linked) {
            controller?.let { handler.post { cancelClient(it) } }
            return
        }
        trainerLinked = linked
        // Losing the trainer invalidates the level anchor (see [reanchorLevel]) AND any servo step we were
        // still owed: the write that bought it may never have reached the trainer, and if it did, the step it
        // caused is on the far side of the outage where the re-anchor absorbs it anyway. Leaving it armed
        // means the rider's first press after a fast reconnect is eaten instead — and this codebase's settled
        // bias is that eating a real press is the worse failure (pinning the byte killed the buttons).
        // Getting the link back also deserves a fresh advertise backoff.
        if (!linked) { reanchorLevel = true; servoStepOwed = false; lastControlWriteMs = 0L }
        else { advRetries = 0; advRetryMs = ADV_RETRY_MS }
        FileLog.event("mirror trainer link=$linked -> ${if (linked) "advertise" else "stop advertising"}")
        handler.post {
            if (linked) startAdvertising() else {
                stopAdvertising()
                controller?.let { cancelClient(it) }
            }
        }
    }

    /** Adopt the trainer's own advertised service UUIDs + manufacturer data (captured by the client) so we
     *  advertise an identical packet. If we're already advertising, restart to apply it. */
    fun setAdvBlueprint(bp: AdvBlueprint) {
        advBlueprint = bp
        // A new blueprint deserves a fresh retry budget — and that means the BACKOFF, not just the diagnostic
        // count: at the 30 s ceiling, resetting only the counter changed nothing. Keep the size shedding.
        advRetries = 0; advRetryMs = ADV_RETRY_MS
        FileLog.event("mirror adv blueprint: ${bp.serviceUuids.size} uuids, ${bp.manufacturerData.size} mfr")
        handler.post { if (advertising) restartAdvertising() }   // serialise onto the advertising thread
    }

    fun start() {
        stopped = false
        // Kill any advertising set left running by a PREVIOUS instance: its stop may have been dropped
        // because a start was still in flight, and its callback died with the object.
        lastAdvCallback?.let { orphan ->
            FileLog.event("stopping an advertising set left by a previous mirror (unresolved=$lastAdvUnresolved)")
            // Only an unresolved start can have its stop dropped, so only that one needs the sweep. A
            // resolved one is stopped once, reliably — adopting it would strand it in advOrphans forever.
            if (lastAdvUnresolved) orphanAdvCallback(orphan)
            else {
                runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(orphan) }
                if (lastAdvCallback === orphan) lastAdvCallback = null
            }
        }
        if (advOrphans.isNotEmpty()) {   // orphans from an earlier instance keep being swept by this one
            advSweepMs = ADV_SWEEP_MS
            orphanHandler.removeCallbacks(advSweep); orphanHandler.postDelayed(advSweep, advSweepMs)
        }
        openServer()
    }

    /** Open the GATT server, retrying while emit is on. A null here is usually the stack restarting, and
     *  returning on it left the whole emit half dead for the session with no path back — [build] had already
     *  been called by then, so even a later recovery would have had nothing to serve. Hence [pendingProfile]. */
    private fun openServer() {
        if (stopped || server != null) return
        val srv = runCatching { mgr.openGattServer(context, serverCallback) }.getOrNull()
        if (srv == null) {
            // Backoff, not a flat retry: the common cause (Bluetooth off, stack not coming back) lasts the
            // whole ride, and at a fixed 2 s that is ~1800 attempts an hour, each one a log line — the same
            // mistake RawAntLink.scheduleReopen already documents having made.
            val wait = serverRetryMs
            serverRetryMs = (wait * 2).coerceAtMost(SERVER_RETRY_MAX_MS)
            FileLog.event("openGattServer returned null — retrying in ${wait}ms")
            onStatus(context.getString(R.string.status_ble_server_failed)); onAdvState(false)
            handler.removeCallbacks(serverRetryRunnable); handler.postDelayed(serverRetryRunnable, wait)
            return
        }
        serverRetryMs = SERVER_RETRY_MS   // open: a later failure starts its backoff from scratch
        // Publishing the server and claiming the held profile must be ONE step, under the same lock build()
        // uses. As two independent volatiles they interleave: build() reads server==null on a binder thread,
        // we publish and find pendingProfile still null, then build() stores it — and nobody ever replays it,
        // leaving an open GATT server with no services that can never advertise.
        val replay = synchronized(serverLock) { server = srv; pendingProfile.also { pendingProfile = null } }
        renameAdapter()
        replay?.let { FileLog.event("mirror server opened — building the profile we held"); build(it) }
    }

    private val serverRetryRunnable = Runnable { openServer() }
    /** Guards the server/pendingProfile handover only — never held across a GATT call. */
    private val serverLock = Any()
    @Volatile private var serverRetryMs = SERVER_RETRY_MS
    @Volatile private var stopped = false
    /** A profile handed to [build] before the server existed, replayed once it does. */
    @Volatile private var pendingProfile: GattProfile? = null
    @Volatile private var latestProfile: GattProfile? = null

    /** Rename the adapter to our advertised name, persisting the ORIGINAL to prefs so a process kill (which
     *  skips stop()) doesn't lose the user's real Bluetooth name — and so we never capture our own rename. */
    private fun renameAdapter() {
        val prefs = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)
        if (advertisedName.isBlank()) {
            // No custom name: don't touch the adapter at all. The advert carries the device's own name, and
            // none of the capture/restore machinery below can misfire, because it never runs. The advertised
            // name is cosmetic (a captured session proved an app negotiates fine under any name), so this is
            // the better default: nothing to lose, nothing to restore, no second device wearing our name.
            prefs.getString(KEY_ORIG_NAME, null)?.let { orig ->   // migration: hand back a name we once took
                FileLog.event("custom name cleared - restoring $orig")
                runCatching { adapter.name = orig }
                prefs.edit().remove(KEY_ORIG_NAME).apply()
            }
            return
        }
        val current = runCatching { adapter.name }.getOrNull()
        // Remember every name WE have ever advertised. Without this the read-back after a stop→start (the
        // rename is asynchronous, so it still shows the PREVIOUS advertised name) gets stored as if it were
        // the user's real device name — which is how a Karoo ends up permanently called "ZycleBike7".
        val ours = prefs.getStringSet(KEY_ADV_NAMES, emptySet())!!.toMutableSet()
        if (ours.add(advertisedName)) prefs.edit().putStringSet(KEY_ADV_NAMES, ours).apply()
        // Capture the real name when we see one that is not ours — first run, or the user renamed the
        // device since. Never capture a name we know we put there.
        if (current != null && current != advertisedName && current !in ours &&
            current != prefs.getString(KEY_ORIG_NAME, null))
            prefs.edit().putString(KEY_ORIG_NAME, current).apply()
        originalName = prefs.getString(KEY_ORIG_NAME, null)
        runCatching { if (current != advertisedName) adapter.name = advertisedName }
    }

    /** ponytail: KEY_ORIG_NAME is deliberately NOT cleared. adapter.name is set asynchronously, so a
     *  stop→start within the same second (a config save that changes the advertised name) would read back
     *  our own name, fail to store it, and lose the user's real one forever. Keeping it costs a stale pref
     *  if the user renames the device while we hold it; clear it on restore if that ever matters. */
    private fun restoreName() {
        if (advertisedName.isBlank()) return   // we never renamed anything
        val prefs = context.getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)
        // Restore unconditionally. Gating on `adapter.name == advertisedName` looked safer but is unsound:
        // the rename is asynchronous, so a stop right after a start reads back the OLD name, skips the
        // restore, and leaves the user's phone named after the trainer. Losing a rename the user made
        // mid-session is the lesser evil, and only a stale pref can cause it.
        val orig = originalName ?: prefs.getString(KEY_ORIG_NAME, null)
        if (orig != null) runCatching { adapter.name = orig }
        originalName = null
    }

    /** Build the mirrored server from the trainer's discovered GATT. Services are added one at a time.
     *  Built ONCE per session: a trainer reconnect re-discovers the SAME GATT, so we keep the existing
     *  services + characteristics (re-adding them would strand apps still subscribed to the old instances
     *  and there is no clean live rebuild). */
    fun build(profile: GattProfile) {
        latestProfile = profile
        // No server yet (it is being retried): hold the profile rather than drop it, or a server that opens
        // on the second attempt would have no services and would therefore never advertise. Under the same
        // lock openServer() claims it with, so the read and the store cannot straddle the handover.
        val srv = synchronized(serverLock) { server ?: run { pendingProfile = profile; null } } ?: return
        // Atomic gate: build() is called from both the GATT binder thread (onServicesDiscovered) and the main
        // thread (Start) — a plain check-then-set could let both through and double-add the services.
        if (!built.compareAndSet(false, true)) return
        servicesReady = false
        // Only reachable on a REBUILD (the latch was released after addService gave up), and then the server
        // still holds whatever did get added — re-adding over it yields a duplicated GATT.
        runCatching { srv.clearServices() }
        // subscribers/clients too: clearServices() moves every ATT handle, so a peer that reconnected to a
        // remembered MAC would be tracked as subscribed to characteristic objects that no longer exist.
        // serviceRetries, or a rebuild starts with the budget the failed build already spent.
        chars.clear(); pendingServices.clear(); subscribers.clear(); serviceRetries = 0
        for (svc in profile.services) {
            if (GattUuids.isStackService(svc.uuid)) continue
            val service = BluetoothGattService(svc.uuid,
                if (svc.primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY)
            for (cs in svc.chars) {
                // Android's CLIENT-side getPermissions() is 0 (permissions aren't exposed to a central), so
                // mirroring cs.permissions verbatim leaves the char with NO read/write permission and apps
                // can't use it. Derive permissions from the discovered PROPERTIES instead.
                val ch = BluetoothGattCharacteristic(cs.uuid, cs.properties, permissionsFor(cs.properties))
                for (dU in cs.descriptors) ch.addDescriptor(BluetoothGattDescriptor(dU,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                // Ensure a CCCD exists on notify/indicate chars even if the trainer didn't list it.
                if (cs.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && ch.getDescriptor(cccd) == null)
                    ch.addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                service.addCharacteristic(ch)
                chars[cs.uuid] = ch
                FileLog.event("mirror char ${shortUuid(svc.uuid)}/${shortUuid(cs.uuid)} props=${cs.properties}")
            }
            pendingServices.add(service)
        }
        FileLog.event("mirror built ${pendingServices.size} services")
        if (pendingServices.isEmpty()) {
            // Advertising an empty GATT is worse than not advertising: an app connects and caches nothing.
            // Release the build latch so a later, real discovery can still populate the mirror.
            FileLog.event("mirror has NO services — not advertising, waiting for a real profile")
            built.set(false)
        } else addNextService()
    }

    /** Read/write GATT permissions for a mirrored characteristic, derived from its BLE properties. */
    private fun permissionsFor(properties: Int): Int {
        var perm = 0
        if (properties and (BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            perm = perm or BluetoothGattCharacteristic.PERMISSION_READ
        if (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0)
            perm = perm or BluetoothGattCharacteristic.PERMISSION_WRITE
        return perm
    }

    /** PEEK, don't poll: the service stays at the head until its own onServiceAdded confirms it. Removing it
     *  up front let a late success (the stack had queued the "refused" add after all) and the retry both
     *  drive the chain — adding a service twice and letting `servicesReady` fire with an add still in flight. */
    @Synchronized private fun addNextService() {
        if (serviceAddOwner.current != null) return
        val svc = pendingServices.peek() ?: return
        // Claim ownership BEFORE the binder call: onServiceAdded can arrive on a binder thread before a
        // post-call assignment lands, and a callback that finds no owner is discarded as stale — costing an
        // 8 s watchdog and a whole server rebuild for an add that actually succeeded.
        val watchdog = Runnable {
            if (serviceAddOwner.clearIfCurrent(svc) { serviceAddWatchdog = null })
                rebuildServer("addService callback timeout for ${shortUuid(svc.uuid)}")
        }
        serviceAddWatchdog = watchdog
        handler.postDelayed(watchdog, SERVICE_ADD_TIMEOUT_MS)
        serviceAddOwner.replace(svc)
        val accepted = runCatching { server?.addService(svc) }.getOrNull() == true
        // Retract the claim only if the callback has not already consumed it (it may have completed the
        // add while the binder call was still returning).
        if (!accepted && serviceAddOwner.clearIfCurrent(svc) { serviceAddWatchdog = null }) {
            handler.removeCallbacks(watchdog)
            if (serviceRetries++ >= SERVICE_MAX_RETRIES) {
                rebuildServer("addService refused for ${shortUuid(svc.uuid)}")
                return
            }
            FileLog.event("mirror addService REFUSED for ${shortUuid(svc.uuid)} — retry ${serviceRetries}")
            handler.removeCallbacks(serviceRetryRunnable); handler.postDelayed(serviceRetryRunnable, SERVICE_RETRY_MS)
        }
    }

    private fun rebuildServer(reason: String) {
        if (stopped) return
        val wait = serviceRebuildMs
        serviceRebuildMs = (wait * 2).coerceAtMost(SERVICE_REBUILD_MAX_MS)
        FileLog.event("mirror service build failed: $reason — reopening in ${wait}ms")
        servicesReady = false
        built.set(false)
        serviceAddOwner.clear()
        serviceAddWatchdog?.let { handler.removeCallbacks(it) }
        serviceAddWatchdog = null
        handler.removeCallbacks(serviceRetryRunnable)
        pendingServices.clear()
        chars.clear()
        // Closing the server invalidates every ATT handle and delivers NO disconnect callbacks, so any
        // controller identity kept here would strand FTMS ownership on a generation that can never return:
        // the app reconnects, gets a new generation, and is refused CONTROL_NOT_PERMITTED for the ride.
        procedureDeadline?.let { handler.removeCallbacks(it.second) }
        procedureDeadline = null
        ftmsControl.clear()
        terminalIndication = null
        clients.clear(); subscribers.clear()
        // ...and never keep broadcasting under the trainer's name with no server behind it: an app that
        // connects during the rebuild window finds no services and caches a broken device for the session.
        stopAdvertising()
        val failedServer = synchronized(serverLock) {
            server.also {
                server = null
                pendingProfile = latestProfile
            }
        }
        runCatching { failedServer?.close() }
        onAdvState(false)
        handler.removeCallbacks(serverRetryRunnable)
        handler.postDelayed(serverRetryRunnable, wait)
    }

    fun stop() {
        // Stop the advertiser UNCONDITIONALLY: the `advertising` flag is transiently false mid-restart, so
        // trusting it here can leave the phone broadcasting with a closed GATT server.
        stopped = true; pendingProfile = null; latestProfile = null; servicesReady = false
        serviceAddOwner.clear()
        serviceAddWatchdog?.let { handler.removeCallbacks(it) }
        serviceAddWatchdog = null
        ftmsControl.clear()
        terminalIndication = null
        procedureDeadline?.let { handler.removeCallbacks(it.second) }
        procedureDeadline = null
        stopAdvertising()
        handler.removeCallbacksAndMessages(null)   // pending adv starts / service retries must not outlive us
        runCatching { server?.close() }
        server = null
        restoreName()
        built.set(false); advBlueprint = null
        chars.clear(); cache.clear(); subscribers.clear(); clients.clear(); pendingServices.clear()
        shownZycleLevel = null; lastRawZycleLevel = null; lastControlWriteMs = 0L; servoStepOwed = false; reanchorLevel = false
    }

    /**
     * The Zycle level byte we report. It is NOT the trainer's: it starts there and then moves only by the
     * steps the RIDER makes on the bike's own +/- buttons. Measured over a 38 min ride, 118 of the 126 level
     * changes we relayed verbatim were followed within 45-100 ms by a target write the app never meant to
     * make — the servo settling on our own command, read by the app as the rider changing intensity. Pinning
     * the byte outright stopped that, and also stopped the rider's real button presses from ever reaching
     * the app (28-jul ride: the level walked 7→16 and 10→90 under the rider's thumb while the app sat at 6).
     * So: each control write we relay buys the servo one level step, and every other step is the rider's.
     * The rule itself, and why it is a budget rather than a time window, is in [PowerRewrite.levelToShow].
     *
     * ponytail: a budget of one, not a model of the servo. Replayed against both rides it still leaks 13 of
     * 125 servo steps to the app and still eats about half the rider's presses in a fast burst; only the
     * machine's real level↔watts curve would separate them exactly.
     */
    @Volatile private var shownZycleLevel: Int? = null    // what the app sees
    @Volatile private var lastRawZycleLevel: Int? = null  // what the trainer last reported, to difference against
    @Volatile private var lastControlWriteMs = 0L         // elapsedRealtime of the last control write we relayed
    @Volatile private var servoStepOwed = false           // that write has a level step coming; it is not the rider's
    /** The trainer link dropped, so the next level we see must be RE-ANCHORED rather than differenced.
     *  A drop does not stop the mirror (the GATT and the connected apps are deliberately kept), so without
     *  this the pair above straddles the outage and the first frame back is differenced against a level from
     *  before it — delivering the whole gap to the app in one step, as if the rider had made it. */
    @Volatile private var reanchorLevel = false

    /** For the service's periodic snapshot: how many apps are attached, and how far the level we report has
     *  drifted from the machine's (shown/raw — they diverge by every servo step we absorbed, by design). */
    val clientCount: Int get() = clients.size
    val levelDebug: String get() = "${shownZycleLevel ?: "-"}/${lastRawZycleLevel ?: "-"}"

    /** Non-null once admitted: hand the same token back so a late failure cannot kill a newer procedure.
     *  The bytes travel WITH the procedure — a side slot let one procedure's response commit another's
     *  target, and left the local button with no payload at all. */
    internal fun admitLocalControl(opcode: Int, bytes: ByteArray): FtmsControlCoordinator.Procedure? =
        ftmsControl.admitLocal(opcode, bytes)?.also {
            FileLog.event("ftms ADMITTED local op=0x%02X #%d".format(opcode, it.id))
        }
    internal fun localControlTransportFailed(procedure: FtmsControlCoordinator.Procedure) {
        // Only the procedure that was actually still pending may cancel its deadline: a stale failure
        // arriving after a newer procedure was admitted must not disarm the newer one's timer.
        if (ftmsControl.transportFailed(procedure) != null) cancelProcedureDeadline(procedure)
    }
    /** The trainer took the write. Only now does the response clock start — arming at admission also
     *  measured our own serialised op queue, so a slow predecessor could expire a healthy procedure. */
    internal fun localControlDispatched(procedure: FtmsControlCoordinator.Procedure) =
        armProcedureDeadline(procedure)

    fun releaseFtmsQuarantine() {
        ftmsControl.trainerReady()
        FileLog.event("ftms quarantine released")
    }

    private fun armProcedureDeadline(procedure: FtmsControlCoordinator.Procedure) {
        handler.post {
            // An arm can reach main AFTER its own procedure ended and a newer one was armed — the local
            // button path crosses two main-loop hops, so it loses that race routinely. Replacing the live
            // deadline here would strip a healthy procedure of its only timer and leave it unbounded.
            if (!ftmsControl.isPending(procedure)) return@post
            procedureDeadline?.let { handler.removeCallbacks(it.second) }
            val deadline = Runnable {
                procedureDeadline = null
                // A null return means "not the pending procedure" — the ONLY case that may skip recovery.
                // A matched LOCAL procedure legitimately has no client, and skipping recovery for it left
                // the session quarantined with nothing able to lift it for the rest of the ride.
                val terminated = ftmsControl.timedOut(procedure) ?: return@Runnable
                FileLog.event("ftms TIMEOUT op=0x%02X #%d — no trainer response in ${PROCEDURE_TIMEOUT_MS}ms"
                    .format(procedure.opcode, procedure.id))
                terminated.client?.let {
                    notifyControlResult(it, byteArrayOf(
                        0x80.toByte(), procedure.opcode.toByte(),
                        FtmsControlCoordinator.OPERATION_FAILED.toByte()))
                }
                // a late opcode-only response can no longer be matched to a request: recycle the link
                handler.post(onTrainerRecycle)
            }
            procedureDeadline = procedure to deadline
            handler.postDelayed(deadline, PROCEDURE_TIMEOUT_MS)
        }
    }

    /** Cancels ONLY this procedure's deadline. Arming and cancelling are both posted from binder threads,
     *  so "cancel whatever is current" could disarm the timer of a procedure admitted in between. */
    private fun cancelProcedureDeadline(procedure: FtmsControlCoordinator.Procedure) {
        handler.post {
            val live = procedureDeadline ?: return@post
            if (live.first.id != procedure.id) return@post
            handler.removeCallbacks(live.second)
            procedureDeadline = null
        }
    }

    /** The trainer's verdict. Only SUCCESS commits UI/servo/ERG state, and it commits the bytes THAT
     *  procedure carried — including the local button's, which must reach ErgBias so an armed ERG target
     *  is retired when the rider takes manual control. */
    private fun onControlOutcome(terminated: FtmsControlCoordinator.Terminated, accepted: Boolean) {
        if (!accepted) return
        lastControlWriteMs = SystemClock.elapsedRealtime()
        servoStepOwed = true
        terminated.bytes?.let(onControlAccepted)
    }

    /** A value arrived from the trainer: correct power, cache, and notify every subscribed client. */
    fun onZycleValue(charUuid: UUID, value: ByteArray) {
        if (charUuid == GattUuids.FTMS_CONTROL_POINT && value.size >= 3 &&
            value[0].toInt() and 0xFF == 0x80) {
            val opcode = value[1].toInt() and 0xFF
            val result = value[2].toInt() and 0xFF
            ftmsControl.response(opcode, result)?.let { terminated ->
                cancelProcedureDeadline(terminated.procedure)
                FileLog.event("ftms RESPONSE op=0x%02X result=0x%02X #%d -> %s".format(opcode, result,
                    terminated.procedure.id,
                    terminated.client?.let { "${it.address}#${it.generation}" } ?: "local"))
                terminated.client?.let { notifyControlResult(it, value) }
                onControlOutcome(terminated, result == FtmsControlCoordinator.SUCCESS)
            }
            return
        }
        val out = when {
            charUuid == GattUuids.INDOOR_BIKE_DATA -> PowerRewrite.correctIndoorBikeData(value, correction())
            charUuid == GattUuids.CYCLING_POWER_MEASUREMENT -> PowerRewrite.correctCyclingPower(value, correction())
            // Machine Status "Target Power Changed" echoes the (inverse-corrected) watts we commanded, so
            // correct it forward or the app reads back a target it never asked for
            charUuid == GattUuids.MACHINE_STATUS -> PowerRewrite.correctMachineStatusTargetPower(value, correction())
            // Zycle's own telemetry carries the same watts as 0x2AD2 and Bestcycling subscribes to both:
            // one bridge must never hand one app two different numbers for the same instant.
            charUuid == GattUuids.ZYCLE_TELEMETRY -> {
                PowerRewrite.zycleLevel(value)?.let { raw ->
                    // First frame after a dropout: re-anchor onto whatever the trainer says now, so the gap it
                    // moved through while we were blind is NOT differenced into the app's view. Deliberately
                    // NOT by nulling shownZycleLevel — levelToShow's first-frame rule adopts the raw level,
                    // which is the same jump by another route. A genuine press made during the outage is lost;
                    // we could not have seen it.
                    // ponytail: the flag has no expiry, so a press landing between the link coming back and
                    // the first telemetry frame is absorbed into the anchor too. That window is one frame
                    // (~250 ms at 4 Hz); bounding it with a timer would cost state and re-open the far worse
                    // jump this exists to stop. Revisit only if a trainer is seen going quiet after reconnect.
                    val reanchored = reanchorLevel
                    if (reanchorLevel) { lastRawZycleLevel = raw; reanchorLevel = false }
                    val prevRaw = lastRawZycleLevel; val prevShown = shownZycleLevel
                    // The budget expires: 57 of 169 writes moved no level at all, and an armed one left
                    // lying around would eat the rider's next press minutes later.
                    val owed = servoStepOwed && SystemClock.elapsedRealtime() - lastControlWriteMs < LEVEL_SETTLE_MS
                    val next = PowerRewrite.levelToShow(shownZycleLevel, lastRawZycleLevel, raw, owed)
                    shownZycleLevel = next.level; servoStepOwed = next.servoStepOwed; lastRawZycleLevel = raw
                    // THE line that makes the servo-vs-rider rule auditable. Every claim in this file's
                    // comments ("118 of 125", "111 of 169 writes → 1 step") came from reconstructing this by
                    // hand out of hex dumps; logged directly, a ride answers it by counting lines. Only when
                    // the level actually moved or we re-anchored — a few hundred lines a ride, not 4 Hz.
                    if (FileLog.enabled && (reanchored || raw != prevRaw))
                        FileLog.event("level raw=$prevRaw->$raw shown=$prevShown->${next.level} " +
                            (if (reanchored) "REANCHOR" else if (owed) "SERVO(spent)" else "RIDER") +
                            " owedAfter=${next.servoStepOwed}")
                }
                PowerRewrite.correctZycleTelemetry(value, correction(), shownZycleLevel)
            }
            else -> value
        }
        cache[charUuid] = out
        val ch = chars[charUuid] ?: return
        val subs = subscribers[charUuid] ?: return
        if (subs.isEmpty()) return
        logRelay(charUuid, value, out, subs.size)
        handler.post {
            val srv = server ?: return@post
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val targets = synchronized(subs) { subs.toList() }   // copy under lock — a CCCD write may mutate it concurrently
            for (addr in targets) {
                val dev = clients[addr] ?: continue
                notify(srv, dev, ch, out, indicate)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(srv: BluetoothGattServer, dev: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray, indicate: Boolean): Boolean {
        return runCatching {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                srv.notifyCharacteristicChanged(dev, ch, indicate, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else {
                ch.value = value
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(dev, ch, indicate)
            }
            // a refused notification is a silently dropped sample — "power froze in the app"
            if (!ok) FileLog.event("notify ${shortUuid(ch.uuid)} REFUSED (buffer full?) -> ${dev.address}")
            ok
        }.getOrDefault(false)
    }

    /** EVERY path that fails to hand a TERMINAL result to its origin must release that origin's claim:
     *  a controller waiting for a response it will never see would otherwise keep ERG locked for everyone.
     *  A rejection is not terminal for an admitted procedure and carries no such authority — a stale one
     *  could otherwise revoke ownership the same client legitimately acquired in the meantime. */
    private fun undeliverable(client: FtmsControlCoordinator.Client, why: String, terminal: Boolean) {
        if (!terminal || !ftmsControl.owns(client)) return
        if (ftmsControl.releaseOwner(client))
            FileLog.event("ftms terminal result undeliverable ($why) -> ${client.address}#${client.generation} — owner released")
    }

    private fun notifyControlResult(
        client: FtmsControlCoordinator.Client,
        value: ByteArray,
        terminal: Boolean = true,
    ) {
        val uuid = GattUuids.FTMS_CONTROL_POINT
        val ch = chars[uuid] ?: return undeliverable(client, "no local characteristic", terminal)
        val subs = subscribers[uuid] ?: return undeliverable(client, "no subscriber set", terminal)
        if (ftmsControl.identity(client.address) != client) return undeliverable(client, "stale generation", terminal)
        if (!synchronized(subs) { subs.contains(client.address) }) return undeliverable(client, "not subscribed", terminal)
        handler.post {
            val srv = server ?: return@post undeliverable(client, "server closed", terminal)
            if (ftmsControl.identity(client.address) != client) return@post undeliverable(client, "stale generation", terminal)
            val dev = clients[client.address] ?: return@post undeliverable(client, "device gone", terminal)
            val currentSubs = subscribers[uuid] ?: return@post undeliverable(client, "no subscriber set", terminal)
            if (!synchronized(currentSubs) { currentSubs.contains(client.address) })
                return@post undeliverable(client, "unsubscribed before send", terminal)
            if (terminal) terminalIndication = client
            if (!notify(srv, dev, ch, value, ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) {
                terminalIndication = null
                // The controller would wait forever for a response it will never get. Drop its claim and cut
                // the link so it reconnects and re-arbitrates instead of sitting there believing it owns ERG.
                undeliverable(client, "indication refused", terminal)
                cancelClient(client)
            }
        }
    }

    private fun cancelClient(client: FtmsControlCoordinator.Client) {
        if (ftmsControl.identity(client.address) != client) return
        clients[client.address]?.let { server?.cancelConnection(it) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @Synchronized override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            val added = service ?: return
            if (!serviceAddOwner.clearIfCurrent(added) {
                    serviceAddWatchdog?.let { handler.removeCallbacks(it) }
                    serviceAddWatchdog = null
                }) return
            // A callback still in flight when stop() ran would otherwise find pendingServices empty, set
            // servicesReady and post a start — putting us back on the air with a closed GATT server.
            if (stopped || server == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // don't poll it: retry the head rather than advertise a mirror missing a service
                FileLog.event("mirror addService FAILED status=$status for ${shortUuid(added.uuid)} — retrying head")
                if (serviceRetries++ < SERVICE_MAX_RETRIES) {
                    handler.removeCallbacks(serviceRetryRunnable); handler.postDelayed(serviceRetryRunnable, SERVICE_RETRY_MS)
                } else rebuildServer("addService callback status=$status for ${shortUuid(added.uuid)}")
                return
            }
            handler.removeCallbacks(serviceRetryRunnable)   // a stale retry would add the NEXT service twice
            pendingServices.poll()   // confirmed — NOW it leaves the queue
            serviceRetries = 0
            if (pendingServices.isEmpty()) {   // the mirrored GATT is complete
                servicesReady = true
                serviceRebuildMs = SERVICE_REBUILD_MS
                handler.post { startAdvertising() }
            } else addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (isTrainer(device.address)) {   // our own central link surfacing on the server callback
                FileLog.event("server sees the trainer link ${device.address} — not an app, ignored")
                return
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                ftmsControl.connected(device.address)
                clients[device.address] = device
                onStatus(context.getString(R.string.status_app_connected, clients.size))
                FileLog.event("app connected ${device.address} status=$status (${clients.size} total)")
                // Android stops connectable advertising once a central connects — restart it so a SECOND
                // central (e.g. the Garmin) can still discover us.
                handler.post { restartAdvertising() }
            } else {
                val lostPending = ftmsControl.disconnected(device.address)?.also {
                    cancelProcedureDeadline(it.procedure)
                } != null
                clients.remove(device.address); subscribers.values.forEach { it.remove(device.address) }
                onStatus(context.getString(R.string.status_app_disconnected, clients.size))
                FileLog.event("app disconnected ${device.address} status=$status (${clients.size} left)")
                if (lostPending) handler.post(onTrainerRecycle)
                handler.post { restartAdvertising() }   // the controller stopped our advert when it connected
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic?) {
            val full = ch?.let { cache[it.uuid] }
            val who = "${shortUuid(ch?.uuid)}${if (offset > 0) " off=$offset" else ""} <- ${device?.address}"
            if (full == null) {
                // Not read from the trainer yet (its reads queue behind every subscribe). Answer ATT
                // "Unlikely Error" so the client can retry — an empty SUCCESS reads as "no capabilities".
                FileLog.event("app read $who — cache cold, answering 0x0E")
                runCatching { server?.sendResponse(device, requestId, ATT_UNLIKELY_ERROR, offset, ByteArray(0)) }
                return
            }
            if (offset < 0 || offset > full.size) {
                FileLog.event("app read $who — invalid offset, answering 0x07")
                runCatching { server?.sendResponse(device, requestId, ATT_INVALID_OFFSET, offset, ByteArray(0)) }
                return
            }
            val value = full.copyOfRange(offset, full.size)
            FileLog.event("app read $who = ${FileLog.hex(value)}")   // the VALUE, not just the request
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, ch: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val uuid = ch?.uuid
            if (preparedWrite && uuid == GattUuids.FTMS_CONTROL_POINT) {
                FileLog.event("app write ${shortUuid(uuid)} <- ${device?.address} PREPARED rejected")
                if (responseNeeded) runCatching {
                    server?.sendResponse(device, requestId, ATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
                return
            }
            if (uuid == GattUuids.FTMS_CONTROL_POINT && value?.isEmpty() == true) {
                FileLog.event("app write ${shortUuid(uuid)} <- ${device?.address} empty rejected")
                if (responseNeeded) runCatching {
                    server?.sendResponse(device, requestId, ATT_INVALID_ATTRIBUTE_VALUE_LENGTH, offset, null)
                }
                return
            }
            var relayed = false
            val tag = "${shortUuid(uuid)} <- ${device?.address}" +
                (if (preparedWrite) " PREPARED off=$offset" else "") + (if (!responseNeeded) " noResp" else "")
            if (uuid != null && value != null) {
                val out = if (GattUuids.carriesControl(uuid)) PowerRewrite.inverseTargetPower(value, correction()) else value
                // what the client asked for, unless the trainer's characteristic can't take a Write Command
                val withResponse = responseNeeded ||
                    (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0)
                FileLog.event("app write $tag = ${FileLog.hex(value)}" + if (!out.contentEquals(value)) " -> ${FileLog.hex(out)}" else "")
                // ponytail: a prepared (long) write is relayed fragment-by-fragment rather than buffered
                // until onExecuteWrite. No FTMS/CPS characteristic exceeds one ATT payload, so this only
                // matters if some app starts using long writes — the log line above says when it happens.
                if (!preparedWrite && uuid == GattUuids.FTMS_CONTROL_POINT && device != null && value.isNotEmpty()) {
                    val opcode = value[0].toInt() and 0xFF
                    when (val admission = ftmsControl.admit(device.address, opcode, out)) {
                        // No live identity: fail CLOSED. Minting one here from `clients` — which is cleared
                        // a few instructions after the coordinator on disconnect and on rebuild — would let
                        // a departing app be resurrected and promoted to owner, which is the exact hole the
                        // by-address admission was written to close.
                        null -> FileLog.event("ftms REJECTED op=0x%02X — no live connection <- %s"
                            .format(opcode, device.address))
                        is FtmsControlCoordinator.Admission.Rejected -> {
                            FileLog.event("ftms REJECTED op=0x%02X result=0x%02X <- %s"
                                .format(opcode, admission.result, device.address))
                            admission.client?.let {
                                notifyControlResult(it,
                                    byteArrayOf(0x80.toByte(), opcode.toByte(), admission.result.toByte()),
                                    terminal = false)
                            }
                        }
                        is FtmsControlCoordinator.Admission.Admitted -> {
                            val procedure = admission.procedure
                            FileLog.event("ftms ADMITTED op=0x%02X #%d <- %s#%d"
                                .format(opcode, procedure.id, device.address, procedure.client?.generation ?: 0L))
                            relayed = toZycle(uuid, out, withResponse) { success ->
                                // Transport success is NOT command success: FTMS puts acceptance in the
                                // Response Code, so nothing commits until onZycleValue sees it. The response
                                // clock starts HERE, once the machine actually has the write.
                                if (success) armProcedureDeadline(procedure)
                                else ftmsControl.transportFailed(procedure)?.let {
                                    cancelProcedureDeadline(procedure)
                                    it.client?.let { c ->
                                        notifyControlResult(c, byteArrayOf(
                                            0x80.toByte(), procedure.opcode.toByte(),
                                            FtmsControlCoordinator.OPERATION_FAILED.toByte(),
                                        ))
                                    }
                                }
                            }
                            if (!relayed) ftmsControl.transportFailed(procedure)?.let {
                                cancelProcedureDeadline(procedure)
                                it.client?.let { c ->
                                    notifyControlResult(c, byteArrayOf(
                                        0x80.toByte(), procedure.opcode.toByte(),
                                        FtmsControlCoordinator.OPERATION_FAILED.toByte()))
                                }
                            }
                        }
                    }
                } else relayed = toZycle(uuid, out, withResponse) { success ->
                    if (success && GattUuids.carriesControl(uuid)) {
                        lastControlWriteMs = SystemClock.elapsedRealtime()
                        servoStepOwed = true
                    }
                }
                if (!relayed) FileLog.event("app write $tag NOT RELAYED — answering failure")
            } else FileLog.event("app write $tag = <no value / unknown char>")
            // ATT response = "received", always. FTMS puts the OUTCOME in the control point indication.
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor?.uuid == cccd && device != null) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val cUuid = descriptor.characteristic?.uuid
                if (cUuid != null) {
                    val set = subscribers.computeIfAbsent(cUuid) { java.util.Collections.synchronizedSet(HashSet()) }
                    if (enabled) set.add(device.address) else set.remove(device.address)
                    FileLog.event("app subscribe ${shortUuid(cUuid)} enabled=$enabled raw=${FileLog.hex(value ?: ByteArray(0))} <- ${device.address}")
                }
            } else FileLog.event("app descriptor write ${shortUuid(descriptor?.uuid)} = ${FileLog.hex(value ?: ByteArray(0))} <- ${device?.address}")
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }

        /** Long-write commit. We relay fragments as they arrive (see onCharacteristicWriteRequest), so this
         *  only has to answer — but log it, because its presence means an app IS using long writes. */
        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            FileLog.event("app execute write execute=$execute <- ${device?.address}")
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }

        /** Notification flow control: a failure here means our notifications stopped reaching the app. */
        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) return
            FileLog.event("notify FAILED status=$status -> ${device?.address}")
            // notifyCharacteristicChanged() returning success only means the send was accepted for
            // dispatch; THIS is where Android reports whether it actually landed. If the send in flight
            // was a terminal Control Point result, its origin will wait forever for an answer it will
            // never see, while every other app is refused because it still holds control.
            val addr = device?.address ?: return
            val pendingTerminal = terminalIndication ?: return
            if (pendingTerminal.address != addr) return
            terminalIndication = null
            undeliverable(pendingTerminal, "onNotificationSent status=$status", terminal = true)
            cancelClient(pendingTerminal)
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            FileLog.event("app mtu=$mtu <- ${device?.address}")
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            // Report the client's current CCCD state so a read doesn't time out.
            val cUuid = descriptor?.characteristic?.uuid
            val on = device != null && cUuid != null && subscribers[cUuid]?.contains(device.address) == true
            val indicate = (descriptor?.characteristic?.properties ?: 0)
                .and(BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0   // null props must read as NOT indicate
            val v = if (!on) byteArrayOf(0x00, 0x00)
                    else if (indicate) byteArrayOf(0x02, 0x00) else byteArrayOf(0x01, 0x00)
            FileLog.event("app read descriptor ${shortUuid(descriptor?.uuid)} of ${shortUuid(cUuid)} = ${FileLog.hex(v)} <- ${device?.address}")
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v) }
        }
    }

    // ── advertising ──────────────────────────────────────────────────────────────────────────────────
    private fun newAdvAttempt(advertiser: BluetoothLeAdvertiser): Pair<AdvertiseCallback, Runnable> {
        lateinit var callback: AdvertiseCallback
        lateinit var watchdog: Runnable
        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                handler.removeCallbacks(watchdog)
                resolveAdvOrphan(this)   // our one callback arrived: resolved, current or not
                if (!advAttempts.runIfCurrent(this) {
                    advStarting = false; advertising = true; advRetries = 0; advRetryMs = ADV_RETRY_MS
                    onAdvState(true); onStatus(context.getString(R.string.status_advertising, advertisedName))
                    FileLog.event("advertising as $advertisedName")
                    if (!trainerLinked || server == null) {
                        FileLog.event("advertising with no trainer — stopping")
                        stopAdvertising()
                    }
                }) {
                    // Late success for a retired attempt: its registration is real, so stop it. Resolved
                    // above, so this stop can no longer be dropped by the stack.
                    runCatching { advertiser.stopAdvertising(this) }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                handler.removeCallbacks(watchdog)
                resolveAdvOrphan(this)   // no registration exists for a failed start, orphaned or not
                advAttempts.failIfCurrent(
                    this,
                    stop = { runCatching { advertiser.stopAdvertising(it) } },
                    markFailed = {
                        if (lastAdvCallback === this) lastAdvCallback = null
                        advStarting = false; advertising = false; onAdvState(false)
                        onStatus(context.getString(R.string.status_advertise_failed, errorCode))
                        FileLog.event("advertise failed $errorCode (attempt ${++advRetries}, name=${!dropNameFromAdv})")
                        if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) when {
                            !dropMfrFromAdv -> dropMfrFromAdv = true
                            !dropBlueprintFromAdv -> dropBlueprintFromAdv = true
                            else -> dropNameFromAdv = true
                        }
                    },
                    retry = { scheduleAdvRetry("failure $errorCode") },
                )
            }
        }
        watchdog = Runnable {
            advAttempts.retireIfCurrent(
                callback,
                // Unresolved by definition — that is what the watchdog fires on. One stop is not enough:
                // if the start is still in flight the stack drops it, so hand it to the orphan sweep.
                retire = { orphanAdvCallback(it) },
                after = {
                    advStarting = false; advertising = false; onAdvState(false)
                    FileLog.event("advertise start never answered — retiring attempt and retrying")
                    scheduleAdvRetry("start never answered")
                },
            )
        }
        return callback to watchdog
    }

    private fun startAdvertising() {
        // no trainer → stay off the air (see setTrainerLinked); not built → we'd advertise an empty GATT and
        // an app that connects in that window caches it. onServiceAdded calls back here once services land.
        // `stopped`/`server` too: a callback still in flight when stop() ran must not put us back on the air
        // with a closed GATT server behind the advert.
        if (stopped || server == null || advertising || advStarting || !trainerLinked || !servicesReady) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { onStatus(context.getString(R.string.status_ble_adv_unsupported)); onAdvState(false); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        // Clone the trainer's own advertising when we have it (same service UUIDs + manufacturer data), so a
        // head unit that filters on them sees us exactly like the real trainer. Fallback (sim / not yet
        // captured): the three standard cycling services.
        val builder = AdvertiseData.Builder().setIncludeDeviceName(!dropNameFromAdv)
        val bp = if (dropBlueprintFromAdv) null else advBlueprint
        if (bp != null && bp.serviceUuids.isNotEmpty()) {
            bp.serviceUuids.forEach { builder.addServiceUuid(it) }
            if (!dropMfrFromAdv) bp.manufacturerData.forEach { (id, d) -> runCatching { builder.addManufacturerData(id, d) } }
        } else {
            // only what we actually serve — advertising CSC 0x1816 made head units connect and find nothing
            builder.addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1818)))
                .addServiceUuid(ParcelUuid(GattUuids.uuid16(0x1826)))
        }
        advStarting = true
        // One callback is one controller registration. Retire any orphan before publishing the new owner.
        val (callback, watchdog) = newAdvAttempt(advertiser)
        // A previous owner still here means its start never resolved (a resolved one is retired by its own
        // callback), so it is an orphan, not a plain stop.
        advAttempts.begin(callback) { old -> orphanAdvCallback(old) }
        lastAdvCallback = callback   // process-wide, so a later instance can still stop this set
        lastAdvUnresolved = true     // ...and know whether that stop can be dropped by the stack
        if (runCatching { advertiser.startAdvertising(settings, builder.build(), callback) }.isFailure) {
            // A synchronous throw answers with no callback at all, so this is the only place that can report
            // it. Clearing the flag alone left health green and nothing scheduled.
            advAttempts.failIfCurrent(
                callback,
                // The start was never accepted, so there is no registration to chase: a plain stop, and
                // deliberately NOT an orphan — an entry nothing can ever resolve would be swept forever.
                stop = { runCatching { advertiser.stopAdvertising(it) } },
                markFailed = {
                    if (lastAdvCallback === callback) lastAdvCallback = null
                    advStarting = false; advertising = false; onAdvState(false)
                },
                retry = { scheduleAdvRetry("start threw") },
            )
        } else {
            // An accepted start normally answers with exactly one callback — except when the adapter is
            // turned off or the BT process dies under it, which drops the callback silently. Without this
            // the flag latches and we never advertise again.
            handler.postDelayed(watchdog, ADV_START_TIMEOUT_MS)
        }
    }

    /** Unconditional, for the same reason [stop] is: `advertising` is false between startAdvertising() and
     *  onStartSuccess, so an early return here can leave a pending advert running with no trainer behind it. */
    private fun stopAdvertising() {
        handler.removeCallbacks(startAdvRunnable)
        val wasStarting = advStarting
        // Clear waits for a callback already admitted by runIfCurrent; finalize flags only after it exits.
        val callback = advAttempts.clear()
        // advStarting IS cleared here now, unlike before. A stop issued while a start is in flight is dropped
        // by the stack, so that start is still coming and a second one can get through — but it now builds a
        // NEW callback, i.e. a separate registration, while this one is retired from the owner (its late
        // callback is rejected) and handed to the orphan sweep, which keeps stopping it until its own
        // callback arrives. So the extra registration is chased until it is provably gone, rather than
        // stopped once on a delay that no Android contract actually bounds.
        advertising = false; advStarting = false
        callback ?: return
        // Only an UNRESOLVED start needs the sweep: its stop can be dropped and nothing else holds the
        // handle. A resolved attempt is stopped once, reliably, right here.
        if (wasStarting) orphanAdvCallback(callback)
        else {
            if (lastAdvCallback === callback) lastAdvCallback = null
            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
    }

    /** Retire a callback whose start never resolved: stop it now, and keep stopping it until its own
     *  callback proves the registration is gone. [resolved] callbacks skip this — their result is known. */
    private fun orphanAdvCallback(cb: AdvertiseCallback) {
        advOrphans.add(cb)
        if (lastAdvCallback === cb) lastAdvCallback = null
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        advSweepMs = ADV_SWEEP_MS
        orphanHandler.removeCallbacks(advSweep); orphanHandler.postDelayed(advSweep, advSweepMs)
    }

    /** An orphan's own callback finally arrived: that is the only proof the registration resolved. Also
     *  the one place that can mark the process-wide handle resolved, for a later instance's handoff. */
    private fun resolveAdvOrphan(cb: AdvertiseCallback) {
        advOrphans.remove(cb)
        if (lastAdvCallback === cb) lastAdvUnresolved = false
    }

    private var advSweepMs = ADV_SWEEP_MS
    private val advSweep = object : Runnable {
        override fun run() {
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {   // adapter off: every prior registration died with it
                if (advOrphans.isNotEmpty()) FileLog.event("adapter off — dropping ${advOrphans.size} orphan advertising set(s)")
                advOrphans.clear(); advSweepMs = ADV_SWEEP_MS
                return
            }
            val current = advAttempts.current
            // Snapshot under the lock, call the advertiser outside it. NEVER the current attempt.
            val targets = synchronized(advOrphans) { advOrphans.toList() }.filter { it !== current }
            for (cb in targets) runCatching { advertiser.stopAdvertising(cb) }
            if (advOrphans.isEmpty()) { advSweepMs = ADV_SWEEP_MS; return }
            advSweepMs = (advSweepMs * 2).coerceAtMost(ADV_SWEEP_MAX_MS)
            orphanHandler.postDelayed(this, advSweepMs)
        }
    }

    /** Force a fresh advertise (Android silently stopped it when a central connected, but our flag didn't
     *  know). Needed so more than one app can find us. */
    private val startAdvRunnable = Runnable { startAdvertising() }
    /**
     * The one place that re-arms advertising. A capped ATTEMPT COUNT was the bug this replaces: five
     * transient failures took the mirror off the air for the whole ride. A flat 1 s retry is the opposite
     * mistake — a revoked BLUETOOTH_ADVERTISE or a stack that throws every time would retry ~3600 times an
     * hour, each one a log line, across a 4-12 h ride. So: backoff, no cap. The budget resets on a real
     * success and when the trainer link comes back.
     *
     * Each attempt owns a distinct callback. Its watchdog retires that registration before scheduling the
     * retry, so callbacks from an old attempt cannot cancel or mutate the current one.
     */
    private fun scheduleAdvRetry(why: String) {
        if (stopped) return
        val wait = advRetryMs
        advRetryMs = (wait * 2).coerceAtMost(ADV_RETRY_MAX_MS)
        FileLog.event("advertise retry in ${wait}ms: $why")
        handler.removeCallbacks(startAdvRunnable); handler.postDelayed(startAdvRunnable, wait)
    }

    private fun restartAdvertising() {
        // an app connecting/disconnecting is a fresh chance, not a continuation of old failures
        advRetries = 0; advRetryMs = ADV_RETRY_MS
        stopAdvertising()
        // The stop is async and only frees this callback when it completes; starting in the same turn
        // answers ADVERTISE_FAILED_ALREADY_STARTED. Give it a turn — and collapse duplicate restarts
        // (connect + disconnect in quick succession) into a single pending start.
        handler.removeCallbacks(startAdvRunnable)
        handler.postDelayed(startAdvRunnable, ADV_RESTART_MS)
    }

    private fun logRelay(uuid: UUID, inV: ByteArray, outV: ByteArray, nClients: Int) {
        if (!FileLog.enabled) return   // don't build hex strings on the BLE thread when logging is off
        val corr = if (!outV.contentEquals(inV)) " -> ${FileLog.hex(outV)}" else ""
        FileLog.event("relay ${shortUuid(uuid)} = ${FileLog.hex(inV)}$corr to $nClients")
    }

    private fun shortUuid(u: UUID?): String {
        val s = u?.toString() ?: return "?"
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
    }

    private companion object {
        /**
         * Callbacks retired while their start was STILL UNRESOLVED — and only those. The callback IS the
         * controller registration, and a stop issued while its start is in flight is dropped by the stack,
         * so such an attempt can stay registered with nothing holding its handle: an advertiser slot burnt,
         * or an obsolete mirror on the air, until Bluetooth restarts. Membership ends only when that
         * attempt's OWN callback is finally delivered, because nothing else proves the registration is gone.
         *
         * Deliberately NOT every started attempt: a sweep must never stop the live one. An attempt whose
         * result is already known (onStartFailure, a synchronous throw — the start was never accepted) is
         * not an orphan either.
         *
         * Process-wide: an orphan has to outlive the MirrorServer that created it, which is the whole point.
         *
         * ponytail: stopAdvertising() does not confirm removal and a start can stay in flight past
         * ADV_START_TIMEOUT_MS, so NO finite delay can guarantee the retirement lands. The sweep backs off
         * to ADV_SWEEP_MAX_MS and keeps trying while the set is non-empty; a null advertiser (adapter off)
         * is the one piece of evidence that every prior registration is definitively gone.
         */
        private val advOrphans: MutableSet<android.bluetooth.le.AdvertiseCallback> =
            java.util.Collections.synchronizedSet(mutableSetOf())
        /** NOT the instance handler: stop() drains that one, and an orphan must outlive its mirror. */
        private val orphanHandler by lazy { Handler(Looper.getMainLooper()) }
        @Volatile private var lastAdvCallback: android.bluetooth.le.AdvertiseCallback? = null
        /** Whether [lastAdvCallback]'s start is still UNRESOLVED. A bare reference cannot say: a callback
         *  that already got its onStartSuccess is registered but RESOLVED, so the next instance must stop it
         *  once — never adopt it as an orphan, which nothing could ever resolve and the sweep would chase
         *  every 30 s for the life of the process. */
        @Volatile private var lastAdvUnresolved = false
        const val KEY_ADV_NAMES = "advertisedNamesUsed"
        const val KEY_ORIG_NAME = "origBtName"
    }
}
