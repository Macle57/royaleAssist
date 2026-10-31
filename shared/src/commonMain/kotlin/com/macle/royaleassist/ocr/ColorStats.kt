package com.macle.royaleassist.ocr

import com.macle.royaleassist.image.ArgbImage

/**
 * Colour of a text line. Clash Royale draws the opponent's name in saturated pink/magenta or
 * orange/yellow, and the clan, "League N", timers and HP in white or pale colours. Measured on the
 * samples: name lines 0.12-0.46 warm pixels, clan/label lines <= 0.02.
 */
object ColorStats {
    /** Fraction of pixels in [box] (in [img] coordinates) that are saturated red/pink/orange/yellow. */
    fun warmFraction(img: ArgbImage, box: Box, maxSamples: Int = 4000): Double {
        val l = box.left.coerceIn(0, img.width - 1)
        val t = box.top.coerceIn(0, img.height - 1)
        val r = box.right.coerceIn(l + 1, img.width)
        val b = box.bottom.coerceIn(t + 1, img.height)
        val area = (r - l).toLong() * (b - t)
        val step = kotlin.math.sqrt(area.toDouble() / maxSamples).toInt().coerceAtLeast(1)
        var n = 0
        var warm = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                if (isWarm(img.getPixel(x, y))) warm++
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.0 else warm.toDouble() / n
    }

    private fun isWarm(c: Int): Boolean {
        val hsv = colorToHsv(c)
        val h = hsv[0]
        return hsv[1] > 0.45f && hsv[2] > 0.55f && (h >= 290f || h < 70f)
    }

    /**
     * Hue (0..360), saturation and value (0..1) of an ARGB colour, computed like Android's
     * `Color.colorToHSV` (Skia's SkRGBToHSV).
     */
    fun colorToHsv(argb: Int): FloatArray {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val v = max / 255f
        if (max == min) return floatArrayOf(0f, 0f, v)
        val delta = (max - min).toFloat()
        val s = delta / max
        var h = when (max) {
            r -> (g - b) / delta
            g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f
        if (h < 0f) h += 360f
        return floatArrayOf(h, s, v)
    }
}
