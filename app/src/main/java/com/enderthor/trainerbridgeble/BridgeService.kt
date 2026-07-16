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
    private var mirror: MirrorServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile var status: String = "parado"; private set
    @Volatile var zycleConnected: Boolean = false; private set
    @Volatile var lastRawW: Int? = null; private set
    @Volatile var lastCorrectedW: Int? = null; private set
    @Volatile var listener: (() -> Unit)? = null
    val isRunning: Boolean get() = mirror != null

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
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        acquireWakeLock()
        FileLog.init(this); FileLog.enabled = config.loggingEnabled
        FileLog.event("bridge start scaleAdj=${config.scaleAdjustPercent}% offset=${config.offsetW}W prefix=${config.namePrefix} sim=${config.simulate}")

        val m = MirrorServer(
            context = this,
            correction = { config.correction() },
            toZycle = { uuid, bytes, withResponse -> client?.write(uuid, bytes, withResponse) },
            onStatus = { s -> status = s; listener?.invoke() },
        )
        val onProfile: (com.enderthor.trainerbridgeble.ble.GattProfile) -> Unit = { profile -> m.build(profile) }
        val onValue: (java.util.UUID, ByteArray) -> Unit = { uuid, value -> m.onZycleValue(uuid, value); cachePowerForUi(config, uuid, value) }
        val onState: (Boolean) -> Unit = { connected -> zycleConnected = connected; status = if (connected) "trainer conectado" else "buscando trainer…"; listener?.invoke() }
        val c: TrainerSource = if (config.simulate) SimSource(onProfile, onValue, onState)
        else ZycleClient(this, config.namePrefix, onProfile, onValue, onState)
        m.start()
        c.start()
        mirror = m; client = c
    }

    private fun cachePowerForUi(config: Config, uuid: java.util.UUID, value: ByteArray) {
        if (uuid != com.enderthor.trainerbridgeble.ble.GattUuids.CYCLING_POWER_MEASUREMENT) return
        if (value.size < 4) return
        val raw = ((value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)).toShort().toInt()
        lastRawW = raw; lastCorrectedW = config.correction().correct(raw); listener?.invoke()
    }

    private fun stopPipeline() {
        if (mirror != null) FileLog.event("bridge stop")
        client?.stop(); client = null
        mirror?.stop(); mirror = null
        zycleConnected = false; lastRawW = null; lastCorrectedW = null; status = "parado"
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
