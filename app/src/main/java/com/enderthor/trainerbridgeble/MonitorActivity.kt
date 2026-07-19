package com.enderthor.trainerbridgeble

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Monitor: live power / corrected / speed / cadence + resistance and app control, Start/Stop, emulated
 *  resistance buttons (test mode), and a link to Configuración. */
class MonitorActivity : Activity() {
    private lateinit var config: Config
    private lateinit var banner: TextView
    private lateinit var alertLine: TextView
    private lateinit var statusLine: TextView
    private lateinit var controlTile: TextView
    private lateinit var powerTile: TextView
    private lateinit var correctedTile: TextView
    private lateinit var speedTile: TextView
    private lateinit var cadenceTile: TextView
    private lateinit var resistTile: TextView
    private lateinit var masterSwitch: android.widget.Switch
    private lateinit var startBtn: Button
    private lateinit var upBtn: Button
    private lateinit var downBtn: Button

    private var service: BridgeService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? BridgeService.LocalBinder)?.service
            service?.listener = { runOnUiThread { render() } }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAGE_BG); setPadding(dp(16), dp(6), dp(16), dp(16))
        }
        // Master switch (created here, added near the bottom above Configuration to keep the 6 tiles visible).
        masterSwitch = switchRow(getString(R.string.monitor_master), config.masterEnabled).apply {
            setOnCheckedChangeListener { _, isChecked -> config.masterEnabled = isChecked; BridgeService.setMaster(this@MonitorActivity, isChecked); render() }
        }
        startBtn = accentButton(getString(R.string.monitor_emit_start)) { onStartStop() }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, dp(10))   // gap above and before the status card
        }
        body.addView(startBtn)

        val mon = card("")   // no title — the data sits at the top of the card
        mon.setPadding(dp(18), dp(8), dp(18), dp(16))   // less top padding so the status banner sits up top
        banner = TextView(this).apply {
            text = getString(R.string.monitor_banner_stopped); setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER; background = rounded(Palette.MUTED); setPadding(dp(12), dp(12), dp(12), dp(12))
            maxLines = 1   // never wrap — autosize shrinks a long alert to fit one line, so it stays aligned
            setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, 0, 0, dp(8)); layoutParams = lp
        }
        mon.addView(banner)
        alertLine = bodyText("", 13f, Palette.DANGER).apply { visibility = TextView.GONE }; mon.addView(alertLine)
        statusLine = bodyText(getString(R.string.status_stopped), 14f); mon.addView(statusLine)
        val row1 = tileRow(); mon.addView(row1)
        powerTile = tile(row1, getString(R.string.monitor_tile_power), Palette.TEXT)
        correctedTile = tile(row1, getString(R.string.monitor_tile_corrected), Palette.ACCENT)
        val row2 = tileRow(); mon.addView(row2)
        speedTile = tile(row2, getString(R.string.monitor_tile_speed), Palette.TEXT)
        cadenceTile = tile(row2, getString(R.string.monitor_tile_cadence), Palette.TEXT)
        val row3 = tileRow(); mon.addView(row3)
        resistTile = tile(row3, "", Palette.OK)   // label lives in the caption above the ▼▲ arrows
        controlTile = tile(row3, getString(R.string.monitor_tile_control), Palette.MUTED).apply {   // fills the box right of resistance
            maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }

        mon.addView(bodyText(getString(R.string.monitor_tile_resistance), 12f, Palette.MUTED).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(2), 0, 0)   // caption above the ▼▲ arrows
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downBtn = tileButton(getString(R.string.monitor_resistance_down)) { service?.buttonDown() }
        upBtn = tileButton(getString(R.string.monitor_resistance_up)) { service?.buttonUp() }
        btnRow.addView(downBtn); btnRow.addView(upBtn); mon.addView(btnRow)
        body.addView(mon)

        body.addView(masterSwitch.tag as android.view.View)
        body.addView(plainButton(getString(R.string.config_title)) { startActivity(Intent(this, ConfigActivity::class.java)) }
            .apply { background = rounded(Palette.CARD_BG) })   // white so it stands out from the page bg

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.PAGE_BG); addView(body) })
        ensurePermissions()
        requestBatteryExemption()
    }

    /** Doze kills the pipeline a few minutes after the screen turns off unless we're whitelisted. Ask once. */
    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("trainerbridgeble", Context.MODE_PRIVATE)
        if (prefs.getBoolean("batteryAsked", false)) return   // ask once, not on every recreate/rotation
        prefs.edit().putBoolean("batteryAsked", true).apply()
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    private val poller = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollTick = object : Runnable { override fun run() { render(); poller.postDelayed(this, 1000) } }

    override fun onStart() {
        super.onStart()
        // Re-assert the master state: a cold open (bind-only, no START_STICKY restart) would otherwise leave
        // the service bound but not foregrounded/receiving even though master is persisted on. Idempotent.
        if (config.masterEnabled) BridgeService.setMaster(this, true)
        bindService(Intent(this, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE)
        poller.post(pollTick)   // 1 Hz refresh so tiles go stale ("—") even when the trainer stops notifying
    }
    override fun onStop() { super.onStop(); poller.removeCallbacks(pollTick); service?.listener = null; runCatching { unbindService(conn) } }

    private fun onStartStop() {   // the button now toggles the EMIT (broadcast) half
        if (service?.emitting == true) BridgeService.stopEmit(this) else BridgeService.startEmit(this)
        render()
    }

    private fun render() {
        val s = service
        val master = config.masterEnabled
        val emitting = s?.emitting == true
        // Broadcast button: label off emit state, enabled only while the master switch is on.
        startBtn.text = if (emitting) getString(R.string.monitor_emit_stop) else getString(R.string.monitor_emit_start)
        val canEmit = master && s?.receiving == true   // broadcasting an un-fed mirror is pointless
        startBtn.isEnabled = canEmit; startBtn.alpha = if (canEmit) 1f else 0.4f
        // Fresh = a sample arrived within the last 3s. A brief (<3s) blip keeps showing the last value
        // (the mirror is re-emitting it too); a longer gap blanks the tiles to "—".
        val fresh = s != null && s.lastSampleMs != 0L && System.currentTimeMillis() - s.lastSampleMs <= STALE_MS
        // Banner shows the TRAINER (receive) link when master is on; "Off" when master is off.
        val (bText, bColor) = when {
            !master -> getString(R.string.monitor_off) to Palette.MUTED
            s?.zycleConnected != true -> getString(R.string.monitor_banner_searching) to Palette.ACCENT
            !fresh -> getString(R.string.monitor_banner_no_data) to Palette.DANGER
            else -> getString(R.string.monitor_banner_connected) to Palette.OK
        }
        banner.text = bText + (if (master && s?.isSimulating == true) "   ·   SIM" else "")
        banner.background = rounded(bColor)
        // ANT/BLE problems go on their OWN line, so they don't mask the trainer state.
        val alert = s?.alert   // getter already returns null unless emitting
        if (alert != null) { alertLine.visibility = TextView.VISIBLE; alertLine.text = getString(R.string.alert_prefix, alert) }
        else alertLine.visibility = TextView.GONE
        statusLine.text = if (master) s?.status ?: getString(R.string.status_stopped) else getString(R.string.status_stopped)
        statusLine.setTextColor(if (master && s?.zycleConnected == true) Palette.OK else Palette.MUTED)
        val show = master && fresh
        powerTile.text = if (show) s?.lastRawW?.toString() ?: "—" else "—"
        correctedTile.text = if (show) s?.lastCorrectedW?.toString() ?: "—" else "—"
        speedTile.text = if (show) s?.lastSpeedKmh?.let { String.format("%.1f", it) } ?: "—" else "—"
        cadenceTile.text = if (show) s?.lastCadence?.toString() ?: "—" else "—"
        resistTile.text = if (master) s?.resistance?.toString() ?: "—" else "—"
        controlTile.text = if (master) s?.lastControl ?: "—" else "—"
        val nudgeable = master && s?.receiving == true
        upBtn.isEnabled = nudgeable; downBtn.isEnabled = nudgeable
        upBtn.alpha = if (nudgeable) 1f else 0.4f; downBtn.alpha = if (nudgeable) 1f else 0.4f
    }

    private fun tileButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Palette.ACCENT); background = rounded(Palette.PAGE_BG); textSize = 18f; setPadding(dp(10), dp(8), dp(10), dp(8))
        val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.setMargins(dp(3), dp(8), dp(3), 0); layoutParams = lp
        setOnClickListener { onClick() }
    }

    private companion object { const val STALE_MS = 3000L }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
