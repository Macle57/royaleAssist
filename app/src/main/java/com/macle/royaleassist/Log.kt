package com.macle.royaleassist

import android.util.Log

/** Every log line of the app uses this tag so `adb logcat -s RoyaleAssist` shows the whole story. */
const val TAG = "RoyaleAssist"

object L {
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}
