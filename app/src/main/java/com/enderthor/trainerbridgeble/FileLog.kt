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
    /** Every mutation of the file goes through this: the executor's append+rotate and the synchronous
     *  shutdown line write the same path, and a rotation racing that line would lose the one record that
     *  tells a device shutdown apart from a crashed bridge. */
    private val fileLock = Any()

    fun init(context: Context) {
        if (file != null) return
        file = File(context.getExternalFilesDir(null), "trainerbridgeble.csv").also { if (!it.exists()) runCatching { it.writeText("") } }
    }

    fun event(msg: String) {
        if (!enabled) return
        val f = file ?: return
        val ts = System.currentTimeMillis()   // when it HAPPENED — the IO queue can lag under load
        io.execute {
            runCatching { synchronized(fileLock) {
                // Nothing is throttled (a dropped line is the one you needed), so cap the file instead.
                // Rotate rather than truncate: truncating at the cap leaves you holding a log that starts
                // seconds ago, which is worthless for a ride that just ended. O(1) — a rename, no read.
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    // ONLY start a new file if the rename actually happened — falling through on failure
                    // truncates the very history the rotation exists to keep.
                    val rotated = runCatching { if (old.exists()) old.delete(); f.renameTo(old) }.getOrDefault(false)
                    if (rotated) f.writeText("# $ts log rotated at ${MAX_BYTES / 1024 / 1024} MB (previous: ${f.name}.1)\n")
                }
                // fsync at most every few seconds, not per line (~10 lines/s): enough that a power cut costs
                // seconds of log, not the NUL-filled holes the 24-jul and 8-sep idle shutdowns left behind.
                val sync = ts - lastSyncMs >= SYNC_EVERY_MS
                if (sync) lastSyncMs = ts
                append(f, "# $ts $msg\n", sync)
            } }
        }
    }

    /** Synchronous append, for the one case where the queue will never drain: the device is powering
     *  off and we have seconds. Blocks the caller — never use it on a BLE callback or the hot path. */
    fun eventNow(msg: String) {
        if (!enabled) return
        val f = file ?: return
        val ts = System.currentTimeMillis()
        // Through the queue, so it lands AFTER the lines that happened before it; the caller's wait is bounded,
        // so a stalled flash cannot hold the shutdown receiver past its window.
        runCatching {
            io.submit { synchronized(fileLock) { append(f, "# $ts $msg\n", sync = true) } }
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Volatile private var lastSyncMs = 0L
    private const val SYNC_EVERY_MS = 5_000L

    /** Append, and optionally force it to flash: without the sync a power cut keeps the file's new length
     *  but not its data. */
    private fun append(f: File, line: String, sync: Boolean) =
        java.io.FileOutputStream(f, true).use { out -> out.write(line.toByteArray()); if (sync) out.fd.sync() }

    // Every notification is logged unthrottled (~2 KB/s), so 16 MB filled in ~2 h and rotation threw away
    // the START of the ride — which is exactly where the connect / sync / first-advertise sequence lives,
    // the part you turned logging on to see. 48 MB holds a ~6 h ride in one file, 12 h across the two.
    private const val MAX_BYTES = 48L * 1024 * 1024   // two files kept, so 96 MB worst case

    fun clear() { file?.let { f -> io.execute { runCatching { f.writeText("") } } } }

    /** Empty when logging is off: callers interpolate this into a message before [event] can check. */
    fun hex(b: ByteArray): String = if (!enabled) "" else b.joinToString("") { "%02X".format(it) }
}
