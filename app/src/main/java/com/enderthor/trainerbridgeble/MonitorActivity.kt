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
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal UI: correction (scale % / offset W), Start/Stop, and live status + raw/corrected power. */
class MonitorActivity : Activity() {
    private lateinit var config: Config
    private lateinit var status: TextView
    private lateinit var power: TextView
    private lateinit var startBtn: Button
    private lateinit var scaleField: EditText
    private lateinit var offsetField: EditText

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
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "TrainerBridge BLE"; textSize = 22f })
        status = TextView(this).apply { text = "parado"; textSize = 16f }; root.addView(status)
        power = TextView(this).apply { text = "—"; textSize = 28f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }; root.addView(power)

        root.addView(TextView(this).apply { text = "Ajuste de escala (%, entero)" })
        scaleField = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(config.scaleAdjustPercent.toString()) }
        root.addView(scaleField)
        root.addView(TextView(this).apply { text = "Offset (W, entero)" })
        offsetField = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(config.offsetW.toString()) }
        root.addView(offsetField)

        startBtn = Button(this).apply { text = "Start"; setOnClickListener { onStartStop() } }
        root.addView(startBtn)

        setContentView(root)
        ensurePermissions()
    }

    override fun onStart() { super.onStart(); bindService(Intent(this, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE) }
    override fun onStop() { super.onStop(); service?.listener = null; runCatching { unbindService(conn) } }

    private fun onStartStop() {
        saveCorrection()
        if (service?.isRunning == true) { BridgeService.stop(this) }
        else { BridgeService.start(this) }
        render()
    }

    private fun saveCorrection() {
        scaleField.text.toString().toIntOrNull()?.let { if (it > -100) config.scaleAdjustPercent = it }
        offsetField.text.toString().toIntOrNull()?.let { config.offsetW = it }
    }

    private fun render() {
        val s = service
        val running = s?.isRunning == true
        startBtn.text = if (running) "Stop" else "Start"
        status.text = s?.status ?: "parado"
        power.text = s?.let { if (it.lastCorrectedW != null) "${it.lastRawW} → ${it.lastCorrectedW} W" else "—" } ?: "—"
    }

    private fun ensurePermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
