package com.enderthor.trainerbridgeble.ant

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.Capabilities
import com.dsi.ant.channel.ChannelNotAvailableException
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.enderthor.trainerbridgeble.FileLog
import com.dsi.ant.message.EventCode
import com.dsi.ant.message.fromant.ChannelEventMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Robust wrapper around ONE raw ANT channel (via the ANT Radio Service). Binds the service, acquires
 * a channel, lets the caller configure it (assign + channel id / RF / period), opens it, and RECOVERS
 * from the three ways an ANT channel stops delivering: channel death, RX search timeout (the slave's
 * search window expiring when the master goes silent — arrives as a CHANNEL_EVENT, NOT onChannelDeath),
 * and ANT-service disconnect. Used for both the FE-C RX slave and the FE-C TX master.
 *
 * Concurrency guards carried over from KPower's RawAntChannel (load-bearing, not tuning):
 *  - an [opening] CAS so two reopen paths can't both acquire a channel and orphan one;
 *  - idempotent [releaseChannel] (a per-handle released-set) so a stop()/configure() race can't
 *    double-close/unassign/release the same handle and corrupt the provider's channel accounting;
 *  - open() failure releases the LOCAL handle and only nulls [channel] if it still points at it, so it
 *    can never tear down a newer, healthy channel.
 *
 * @param configure applies assign()+setChannelId()+setRfFrequency()+setPeriod() (+ setSearchTimeout
 *        for a slave). Runs on an IO coroutine; may call the channel's blocking IPC methods.
 * @param onMessage BROADCAST_DATA (RX payload) or a CHANNEL_EVENT other than RX_SEARCH_TIMEOUT (e.g.
 *        the master's per-period EventCode.TX). RX_SEARCH_TIMEOUT is consumed here for recovery.
 * @param onOpened fires right after open() succeeds (e.g. the master seeds its first broadcast page).
 */
class RawAntLink(
    private val context: Context,
    private val tag: String,
    private val configure: (AntChannel) -> Unit,
    private val onMessage: (AntChannel, MessageFromAntType, AntMessageParcel) -> Unit,
    private val onOpened: (AntChannel) -> Unit = {},
    /** Extra channel capabilities to require at acquire time — e.g. background scanning for the scanner.
     *  The RX/TX channels leave it null (requiring it would fail acquire on adapters that lack it). */
    private val capabilities: Capabilities? = null,
    /** Health report to the UI: ok=true once a channel is open+transmitting; ok=false with a reason when
     *  the link can't come up (no ANT Radio Service, no dongle/channel, service down). */
    private val onState: (ok: Boolean, detail: String) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var antService: AntService? = null
    @Volatile private var channel: AntChannel? = null
    @Volatile private var stopped = false

    /** The ANT Radio Service broadcasts this when its free-channel count changes. When a channel frees up
     *  and we have none, retry open() at once — the correct way to wait out a busy pool on the Karoo
     *  (whose ANT channels are shared with its own sensors), instead of rebinding the shared service. */
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED) return
            val n = intent.getIntExtra(AntChannelProvider.NUM_CHANNELS_AVAILABLE, 0)
            if (n > 0 && !stopped && channel == null && !opening.get()) scope.launch { open() }
        }
    }
    @Volatile private var providerRegistered = false

    /** The current open channel, or null if none (mid-reopen / not started). Read-only, for a
     *  bidirectional slave to send acknowledged control data from its own thread; tolerates null. */
    val currentChannel: AntChannel? get() = channel

    /** Wall-clock of the last message from the current channel; 0 when none since (re)open. Drives the
     *  heartbeat watchdog: a SLAVE that acquires a master then goes silent while still "tracking" emits
     *  neither RX_SEARCH_TIMEOUT nor onChannelDeath, so nothing else would recover it. Masters get a TX
     *  event every period, so their heartbeat never expires. */
    @Volatile private var lastMessageMs = 0L

    /** At most one open() in flight — a death-retry must not race a rebind and orphan a channel. */
    private val opening = AtomicBoolean(false)

    /** Idempotent-teardown bookkeeping: a handle is close/unassign/release'd at most once. */
    private val teardownLock = Any()
    private val released = HashSet<AntChannel>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            antService = AntService(binder)
            if (!stopped) scope.launch { open() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            antService = null
            val dead = channel; channel = null; releaseChannel(dead)
            onState(false, "servicio ANT desconectado")
            if (!stopped) scheduleReopen("ant service disconnected")
        }
    }

    fun start() {
        startHeartbeat()
        runCatching {
            ContextCompat.registerReceiver(context, providerReceiver,
                IntentFilter(AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            providerRegistered = true
        }
        val ok = runCatching { AntService.bindService(context, conn) }.getOrDefault(false)
        if (!ok) { onState(false, "no se pudo enlazar el ANT Radio Service"); scheduleReopen("bindService returned false") }
    }

    /** Recycle a channel that is nominally open but has gone silent (tracking-but-no-data firmware
     *  quirk that fires no timeout/death). Masters refresh lastMessageMs every TX period, so this only
     *  ever recycles a stuck slave. */
    private fun startHeartbeat() {
        scope.launch {
            while (!stopped) {
                delay(HEARTBEAT_CHECK_MS)
                val last = lastMessageMs
                if (!stopped && channel != null && !opening.get() && last != 0L &&
                    System.currentTimeMillis() - last > HEARTBEAT_TIMEOUT_MS
                ) {
                    Log.i(tag, "silent ${System.currentTimeMillis() - last}ms — recycling")
                    lastMessageMs = 0L
                    val dead = channel; channel = null; releaseChannel(dead)
                    scheduleReopen("heartbeat")
                }
            }
        }
    }

    private suspend fun open() {
        if (stopped) return
        if (!opening.compareAndSet(false, true)) return
        try {
            // Already have a healthy channel — never acquire a duplicate. The pre-launch channel==null
            // check in the callers (providerReceiver, backstop) is stale by the time this coroutine runs;
            // two rapid provider broadcasts could otherwise both pass it and orphan the first channel.
            if (channel != null) return
            runCatching {
                val provider: AntChannelProvider = antService?.channelProvider
                    ?: throw IllegalStateException("ANT channelProvider not ready")
                val ch = if (capabilities != null)
                    provider.acquireChannel(context, PredefinedNetwork.ANT_PLUS, capabilities)
                else provider.acquireChannel(context, PredefinedNetwork.ANT_PLUS)
                if (stopped) { releaseChannel(ch); return }
                channel = ch
                lastMessageMs = 0L   // fresh channel: heartbeat can't recycle it before it (re)acquires
                ch.setChannelEventHandler(object : IAntChannelEventHandler {
                    override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                        if (msg == null || type == null || ch !== channel) return
                        lastMessageMs = System.currentTimeMillis()
                        // The slave's search window expiring closes the channel as a CHANNEL_EVENT
                        // (never onChannelDeath). Consume it here and reopen, else the channel is dead
                        // for the rest of the session. Any other CHANNEL_EVENT (e.g. master TX) and all
                        // BROADCAST_DATA go to the caller.
                        if (type == MessageFromAntType.CHANNEL_EVENT) {
                            val ev = runCatching { ChannelEventMessage(msg).eventCode }.getOrNull()
                            if (ev == EventCode.RX_SEARCH_TIMEOUT) {
                                // Release OFF the ANT callback thread — a blocking close/unassign/release
                                // called from within the event handler can fail to free the channel (→ leak
                                // that only a replug clears). Reopen only after the release has completed.
                                if (ch === channel) { channel = null; scope.launch { releaseChannel(ch); scheduleReopen("rx search timeout") } }
                                return
                            }
                        }
                        runCatching { onMessage(ch, type, msg) }
                            .onFailure { Log.w(tag, "onMessage failed: ${it.message}") }
                    }
                    override fun onChannelDeath() {
                        if (ch === channel) { channel = null; scope.launch { releaseChannel(ch); scheduleReopen("channel death") } }
                    }
                })
                configure(ch)
                if (stopped) { if (channel === ch) channel = null; releaseChannel(ch); return }
                ch.open()
                Log.d(tag, "channel open")
                FileLog.event("$tag opened")   // diagnostic: which channels actually acquire+open
                onState(true, "transmitiendo")
                onOpened(ch)
            }.onFailure { e ->
                // Release the LOCAL half-open handle and only null the field if it still points here
                // (a concurrent reopen can't run — the opening CAS holds — so channel IS this attempt's).
                val dead = channel; channel = null; releaseChannel(dead)
                when (e) {
                    is ChannelNotAvailableException -> {
                        // All ANT channels busy (on the Karoo: its own paired sensors + others). Do NOT
                        // rebind the ANT service — it's a shared SYSTEM service there, and unbinding it just
                        // wedges ("rebind connect timeout") and makes it worse. Wait for a channel to free:
                        // providerReceiver retries the instant one does, plus a long backstop here. (KPower
                        // model — the only thing that behaves on the Karoo.)
                        Log.w(tag, "no ANT channel available; awaiting a free channel")
                        onState(false, "sin canal ANT libre")
                        scope.launch { delay(NO_CHANNEL_RETRY_MS); if (!stopped && channel == null && !opening.get()) open() }
                    }
                    else -> { Log.e(tag, "open failed: ${e.message}"); onState(false, e.message ?: "fallo ANT"); scheduleReopen(e.message ?: "open failure") }
                }
            }
        } finally {
            opening.set(false)
        }
    }

    private fun scheduleReopen(reason: String) {
        if (stopped) return
        Log.w(tag, "reopen scheduled: $reason")
        FileLog.event("$tag reopen: $reason")
        scope.launch {
            delay(REOPEN_DELAY_MS)
            if (stopped) return@launch
            if (antService == null) {
                runCatching { context.unbindService(conn) }
                val ok = runCatching { AntService.bindService(context, conn) }.getOrDefault(false)
                if (!ok) scheduleReopen("rebind failed")
            } else if (channel == null) {
                open()
            }
        }
    }

    /** Idempotent: close/unassign/release [ch] at most once, ever (a stop()/configure() race would
     *  otherwise double-release the same handle and corrupt the provider's free-channel accounting). */
    private fun releaseChannel(ch: AntChannel?) {
        if (ch == null) return
        synchronized(teardownLock) {
            if (!released.add(ch)) return
            if (released.size > 32) { released.clear(); released.add(ch) }
        }
        // Clear the event handler before release() — matches KPower's proven Karoo teardown order. Leaving
        // it set makes the ANT service keep a reference and NOT reclaim the channel, so the next session
        // gets "no channel available" (the Karoo leak we saw). Harmless on a phone/dongle.
        runCatching { ch.close() }
        runCatching { ch.unassign() }
        runCatching { ch.clearChannelEventHandler() }
        runCatching { ch.release() }
    }

    fun stop() {
        stopped = true
        if (providerRegistered) { runCatching { context.unregisterReceiver(providerReceiver) }; providerRegistered = false }
        scope.launch {
            val ch = channel; channel = null
            releaseChannel(ch)
            runCatching { context.unbindService(conn) }
        }.invokeOnCompletion { scope.cancel() }
    }

    private companion object {
        const val REOPEN_DELAY_MS = 2000L
        const val HEARTBEAT_CHECK_MS = 7_000L
        const val HEARTBEAT_TIMEOUT_MS = 15_000L // recycle a tracking-but-silent channel this long (RF loss);
                                                 // lower than before so a lost trainer recovers in ~15s not ~30s
        const val NO_CHANNEL_RETRY_MS = 15_000L  // backstop retry when all ANT channels are busy (providerReceiver retries sooner)
    }
}
