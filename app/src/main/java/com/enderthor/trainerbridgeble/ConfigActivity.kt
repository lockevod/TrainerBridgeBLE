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
    private lateinit var floorField: EditText
    private lateinit var nameField: EditText
    private lateinit var pairedLine: TextView
    private lateinit var scanBtn: TextView
    private lateinit var foundList: LinearLayout
    private lateinit var inUseBox: LinearLayout
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
        FileLog.init(this); FileLog.enabled = config.loggingEnabled
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.PAGE_BG); setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        body.addView(title(getString(R.string.config_title)))

        // Correction
        val corr = card(getString(R.string.config_correction))
        corr.addView(bodyText(getString(R.string.config_formula), 12f))
        corr.addView(bodyText(getString(R.string.config_scale_label), 13f))
        scaleField = intField(config.scaleAdjustPercent.toString()); corr.addView(scaleField)
        corr.addView(bodyText(getString(R.string.config_offset_label), 13f))
        offsetField = intField(config.offsetW.toString()); corr.addView(offsetField)
        corr.addView(bodyText(getString(R.string.config_floor_label), 13f))
        floorField = intField(config.invertFloorW.toString()); corr.addView(floorField)
        corr.addView(accentButton(getString(R.string.config_save_correction)) { save(); toast(getString(R.string.config_saved)) })
        body.addView(corr)

        // Trainer pairing
        val src = card(getString(R.string.config_bike))
        pairedLine = bodyText(pairedText(), 14f, Palette.TEXT); src.addView(pairedLine)
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanBtn = accentButton(getString(R.string.config_scan_start)) { toggleScan() }
        src.addView(scanBtn); src.addView(foundList)
        inUseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        src.addView(inUseBox); rebuildInUse()
        src.addView(plainButton(getString(R.string.config_forget)) { config.pairedAddress = ""; config.pairedName = ""; pairedLine.text = pairedText(); rebuildInUse() })
        body.addView(src)

        // Advertised identity
        val idc = card(getString(R.string.config_identity))
        idc.addView(bodyText(getString(R.string.config_name_hint), 12f))
        nameField = textField(config.advertisedName).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(14))   // >14 chars overflows the 31-byte advert with the cloned UUIDs+mfr data
        }
        idc.addView(nameField)
        body.addView(idc)

        // Toggles
        val opt = card(getString(R.string.config_options))
        logCheck = check(getString(R.string.config_log), config.loggingEnabled); opt.addView(logCheck)
        simCheck = check(getString(R.string.config_sim), config.simulate); opt.addView(simCheck)
        antCheck = check(getString(R.string.config_ant_output), config.antOutputEnabled); opt.addView(antCheck)
        opt.addView(bodyText(getString(R.string.config_ant_id), 13f))
        antIdField = intField(config.antDeviceId.toString()); opt.addView(antIdField)
        body.addView(opt)

        body.addView(accentButton(getString(R.string.config_save_back)) { save(); finish() })

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.PAGE_BG); addView(body) })
    }

    override fun onResume() { super.onResume(); rebuildInUse() }   // the bridge may have connected meanwhile
    override fun onStop() { super.onStop(); stopScan(); save() }

    private fun pairedText() = if (config.pairedAddress.isEmpty()) getString(R.string.config_none_paired)
    else getString(R.string.config_paired, config.pairedName.ifEmpty { config.pairedAddress })

    private fun save() {
        scaleField.text.toString().toIntOrNull()?.let { if (it > -100) config.scaleAdjustPercent = it }
        offsetField.text.toString().toIntOrNull()?.let { config.offsetW = it }
        floorField.text.toString().toIntOrNull()?.let { if (it >= 0) config.invertFloorW = it }
        nameField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { config.advertisedName = it }
        config.loggingEnabled = logCheck.isChecked
        config.simulate = simCheck.isChecked
        config.antOutputEnabled = antCheck.isChecked
        antIdField.text.toString().toIntOrNull()?.let { if (it in 1..65535) config.antDeviceId = it }
        BridgeService.reconfigure(this)   // no-op unless the source (simulation / paired trainer) actually changed
    }

    // ── BLE scan ──
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: run {
                FileLog.event("config scan: unnamed ${dev.address} rssi=${result.rssi} — skipped"); return
            }
            if (found.put(dev.address, name) == null) {
                FileLog.event("config scan: '$name' ${dev.address} rssi=${result.rssi}")
                rebuildFound()
            }
        }
        override fun onScanFailed(errorCode: Int) {
            FileLog.event("config scan FAILED code=$errorCode")
            toast(getString(R.string.config_scan_failed, errorCode)); stopScan()
        }
    }

    private fun toggleScan() {
        if (scanning) { stopScan(); return }
        found.clear(); rebuildFound()
        val scanner = adapter?.bluetoothLeScanner ?: run { toast(getString(R.string.config_ble_unavailable)); return }
        scanning = true; scanBtn.text = getString(R.string.config_scan_stop)
        FileLog.event("config scan start (filters: FTMS 0x1826 + CPS 0x1818)")
        runCatching {
            scanner.startScan(com.enderthor.trainerbridgeble.ble.GattUuids.scanFilters(0x1826, 0x1818),
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false; scanBtn.text = getString(R.string.config_scan_start)
        FileLog.event("config scan stop, ${found.size} device(s)")
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /** A BLE peripheral stops advertising while it is connected, so the trainer the bridge is currently
     *  using can never show up in the scan. Offer it directly instead. */
    private fun rebuildInUse() {
        inUseBox.removeAllViews()
        val addr = config.lastSeenAddress
        if (addr.isEmpty() || addr == config.pairedAddress) return
        val name = config.lastSeenName.ifEmpty { addr }
        inUseBox.addView(bodyText(getString(R.string.config_in_use, name), 13f, Palette.MUTED))
        inUseBox.addView(plainButton(getString(R.string.config_pair_in_use)) {
            config.pairedAddress = addr; config.pairedName = config.lastSeenName
            pairedLine.text = pairedText(); rebuildInUse(); toast(getString(R.string.config_paired, name))
        })
    }

    private fun rebuildFound() {
        foundList.removeAllViews()
        for ((addr, name) in found) {
            foundList.addView(TextView(this).apply {
                text = "  • $name  ($addr)"; textSize = 14f; setTextColor(Palette.ACCENT); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    config.pairedAddress = addr; config.pairedName = name
                    pairedLine.text = pairedText(); rebuildInUse(); stopScan(); toast(getString(R.string.config_paired, name))
                }
            })
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
