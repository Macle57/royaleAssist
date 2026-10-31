package com.macle.royaleassist

/** Every log line of the app uses this tag so `adb logcat -s RoyaleAssist` shows the whole story. */
const val TAG = "RoyaleAssist"

/**
 * Logging to the platform log (logcat / NSLog), mirrored into a size-capped file
 * (`<logs>/royaleassist.log`, rotated to `.1` at 1 MB) once the platform has set the folder.
 */
expect object L {
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)
}
