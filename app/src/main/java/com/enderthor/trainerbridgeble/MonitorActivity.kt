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
    private lateinit var statusLine: TextView
    private lateinit var controlLine: TextView
    private lateinit var powerTile: TextView
    private lateinit var correctedTile: TextView
    private lateinit var speedTile: TextView
    private lateinit var cadenceTile: TextView
    private lateinit var resistTile: TextView
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
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAGE_BG); setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        body.addView(title("TrainerBridge BLE"))

        val mon = card("Estado")
        statusLine = bodyText("parado", 14f); mon.addView(statusLine)
        val row1 = tileRow(); mon.addView(row1)
        powerTile = tile(row1, "Potencia (W)", Palette.TEXT)
        correctedTile = tile(row1, "Corregida (W)", Palette.ACCENT)
        val row2 = tileRow(); mon.addView(row2)
        speedTile = tile(row2, "Velocidad (km/h)", Palette.TEXT)
        cadenceTile = tile(row2, "Cadencia (rpm)", Palette.TEXT)
        val row3 = tileRow(); mon.addView(row3)
        resistTile = tile(row3, "Resistencia (%)", Palette.OK)
        tile(row3, "", Palette.MUTED).apply { visibility = TextView.INVISIBLE }   // spacer to keep width
        controlLine = bodyText("App → trainer: —", 14f); mon.addView(controlLine)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downBtn = tileButton("Resistencia ▼") { service?.buttonDown() }
        upBtn = tileButton("Resistencia ▲") { service?.buttonUp() }
        btnRow.addView(downBtn); btnRow.addView(upBtn); mon.addView(btnRow)
        startBtn = accentButton("Start") { onStartStop() }; mon.addView(startBtn)
        body.addView(mon)

        body.addView(plainButton("Configuración") { startActivity(Intent(this, ConfigActivity::class.java)) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.PAGE_BG); addView(body) })
        ensurePermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() { super.onStop(); service?.listener = null; runCatching { unbindService(conn) } }

    private fun onStartStop() {
        if (service?.isRunning == true) BridgeService.stop(this) else BridgeService.start(this)
        render()
    }

    private fun render() {
        val s = service
        val running = s?.isRunning == true
        startBtn.text = if (running) "Stop" else "Start"
        statusLine.text = s?.status ?: "parado"
        statusLine.setTextColor(if (s?.zycleConnected == true) Palette.OK else Palette.MUTED)
        powerTile.text = s?.lastRawW?.toString() ?: "—"
        correctedTile.text = s?.lastCorrectedW?.toString() ?: "—"
        speedTile.text = s?.lastSpeedKmh?.let { String.format("%.1f", it) } ?: "—"
        cadenceTile.text = s?.lastCadence?.toString() ?: "—"
        resistTile.text = s?.resistance?.toString() ?: "—"
        controlLine.text = "App → trainer: " + (s?.lastControl ?: "—")
        val simRunning = running && s?.isSimulating == true
        upBtn.isEnabled = simRunning; downBtn.isEnabled = simRunning
        upBtn.alpha = if (simRunning) 1f else 0.4f; downBtn.alpha = if (simRunning) 1f else 0.4f
    }

    private fun tileButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(Palette.ACCENT); background = rounded(Palette.PAGE_BG); setPadding(dp(10), dp(10), dp(10), dp(10))
        val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.setMargins(dp(3), dp(8), dp(3), 0); layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
