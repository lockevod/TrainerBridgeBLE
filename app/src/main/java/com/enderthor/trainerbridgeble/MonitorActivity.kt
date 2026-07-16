package com.enderthor.trainerbridgeble

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Card-based monitor + config UI (same look as the ANT app): live power/resistance/control, correction
 *  fields, log + simulation toggles, and emulated resistance buttons in test mode. */
class MonitorActivity : Activity() {
    private lateinit var config: Config
    private lateinit var statusLine: TextView
    private lateinit var powerTile: TextView
    private lateinit var resistTile: TextView
    private lateinit var controlLine: TextView
    private lateinit var startBtn: Button
    private lateinit var upBtn: Button
    private lateinit var downBtn: Button
    private lateinit var scaleField: EditText
    private lateinit var offsetField: EditText
    private lateinit var logCheck: CheckBox
    private lateinit var simCheck: CheckBox

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
            orientation = LinearLayout.VERTICAL; setBackgroundColor(PAGE_BG); setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        body.addView(TextView(this).apply {
            text = "TrainerBridge BLE"; textSize = 22f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, 0, dp(12))
        })

        // ── Monitor card ──
        val mon = card("Estado")
        statusLine = line(mon, "parado")
        val tiles = tileRow(); mon.addView(tiles)
        powerTile = tile(tiles, "Potencia", ACCENT)
        resistTile = tile(tiles, "Resistencia", OK)
        controlLine = line(mon, "App → trainer: —")
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downBtn = plainButton("Resistencia ▼") { service?.buttonDown() }
        upBtn = plainButton("Resistencia ▲") { service?.buttonUp() }
        (downBtn.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; rightMargin = dp(6) }
        (upBtn.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f }
        btnRow.addView(downBtn); btnRow.addView(upBtn); mon.addView(btnRow)
        startBtn = accentButton("Start") { onStartStop() }; mon.addView(startBtn)
        body.addView(mon)

        // ── Config card ──
        val cfg = card("Corrección")
        cfg.addView(fieldLabel("Ajuste de escala (%, entero)"))
        scaleField = numberField(config.scaleAdjustPercent.toString()); cfg.addView(scaleField)
        cfg.addView(fieldLabel("Offset (W, entero)"))
        offsetField = numberField(config.offsetW.toString()); cfg.addView(offsetField)
        logCheck = check("Guardar log (CSV)", config.loggingEnabled); cfg.addView(logCheck)
        simCheck = check("Modo simulación (sin bici)", config.simulate); cfg.addView(simCheck)
        body.addView(cfg)

        setContentView(ScrollView(this).apply { setBackgroundColor(PAGE_BG); addView(body) })
        ensurePermissions()
    }

    override fun onStart() { super.onStart(); bindService(Intent(this, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE) }
    override fun onStop() { super.onStop(); service?.listener = null; runCatching { unbindService(conn) } }

    private fun onStartStop() {
        saveCorrection()
        if (service?.isRunning == true) BridgeService.stop(this) else BridgeService.start(this)
        render()
    }

    private fun saveCorrection() {
        scaleField.text.toString().toIntOrNull()?.let { if (it > -100) config.scaleAdjustPercent = it }
        offsetField.text.toString().toIntOrNull()?.let { config.offsetW = it }
        config.loggingEnabled = logCheck.isChecked
        config.simulate = simCheck.isChecked
    }

    private fun render() {
        val s = service
        val running = s?.isRunning == true
        startBtn.text = if (running) "Stop" else "Start"
        statusLine.text = s?.status ?: "parado"
        statusLine.setTextColor(if (s?.zycleConnected == true) OK else MUTED)
        powerTile.text = s?.let { if (it.lastCorrectedW != null) "${it.lastRawW}→${it.lastCorrectedW} W" else "—" } ?: "—"
        resistTile.text = s?.resistance?.let { "$it%" } ?: "—"
        controlLine.text = "App → trainer: " + (s?.lastControl ?: "—")
        val simRunning = running && s?.isSimulating == true
        upBtn.isEnabled = simRunning; downBtn.isEnabled = simRunning
        upBtn.alpha = if (simRunning) 1f else 0.4f; downBtn.alpha = if (simRunning) 1f else 0.4f
    }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ── style helpers (mirrors the ANT app) ──
    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(CARD_BG); setPadding(dp(18), dp(14), dp(18), dp(16))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, 0, 0, dp(14)); layoutParams = lp
        addView(TextView(this@MonitorActivity).apply {
            text = title; textSize = 16f; setTextColor(ACCENT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(8))
        })
    }

    private fun line(parent: LinearLayout, text: String): TextView {
        val tv = TextView(this).apply { this.text = text; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(3), 0, dp(3)) }
        parent.addView(tv); return tv
    }

    private fun tileRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8)) }

    private fun tile(row: LinearLayout, label: String, valueColor: Int): TextView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(PAGE_BG); setPadding(dp(6), dp(12), dp(6), dp(12)); gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.setMargins(dp(3), 0, dp(3), 0); layoutParams = lp
        }
        val value = TextView(this).apply { text = "—"; textSize = 24f; setTextColor(valueColor); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        col.addView(value)
        col.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER })
        row.addView(col); return value
    }

    private fun fieldLabel(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(6), 0, dp(2)) }
    private fun numberField(v: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(v); setTextColor(TEXT)
    }
    private fun check(t: String, on: Boolean) = CheckBox(this).apply { text = t; isChecked = on; setTextColor(TEXT); setPadding(0, dp(6), 0, 0) }

    private fun accentButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(0xFFFFFFFF.toInt()); background = rounded(ACCENT); textSize = 16f; setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT); lp.setMargins(0, dp(10), 0, 0); layoutParams = lp
        setOnClickListener { onClick() }
    }
    private fun plainButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(ACCENT); background = rounded(PAGE_BG); setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT); setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_BG = 0xFFEDEFF2.toInt()
        const val CARD_BG = 0xFFFFFFFF.toInt()
        val ACCENT = 0xFF1565C0.toInt()
        const val TEXT = 0xFF1A1A1A.toInt()
        const val MUTED = 0xFF616161.toInt()
        const val OK = 0xFF2E7D32.toInt()
    }
}
