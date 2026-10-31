package com.macle.royaleassist.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Image preparation before OCR. The game font is bold with a dark outline, which small text
 * recognisers dislike; upscaling small images helps most. Variants are compared by the debug
 * harness (`DebugActivity --es mode variants`), and [DEFAULT] is the winner on the 7 samples.
 */
enum class Prep(val upscale: Boolean, val gray: Boolean, val contrast: Float, val threshold: Int?) {
    NONE(false, false, 1f, null),
    UPSCALE(true, false, 1f, null),
    UPSCALE_GRAY(true, true, 1f, null),
    UPSCALE_GRAY_CONTRAST(true, true, 1.6f, null),
    UPSCALE_BINARY(true, true, 1f, 170);

    companion object {
        val DEFAULT = UPSCALE
    }
}

object Preprocess {
    /** Images whose longer side is below this get upscaled (up to 3x). Full screenshots are not. */
    private const val TARGET_LONG_SIDE = 1100

    fun scaleFor(bmp: Bitmap, prep: Prep): Float {
        if (!prep.upscale) return 1f
        val longSide = max(bmp.width, bmp.height)
        if (longSide >= TARGET_LONG_SIDE) return 1f
        return min(3f, TARGET_LONG_SIDE.toFloat() / longSide).coerceAtLeast(1f)
    }

    /** Returns the processed bitmap and the scale factor applied (boxes must be divided by it). */
    fun apply(src: Bitmap, prep: Prep, forcedScale: Float? = null): Pair<Bitmap, Float> {
        val scale = forcedScale ?: scaleFor(src, prep)
        var bmp = src.ensureArgb()
        if (scale != 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        if (prep.gray || prep.contrast != 1f) bmp = colorFilter(bmp, prep.gray, prep.contrast)
        prep.threshold?.let { bmp = binarize(bmp, it, invert = false) }
        return bmp to scale
    }

    fun Bitmap.ensureArgb(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)

    private fun colorFilter(src: Bitmap, gray: Boolean, contrast: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix()
        if (gray) cm.setSaturation(0f)
        if (contrast != 1f) {
            val t = (1f - contrast) * 128f
            cm.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /**
     * Luminance threshold. With invert=false bright pixels (the white/pink glyph fill) become black
     * on white, which is what Tesseract wants; the dark outline and background become white.
     */
    fun binarize(src: Bitmap, threshold: Int, invert: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val text = if (invert) lum < threshold else lum >= threshold
            px[i] = if (text) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Crop with a margin (fraction of the box height), clamped to the bitmap. */
    fun crop(src: Bitmap, box: Box, marginFrac: Float = 0.25f): Pair<Bitmap, Box> {
        val m = (box.height * marginFrac).toInt()
        val l = (box.left - m).coerceIn(0, src.width - 1)
        val t = (box.top - m).coerceIn(0, src.height - 1)
        val r = (box.right + m).coerceIn(l + 1, src.width)
        val b = (box.bottom + m).coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t) to Box(l, t, r, b)
    }
}
