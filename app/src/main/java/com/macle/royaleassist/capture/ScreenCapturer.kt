package com.macle.royaleassist.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.macle.royaleassist.L
import kotlinx.coroutines.delay

/**
 * Grabs single frames of the screen through MediaProjection.
 *
 * Since Android 14 a consent token can be used once and a MediaProjection may create only one
 * VirtualDisplay, so the projection and its VirtualDisplay are kept for the whole session. Between
 * captures the VirtualDisplay has no surface (nothing is rendered); a capture attaches the
 * ImageReader surface, hides the overlay, takes the first fresh frame and detaches again.
 * If the system ends the projection (user taps "Stop sharing", screen lock on some versions,
 * another app starts projecting), [onStopped] fires and the next capture asks for consent again.
 */
class ScreenCapturer(private val context: Context) {
    private val thread = HandlerThread("royaleassist-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    var onStopped: (() -> Unit)? = null

    val isActive: Boolean get() = projection != null && display != null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            L.i("capture: projection stopped by the system")
            release()
            onStopped?.invoke()
        }
    }

    /** Must be called after the service is in the foreground with type mediaProjection. */
    fun start(resultCode: Int, data: Intent): Boolean {
        release()
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            L.e("capture: getMediaProjection failed", e)
            null
        } ?: return false
        p.registerCallback(callback, handler)
        projection = p
        readMetrics()
        reader = newReader()
        display = try {
            p.createVirtualDisplay(
                "royaleAssist", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, handler,
            )
        } catch (e: Exception) {
            L.e("capture: createVirtualDisplay failed", e)
            release()
            return false
        }
        L.i("capture: projection ready ${width}x$height@$dpi")
        return true
    }

    private fun readMetrics() {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width(); height = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            width = m.widthPixels; height = m.heightPixels
        }
        dpi = context.resources.displayMetrics.densityDpi
    }

    @SuppressLint("WrongConstant")
    private fun newReader() = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    /**
     * Attaches the reader, runs [hideOverlay] (which should hide our windows and wait a couple
     * of frames), then returns the first frame rendered after that, or null on timeout.
     */
    suspend fun capture(hideOverlay: suspend () -> Unit): Bitmap? {
        val vd = display ?: return null
        // Follow rotation / resolution changes without creating a second VirtualDisplay.
        val (ow, oh) = width to height
        readMetrics()
        if (ow != width || oh != height || reader == null) {
            reader?.close()
            reader = newReader()
            vd.resize(width, height, dpi)
            L.i("capture: resized to ${width}x$height")
        }
        val r = reader ?: return null
        drain(r)
        vd.surface = r.surface
        try {
            hideOverlay()
            val after = System.nanoTime()
            var fallback: Image? = null
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                val img = r.acquireLatestImage()
                if (img != null) {
                    if (img.timestamp >= after) {
                        fallback?.close()
                        L.i("capture: fresh frame (ts +${(img.timestamp - after) / 1_000_000} ms)")
                        return img.use { toBitmap(it) }
                    }
                    fallback?.close()
                    fallback = img
                }
                delay(16)
            }
            // Static screens may not produce a new frame; the newest one we saw still had the overlay hidden.
            L.w("capture: no fresh frame, using ${if (fallback != null) "latest frame" else "nothing"}")
            return fallback?.use { toBitmap(it) }
        } finally {
            vd.surface = null
        }
    }

    private fun drain(r: ImageReader) {
        while (true) { (r.acquireLatestImage() ?: return).close() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    fun release() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.let {
            it.unregisterCallback(callback)
            it.stop()
        }
        projection = null
    }

    fun shutdown() {
        release()
        thread.quitSafely()
    }
}
