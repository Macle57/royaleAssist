package com.macle.royaleassist.app

import com.macle.royaleassist.L
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.image.loadArgbImage
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970

/**
 * The frames of the screen broadcast. The ReplayKit upload extension (`royaleAssistBroadcast`,
 * SampleHandler.swift) keeps only the newest frame as `frame.jpg` (about 2 per second, longest side
 * 1600 px, written atomically) and its state in `state.txt` ("started|paused|finished <epoch s>")
 * in the shared App Group container. Builds without the App Group (simulator, unsigned) use
 * Library/Caches/capture instead, so a test can drop a frame there.
 */
object CaptureFrames {
    const val APP_GROUP = "group.com.macle.royaleassist"
    const val FRAME_FILE = "frame.jpg"
    const val STATE_FILE = "state.txt"

    /** A broadcast that has not sent a frame for this long is treated as gone (killed extension). */
    private const val STALE_SECONDS = 120.0

    val dir: String by lazy {
        val group = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)?.path
        val d = if (group != null) "$group/capture" else {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
            "$caches/capture"
        }
        NSFileManager.defaultManager.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        L.i("capture: frames in $d (app group ${if (group != null) "yes" else "no"})")
        d
    }

    val framePath get() = "$dir/$FRAME_FILE"

    /** Seconds since the newest frame was written; null when there is none. */
    fun frameAgeSeconds(): Double? {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(framePath, error = null) ?: return null
        val date = attrs[NSFileModificationDate] as? NSDate ?: return null
        return (NSDate().timeIntervalSince1970 - date.timeIntervalSince1970).coerceAtLeast(0.0)
    }

    /** "started", "paused" or "finished" as the extension last wrote it, and when (epoch seconds). */
    fun state(): Pair<String, Double>? {
        val text = NSString.stringWithContentsOfFile("$dir/$STATE_FILE", NSUTF8StringEncoding, null) ?: return null
        val parts = text.trim().split(' ')
        return parts[0] to (parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
    }

    /** True while the broadcast runs: started (or paused) and a frame within [STALE_SECONDS]. */
    fun isRunning(): Boolean {
        val (s, at) = state() ?: return false
        if (s == "finished") return false
        val age = frameAgeSeconds()
        val sinceStart = NSDate().timeIntervalSince1970 - at
        return (age != null && age < STALE_SECONDS) || sinceStart < STALE_SECONDS
    }

    /** The newest frame as pixels, or null when there is none or it cannot be read. */
    fun latest(): ArgbImage? = runCatching { loadArgbImage(framePath) }
        .onFailure { L.w("capture: cannot read the frame: ${it.message}") }
        .getOrNull()
}
