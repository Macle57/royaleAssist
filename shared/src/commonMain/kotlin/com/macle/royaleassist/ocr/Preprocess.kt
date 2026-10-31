package com.macle.royaleassist.ocr

import com.macle.royaleassist.image.ArgbImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/**
 * Pure-Kotlin image operations on [ArgbImage]. They follow what the Android code did with
 * `Bitmap.createScaledBitmap(filter = true)` (bilinear, pixel centres, clamped edges) and a
 * `ColorMatrixColorFilter` (saturation 0 with Skia's luma weights, then contrast around 128);
 * results can differ from Skia's fixed-point maths by a grey level here and there.
 */
object Preprocess {
    /** Images whose longer side is below this get upscaled (up to 3x). Full screenshots are not. */
    private const val TARGET_LONG_SIDE = 1100

    /**
     * The resize [apply] uses. Android plugs in Skia (`Bitmap.createScaledBitmap`, see
     * `useSkiaResize`) so ML Kit gets exactly the pixels it got before the move to common code;
     * elsewhere (iOS, unit tests) it is [scaleBilinear].
     */
    @kotlin.concurrent.Volatile
    var resizer: (ArgbImage, Int, Int) -> ArgbImage = ::scaleBilinear

    fun scaleFor(img: ArgbImage, prep: Prep): Float {
        if (!prep.upscale) return 1f
        val longSide = max(img.width, img.height)
        if (longSide >= TARGET_LONG_SIDE) return 1f
        return min(3f, TARGET_LONG_SIDE.toFloat() / longSide).coerceAtLeast(1f)
    }

    /** Returns the processed image and the scale factor applied (boxes must be divided by it). */
    fun apply(src: ArgbImage, prep: Prep, forcedScale: Float? = null): Pair<ArgbImage, Float> {
        val scale = forcedScale ?: scaleFor(src, prep)
        var img = src
        if (scale != 1f) {
            img = resizer(img, (img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1))
        }
        if (prep.gray || prep.contrast != 1f) img = colorFilter(img, prep.gray, prep.contrast)
        prep.threshold?.let { img = binarize(img, it, invert = false) }
        return img to scale
    }

    /** Bilinear resize to [w] x [h], sampling at pixel centres with edges clamped. */
    fun scaleBilinear(src: ArgbImage, w: Int, h: Int): ArgbImage {
        if (w == src.width && h == src.height) return src
        val sw = src.width
        val sh = src.height
        val sp = src.pixels
        val out = IntArray(w * h)
        val fx = sw.toFloat() / w
        val fy = sh.toFloat() / h
        // Per column: left source index and weight of the right neighbour.
        val x0s = IntArray(w)
        val x1s = IntArray(w)
        val wxs = FloatArray(w)
        for (x in 0 until w) {
            val sx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val x0 = floor(sx).toInt()
            x0s[x] = x0
            x1s[x] = min(x0 + 1, sw - 1)
            wxs[x] = sx - x0
        }
        for (y in 0 until h) {
            val sy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, sh - 1)
            val wy = sy - y0
            val r0 = y0 * sw
            val r1 = y1 * sw
            val o = y * w
            for (x in 0 until w) {
                val wx = wxs[x]
                val p00 = sp[r0 + x0s[x]]
                val p01 = sp[r0 + x1s[x]]
                val p10 = sp[r1 + x0s[x]]
                val p11 = sp[r1 + x1s[x]]
                var c = 0
                var shift = 0
                while (shift < 32) {
                    val a = (p00 ushr shift) and 0xff
                    val b = (p01 ushr shift) and 0xff
                    val cc = (p10 ushr shift) and 0xff
                    val d = (p11 ushr shift) and 0xff
                    val top = a + (b - a) * wx
                    val bottom = cc + (d - cc) * wx
                    val v = (top + (bottom - top) * wy + 0.5f).toInt().coerceIn(0, 255)
                    c = c or (v shl shift)
                    shift += 8
                }
                out[o + x] = c
            }
        }
        return ArgbImage(w, h, out)
    }

    /**
     * Grey (Android `ColorMatrix.setSaturation(0)`: 0.213 R + 0.715 G + 0.072 B) and/or contrast
     * ((v - 128) * contrast + 128), clamped; alpha is kept.
     */
    fun colorFilter(src: ArgbImage, gray: Boolean, contrast: Float): ArgbImage {
        val px = src.pixels
        val out = IntArray(src.width * src.height)
        val t = (1f - contrast) * 128f
        for (i in out.indices) {
            val c = px[i]
            var r = ((c shr 16) and 0xff).toFloat()
            var g = ((c shr 8) and 0xff).toFloat()
            var b = (c and 0xff).toFloat()
            if (gray) {
                val l = 0.213f * r + 0.715f * g + 0.072f * b
                r = l; g = l; b = l
            }
            if (contrast != 1f) {
                r = r * contrast + t
                g = g * contrast + t
                b = b * contrast + t
            }
            out[i] = (c and 0xff000000.toInt()) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
        return ArgbImage(src.width, src.height, out)
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /**
     * Luminance threshold. With invert=false bright pixels (the white/pink glyph fill) become black
     * on white, which is what Tesseract wants; the dark outline and background become white.
     */
    fun binarize(src: ArgbImage, threshold: Int, invert: Boolean): ArgbImage {
        val w = src.width
        val h = src.height
        val px = src.pixels.copyOf(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val text = if (invert) lum < threshold else lum >= threshold
            px[i] = if (text) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return ArgbImage(w, h, px)
    }

    /** Crop with a margin (fraction of the box height), clamped to the image. */
    fun crop(src: ArgbImage, box: Box, marginFrac: Float = 0.25f): Pair<ArgbImage, Box> {
        val m = (box.height * marginFrac).toInt()
        val l = (box.left - m).coerceIn(0, src.width - 1)
        val t = (box.top - m).coerceIn(0, src.height - 1)
        val r = (box.right + m).coerceIn(l + 1, src.width)
        val b = (box.bottom + m).coerceIn(t + 1, src.height)
        return src.crop(l, t, r - l, b - t) to Box(l, t, r, b)
    }
}
