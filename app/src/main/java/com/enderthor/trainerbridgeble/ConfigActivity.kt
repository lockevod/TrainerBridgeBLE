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
    private var softFilter = false   // unfiltered retry in progress: match FTMS/CPS in the callback instead
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
            // Empty is the normal case, so show what empty actually MEANS: the device's own name, greyed out
            // in the field itself rather than only explained in the hint text above it.
            hint = runCatching { adapter?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.config_name_device_default)
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
        // A value that doesn't parse used to be dropped in silence, leaving the OLD calibration in place
        // while the field showed the new one — in an app whose whole job is calibration.
        val rejected = mutableListOf<String>()
        fun intField(field: EditText, label: String, ok: (Int) -> Boolean, apply: (Int) -> Unit) {
            val raw = field.text.toString().trim()
            val v = raw.toIntOrNull()
            if (v != null && ok(v)) apply(v) else if (raw.isNotEmpty()) rejected += label
        }
        intField(scaleField, getString(R.string.config_scale_label), { it > -100 }) { config.scaleAdjustPercent = it }
        intField(offsetField, getString(R.string.config_offset_label), { true }) { config.offsetW = it }
        intField(floorField, getString(R.string.config_floor_label), { it >= 0 }) { config.invertFloorW = it }
        config.advertisedName = nameField.text.toString().trim()   // blank = keep the device's own name
        config.loggingEnabled = logCheck.isChecked
        config.simulate = simCheck.isChecked
        config.antOutputEnabled = antCheck.isChecked
        intField(antIdField, getString(R.string.config_ant_id), { it in 1..65535 }) { config.antDeviceId = it }
        if (rejected.isNotEmpty()) toast(getString(R.string.config_not_saved, rejected.size))
        BridgeService.reconfigure(this)   // no-op unless the source (simulation / paired trainer) actually changed
    }

    // ── BLE scan ──
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: run {
                FileLog.event("config scan: unnamed ${dev.address} rssi=${result.rssi} — skipped"); return
            }
            if (softFilter && result.scanRecord?.serviceUuids?.any { it.uuid == POWER_SVC || it.uuid == FTMS_SVC } != true) {
                FileLog.event("config scan: '$name' ${dev.address} — no FTMS/CPS in advert or scan response, skipped"); return
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
        found.clear(); softFilter = false; rebuildFound()
        val scanner = adapter?.bluetoothLeScanner ?: run { toast(getString(R.string.config_ble_unavailable)); return }
        FileLog.event("config scan start (filters: FTMS 0x1826 + CPS 0x1818)")
        if (!startScan(filtered = true)) { toast(getString(R.string.config_ble_unavailable)); return }
        scanning = true; scanBtn.text = getString(R.string.config_scan_stop)   // only once the scan really started
        // A trainer may carry its service UUIDs in the scan response, which the controller's offloaded
        // filter never sees — so a filtered scan that finds nothing must not be the end of the road.
        handler.postDelayed({
            if (scanning && found.isEmpty()) {
                FileLog.event("config scan: nothing with a service filter, retrying unfiltered")
                runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
                softFilter = true
                startScan(filtered = false)
            }
        }, UNFILTERED_RETRY_MS)
    }

    /** @return false if the scan could not be started at all. */
    private fun startScan(filtered: Boolean): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = if (filtered) com.enderthor.trainerbridgeble.ble.GattUuids.scanFilters(0x1826, 0x1818) else null
        return runCatching { scanner.startScan(filters, settings, scanCallback) }.isSuccess
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false; scanBtn.text = getString(R.string.config_scan_start)
        handler.removeCallbacksAndMessages(null)
        FileLog.event("config scan stop, ${found.size} device(s)${if (softFilter) " (unfiltered pass)" else ""}")
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

    private companion object {
        const val UNFILTERED_RETRY_MS = 6000L
        val FTMS_SVC = com.enderthor.trainerbridgeble.ble.GattUuids.uuid16(0x1826)
        val POWER_SVC = com.enderthor.trainerbridgeble.ble.GattUuids.uuid16(0x1818)
    }
}
