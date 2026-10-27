package com.macle.royaleassist.ocr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Colour of a text line. Clash Royale draws the opponent's name in saturated pink/magenta or
 * orange/yellow, and the clan, "League N", timers and HP in white or pale colours. Measured on the
 * samples: name lines 0.12-0.46 warm pixels, clan/label lines <= 0.02.
 */
object ColorStats {
    private val hsv = FloatArray(3)

    /** Fraction of pixels in [box] (in [bmp] coordinates) that are saturated red/pink/orange/yellow. */
    fun warmFraction(bmp: Bitmap, box: Box, maxSamples: Int = 4000): Double {
        val l = box.left.coerceIn(0, bmp.width - 1)
        val t = box.top.coerceIn(0, bmp.height - 1)
        val r = box.right.coerceIn(l + 1, bmp.width)
        val b = box.bottom.coerceIn(t + 1, bmp.height)
        val area = (r - l).toLong() * (b - t)
        val step = kotlin.math.sqrt(area.toDouble() / maxSamples).toInt().coerceAtLeast(1)
        var n = 0
        var warm = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                if (isWarm(bmp.getPixel(x, y))) warm++
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.0 else warm.toDouble() / n
    }

    private fun isWarm(c: Int): Boolean = synchronized(hsv) {
        Color.colorToHSV(c, hsv)
        val h = hsv[0]
        hsv[1] > 0.45f && hsv[2] > 0.55f && (h >= 290f || h < 70f)
    }
}
