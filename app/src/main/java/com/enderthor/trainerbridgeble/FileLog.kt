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
        val ts = System.currentTimeMillis()   // when it HAPPENED — the IO queue can lag under load
        io.execute {
            runCatching {
                // Nothing is throttled (a dropped line is the one you needed), so cap the file instead.
                // Rotate rather than truncate: truncating at the cap leaves you holding a log that starts
                // seconds ago, which is worthless for a ride that just ended. O(1) — a rename, no read.
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    runCatching { if (old.exists()) old.delete(); f.renameTo(old) }
                    f.writeText("# $ts log rotated at ${MAX_BYTES / 1024 / 1024} MB (previous: ${f.name}.1)\n")
                }
                f.appendText("# $ts $msg\n")
            }
        }
    }

    private const val MAX_BYTES = 16L * 1024 * 1024   // two files kept, so 32 MB total

    fun clear() { file?.let { f -> io.execute { runCatching { f.writeText("") } } } }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}
