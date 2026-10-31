package com.macle.royaleassist

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Every log line of the app uses this tag so `adb logcat -s RoyaleAssist` shows the whole story. */
const val TAG = "RoyaleAssist"

/**
 * Logging to logcat, mirrored into a size-capped file (`<base>/logs/royaleassist.log`, rotated to
 * `.1` at [MAX_BYTES]) so the log can be exported from the phone with the debug bundle.
 */
object L {
    private const val MAX_BYTES = 1_000_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "royaleassist-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(logDir: File) {
        logDir.mkdirs()
        file = File(logDir, "royaleassist.log")
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg, null) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t); write("W", msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); write("E", msg, t) }

    private fun write(level: String, msg: String, t: Throwable?) {
        val f = file ?: return
        val line = "${stamp.format(Date())} $level ${Thread.currentThread().name}: $msg" +
            (t?.let { "\n" + Log.getStackTraceString(it) } ?: "") + "\n"
        io.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            }
        }
    }
}
