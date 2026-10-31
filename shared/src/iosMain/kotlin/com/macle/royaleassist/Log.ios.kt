package com.macle.royaleassist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSThread
import platform.Foundation.NSUserDomainMask
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** NSLog plus `Documents/logs/royaleassist.log` (rotated to `.1` at 1 MB), written in order off the caller's thread. */
actual object L {
    private const val MAX_BYTES = 1_000_000L
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var file: Path? = null

    /** Starts mirroring into [logDir] (default: `Documents/logs`). */
    fun init(logDir: String? = null) {
        val dir = logDir ?: (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/logs" } ?: return
        runCatching { SystemFileSystem.createDirectories(Path(dir)) }
        file = Path(dir, "royaleassist.log")
    }

    actual fun i(msg: String) { NSLog("%s", "$TAG I $msg"); write("I", msg, null) }
    actual fun w(msg: String, t: Throwable?) { NSLog("%s", "$TAG W $msg${t?.let { ": $it" } ?: ""}"); write("W", msg, t) }
    actual fun e(msg: String, t: Throwable?) { NSLog("%s", "$TAG E $msg${t?.let { ": $it" } ?: ""}"); write("E", msg, t) }

    private fun write(level: String, msg: String, t: Throwable?) {
        val f = file ?: return
        val thread = if (NSThread.isMainThread) "main" else "worker"
        val line = "${stamp()} $level $thread: $msg" + (t?.let { "\n" + it.stackTraceToString() } ?: "") + "\n"
        io.launch {
            runCatching {
                val size = SystemFileSystem.metadataOrNull(f)?.size ?: 0L
                if (size > MAX_BYTES) {
                    val old = Path(f.parent!!, f.name + ".1")
                    if (SystemFileSystem.exists(old)) SystemFileSystem.delete(old)
                    SystemFileSystem.atomicMove(f, old)
                }
                SystemFileSystem.sink(f, append = true).buffered().use { it.writeString(line) }
            }
        }
    }

    /** "MM-dd HH:mm:ss.SSS" in local time, like the Android log file. */
    private fun stamp(): String {
        val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        fun p(v: Int, n: Int = 2) = v.toString().padStart(n, '0')
        return "${p(t.month.ordinal + 1)}-${p(t.day)} ${p(t.hour)}:${p(t.minute)}:${p(t.second)}.${p(t.nanosecond / 1_000_000, 3)}"
    }
}
