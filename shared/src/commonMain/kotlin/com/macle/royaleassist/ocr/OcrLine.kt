package com.macle.royaleassist.ocr

import com.macle.royaleassist.text.UScript
import com.macle.royaleassist.text.codePoints
import com.macle.royaleassist.text.isLetter

/** Plain box (not android.graphics.Rect, so the picking logic is unit-testable on the JVM). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.toLong() * height.toLong()
    val centerY get() = (top + bottom) / 2

    fun intersect(o: Box): Box? {
        val l = maxOf(left, o.left); val t = maxOf(top, o.top)
        val r = minOf(right, o.right); val b = minOf(bottom, o.bottom)
        return if (r > l && b > t) Box(l, t, r, b) else null
    }

    /** Intersection area over the smaller box area (1.0 = one box contains the other). */
    fun overlapRatio(o: Box): Double {
        val i = intersect(o) ?: return 0.0
        val minArea = minOf(area, o.area).coerceAtLeast(1)
        return i.area.toDouble() / minArea
    }

    fun scaled(f: Float) = Box((left * f).toInt(), (top * f).toInt(), (right * f).toInt(), (bottom * f).toInt())
    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/** One recognised text line. [source] is the recognizer ("latin", "chinese", ..., "tesseract-ara"). */
data class OcrLine(
    val text: String,
    val box: Box,
    val confidence: Float,
    val source: String,
    val language: String? = null,
    /** Fraction of saturated pink/orange pixels in the box (see ColorStats); 0 when not measured. */
    val warmth: Double = 0.0,
)

object Scripts {
    /** Dominant non-common script of a string (LATIN, HAN, ARABIC, CYRILLIC, ...), or null. */
    fun dominant(s: String): UScript? {
        // Insertion order: on a tie the script seen first wins (same on every platform).
        val counts = LinkedHashMap<UScript, Int>()
        codePoints(s).forEach { cp ->
            val sc = UScript.of(cp)
            if (sc != UScript.COMMON) {
                counts[sc] = (counts[sc] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Fraction of letters in [s] that belong to the script(s) a recognizer specialises in. */
    fun fitFraction(s: String, source0: String): Double {
        val source = source0.removeSuffix("+2nd")
        val wanted: Set<UScript> = when (source) {
            "chinese" -> setOf(UScript.HAN)
            "japanese" -> setOf(UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA)
            "korean" -> setOf(UScript.HANGUL, UScript.HAN)
            "devanagari" -> setOf(UScript.DEVANAGARI)
            "tesseract-ara" -> setOf(UScript.ARABIC)
            "tesseract-rus" -> setOf(UScript.CYRILLIC)
            else -> setOf(UScript.LATIN, UScript.GREEK)
        }
        var letters = 0
        var fit = 0
        codePoints(s).forEach { cp ->
            if (isLetter(cp)) {
                letters++
                if (UScript.of(cp) in wanted) fit++
            }
        }
        return if (letters == 0) 0.0 else fit.toDouble() / letters
    }

    /**
     * For CJK/kana/Hangul recognizers: a mixed reading such as "まるNoxi" or "Zodiac 是传奇" fits
     * when it has at least one character of the recognizer's script and the rest is Latin.
     */
    fun mixedFit(s: String, source0: String): Boolean {
        val source = source0.removeSuffix("+2nd")
        if (source !in setOf("chinese", "japanese", "korean")) return false
        val own = fitFraction(s, source)
        if (own <= 0.0) return false
        return own + fitFraction(s, "latin") >= 0.85
    }

    fun letterCount(s: String) = codePoints(s).count { isLetter(it) }
}
