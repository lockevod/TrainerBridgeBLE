package com.enderthor.trainerbridgeble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Configuration: pair the trainer over BLE (scan + pick), correction (scale % / offset W), the advertised
 *  name we present to the apps, and the log + simulation toggles. */
@SuppressLint("MissingPermission")
class ConfigActivity : Activity() {
    private lateinit var config: Config
    private lateinit var scaleField: EditText
    private lateinit var offsetField: EditText
    private lateinit var nameField: EditText
    private lateinit var pairedLine: TextView
    private lateinit var scanBtn: TextView
    private lateinit var foundList: LinearLayout
    private lateinit var logCheck: android.widget.CheckBox
    private lateinit var simCheck: android.widget.CheckBox
    private lateinit var antCheck: android.widget.CheckBox
    private lateinit var antIdField: EditText

    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private var scanning = false
    private val found = LinkedHashMap<String, String>()   // address → name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAGE_BG); setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        body.addView(title("Configuración"))

        // Correction
        val corr = card("Corrección")
        corr.addView(bodyText("corregida = cruda × (1 + escala%/100) + offset   ·   cruda 0 → 0", 12f))
        corr.addView(bodyText("Ajuste de escala (%, entero)", 13f))
        scaleField = intField(config.scaleAdjustPercent.toString()); corr.addView(scaleField)
        corr.addView(bodyText("Offset (W, entero)", 13f))
        offsetField = intField(config.offsetW.toString()); corr.addView(offsetField)
        corr.addView(accentButton("Guardar corrección") { save(); toast("Guardado") })
        body.addView(corr)

        // Trainer pairing
        val src = card("Bicicleta (BLE)")
        pairedLine = bodyText(pairedText(), 14f, Palette.TEXT); src.addView(pairedLine)
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanBtn = accentButton("Buscar bicicleta") { toggleScan() }
        src.addView(scanBtn); src.addView(foundList)
        src.addView(plainButton("Olvidar dispositivo") { config.pairedAddress = ""; config.pairedName = ""; pairedLine.text = pairedText() })
        body.addView(src)

        // Advertised identity
        val idc = card("Identidad anunciada")
        idc.addView(bodyText("Nombre con el que nos ven las apps (por defecto igual que el Zycle para que reconozca sus capacidades).", 12f))
        nameField = textField(config.advertisedName).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(14))   // >14 chars overflows the 31-byte advert with the cloned UUIDs+mfr data
        }
        idc.addView(nameField)
        body.addView(idc)

        // Toggles
        val opt = card("Opciones")
        logCheck = check("Guardar log (CSV)", config.loggingEnabled); opt.addView(logCheck)
        simCheck = check("Modo simulación (sin bici)", config.simulate); opt.addView(simCheck)
        antCheck = check("Salida ANT+ al Garmin (necesita dongle)", config.antOutputEnabled); opt.addView(antCheck)
        opt.addView(bodyText("ID ANT+ (1–65535, distinto en móvil y Karoo para no chocar)", 13f))
        antIdField = intField(config.antDeviceId.toString()); opt.addView(antIdField)
        body.addView(opt)

        body.addView(accentButton("Guardar y volver") { save(); finish() })

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.PAGE_BG); addView(body) })
    }

    override fun onStop() { super.onStop(); stopScan(); save() }

    private fun pairedText() = if (config.pairedAddress.isEmpty()) "Ninguna emparejada (se buscará por nombre \"${config.namePrefix}\")"
    else "Emparejada: ${config.pairedName.ifEmpty { config.pairedAddress }}"

    private fun save() {
        scaleField.text.toString().toIntOrNull()?.let { if (it > -100) config.scaleAdjustPercent = it }
        offsetField.text.toString().toIntOrNull()?.let { config.offsetW = it }
        nameField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { config.advertisedName = it }
        config.loggingEnabled = logCheck.isChecked
        config.simulate = simCheck.isChecked
        config.antOutputEnabled = antCheck.isChecked
        antIdField.text.toString().toIntOrNull()?.let { if (it in 1..65535) config.antDeviceId = it }
    }

    // ── BLE scan ──
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: return   // only named devices
            if (found.put(dev.address, name) == null) rebuildFound()
        }
    }

    private fun toggleScan() {
        if (scanning) { stopScan(); return }
        found.clear(); rebuildFound()
        val scanner = adapter?.bluetoothLeScanner ?: run { toast("BLE no disponible"); return }
        scanning = true; scanBtn.text = "Parar búsqueda"
        runCatching { scanner.startScan(scanCallback) }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false; scanBtn.text = "Buscar bicicleta"
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun rebuildFound() {
        foundList.removeAllViews()
        for ((addr, name) in found) {
            foundList.addView(TextView(this).apply {
                text = "  • $name  ($addr)"; textSize = 14f; setTextColor(Palette.ACCENT); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    config.pairedAddress = addr; config.pairedName = name
                    pairedLine.text = pairedText(); stopScan(); toast("Emparejada: $name")
                }
            })
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
