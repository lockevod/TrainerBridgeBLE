package com.enderthor.trainerbridgeble

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Optional diagnostic log to `<externalFilesDir>/trainerbridgeble.csv`. '#'-prefixed event lines only
 * (this app has no per-sample rows). IO on a single background thread so it never blocks a BLE callback.
 * Pull with `adb pull /sdcard/Android/data/com.enderthor.trainerbridgeble/files/trainerbridgeble.csv`.
 */
object FileLog {
    @Volatile var enabled = false
    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        if (file != null) return
        file = File(context.getExternalFilesDir(null), "trainerbridgeble.csv").also { if (!it.exists()) runCatching { it.writeText("") } }
    }

    fun event(msg: String) {
        if (!enabled) return
        val f = file ?: return
        io.execute { runCatching { f.appendText("# ${System.currentTimeMillis()} $msg\n") } }
    }

    fun clear() { file?.let { f -> io.execute { runCatching { f.writeText("") } } } }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}
