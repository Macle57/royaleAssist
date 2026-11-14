package com.macle.royaleassist.badge

import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.ocr.Box
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the clan badge next to the opponent's name looks like: a distance per badge (0 = identical,
 * the normalised squared difference of 16x16 colour samples of the inside of the shield).
 */
class BadgeReading(private val scores: FloatArray) {
    private val order = scores.indices.sortedBy { scores[it] }
    val bestScore: Float get() = scores[order.first()]

    /** The screen clearly shows a clan badge (else the reading says nothing about the clan). */
    val confident: Boolean get() = bestScore <= CONFIDENT

    /**
     * Badge ids that fit the screen: within a margin of the best one. Several badges look alike
     * (flags with the same colours), so this is usually a handful. Empty when not [confident].
     */
    val matchingIds: List<Int>
        get() = if (!confident) emptyList()
        else order.takeWhile { scores[it] <= min(MAX_MATCH, bestScore + max(MARGIN, bestScore)) }.map { BadgeData.ids[it] }

    fun score(badgeId: Int): Float? = BadgeData.ids.indexOf(badgeId).takeIf { it >= 0 }?.let { scores[it] }

    fun describe(): String = order.take(3).joinToString(", ") { "${BadgeData.names[it]} ${fmt(scores[it])}" } +
        if (confident) "; matches ${matchingIds.size}" else "; not confident"

    companion object {
        const val CONFIDENT = 0.06f
        const val MARGIN = 0.02f
        const val MAX_MATCH = 0.12f
        private fun fmt(v: Float) = ((v * 1000).toInt() / 1000.0).toString()
    }
}

/**
 * Finds the clan badge left of the opponent's name and compares it with every known badge.
 *
 * The game draws the badge at a fixed size and place relative to the name: about 1.43 name heights
 * tall, its right edge ~0.15 name heights left of the name, its top ~0.14 name heights below the
 * name's top (measured on phone frames). A small grid of sizes and offsets around that absorbs OCR
 * box jitter. Only the inside of the shield is compared: the frame's colour follows the clan's war
 * league in game, while the symbol and background are fixed per badge.
 */
object BadgeMatcher {
    private val HEIGHTS = floatArrayOf(1.30f, 1.38f, 1.46f, 1.54f, 1.62f)
    private val GAPS = FloatArray(7) { it * 0.05f }               // 0 .. 0.30
    private val TOPS = FloatArray(9) { -0.05f + it * 0.05f }      // -0.05 .. 0.35

    private const val N = BadgeData.SIZE
    private const val CELLS = N * N * 3

    private val norms: FloatArray by lazy {
        val d = BadgeData.descriptors
        FloatArray(BadgeData.ids.size) { b ->
            var s = 0f
            for (k in 0 until CELLS) { val v = (d[b * CELLS + k].toInt() and 0xFF).toFloat(); s += v * v }
            s
        }
    }

    /** Null when the name sits too close to the left edge for a badge to fit. */
    fun read(img: ArgbImage, name: Box): BadgeReading? {
        val nh = name.height.toFloat()
        if (nh < 6f) return null
        val d = BadgeData.descriptors
        val count = BadgeData.ids.size
        val best = FloatArray(count) { Float.MAX_VALUE }
        val sample = FloatArray(CELLS)
        var any = false
        for (k in HEIGHTS) {
            val h = nh * k
            val w = h * BadgeData.ASPECT
            for (g in GAPS) {
                val right = name.left - g * nh
                val left = right - w
                if (left < 0f) continue
                for (t in TOPS) {
                    val top = name.top + t * nh
                    if (top < 0f || top + h > img.height) continue
                    sample(
                        img, sample,
                        left + w * BadgeData.INNER_LEFT, top + h * BadgeData.INNER_TOP,
                        left + w * BadgeData.INNER_RIGHT, top + h * BadgeData.INNER_BOTTOM,
                    )
                    var sn = 0f
                    for (v in sample) sn += v * v
                    if (sn <= 0f) continue
                    any = true
                    for (b in 0 until count) {
                        var diff = 0f
                        val base = b * CELLS
                        for (c in 0 until CELLS) {
                            val e = (d[base + c].toInt() and 0xFF) - sample[c]
                            diff += e * e
                        }
                        val score = diff / sqrt(norms[b] * sn)
                        if (score < best[b]) best[b] = score
                    }
                }
            }
        }
        return if (any) BadgeReading(best) else null
    }

    /** Box-averaged N x N RGB cells over [x0, x1) x [y0, y1), the same cell bounds as the generator. */
    internal fun sample(img: ArgbImage, out: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float) {
        for (j in 0 until N) {
            val ya = (y0 + (y1 - y0) * j / N).toInt().coerceIn(0, img.height - 1)
            val yb = max(ya + 1, (y0 + (y1 - y0) * (j + 1) / N).toInt()).coerceAtMost(img.height)
            for (i in 0 until N) {
                val xa = (x0 + (x1 - x0) * i / N).toInt().coerceIn(0, img.width - 1)
                val xb = max(xa + 1, (x0 + (x1 - x0) * (i + 1) / N).toInt()).coerceAtMost(img.width)
                var r = 0L; var g = 0L; var b = 0L
                for (y in ya until yb) {
                    val row = y * img.width
                    for (x in xa until xb) {
                        val p = img.pixels[row + x]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                    }
                }
                val n = ((yb - ya) * (xb - xa)).toFloat()
                val o = (j * N + i) * 3
                out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n
            }
        }
    }

    fun idForName(name: String): Int? = BadgeData.names.indexOfFirst { it.equals(name, ignoreCase = true) }
        .takeIf { it >= 0 }?.let { BadgeData.ids[it] }

    fun nameOf(id: Int): String? = BadgeData.ids.indexOf(id).takeIf { it >= 0 }?.let { BadgeData.names[it] }
}
