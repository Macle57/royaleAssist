package com.macle.royaleassist.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared screens cannot do themselves. The Android activities implement these with
 * intents, the overlay service and the scan recorder; the iOS shell with its own navigation.
 */
interface AppNavigator {
    /** Opens the history list. */
    fun openHistory()

    /** Opens the RoyaleAPI browser check, searching [query] (the Cloudflare challenge is passed there). */
    fun openVerify(query: String?)

    /** Opens the player's RoyaleAPI page in the browser. */
    fun openPlayerPage(tag: String)
}

/** State of the floating bubble (Android overlay service). */
data class OverlayStatus(val running: Boolean = false, val capturing: Boolean = false)

/**
 * The Android overlay: permission, screen-capture consent and the bubble service. Platforms without
 * a bubble pass null and the setup screen leaves out the permission and scan-region sections.
 */
interface OverlayControls {
    val status: StateFlow<OverlayStatus>

    /** Re-read whenever the screen resumes (the user may come back from the system setting). */
    fun canDrawOverlays(): Boolean

    fun openOverlayPermission()

    /** Asks for MediaProjection consent (starts the bubble first when allowed). */
    fun requestCapture()

    fun startBubble()

    fun stopBubble()

    fun editRegion()

    fun showRegion()
}

/** Saved debug scans (Android scan recorder); null hides that part of the debug section. */
interface ScanArchive {
    /** How many scans are kept. */
    val keep: Int

    /** Initial value of the "save scans" switch until the setting loads. */
    val saveByDefault: Boolean

    /** (count, bytes) of the saved scans. */
    suspend fun stats(): Pair<Int, Long>

    suspend fun clear()

    /** Shares the debug bundle. */
    suspend fun export()
}
