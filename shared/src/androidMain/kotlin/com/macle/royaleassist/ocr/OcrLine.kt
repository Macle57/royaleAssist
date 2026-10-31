package com.macle.royaleassist.ocr

import java.lang.Character.UnicodeScript

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
    fun dominant(s: String): UnicodeScript? {
        val counts = HashMap<UnicodeScript, Int>()
        s.codePoints().forEach { cp ->
            val sc = UnicodeScript.of(cp)
            if (sc != UnicodeScript.COMMON && sc != UnicodeScript.INHERITED && sc != UnicodeScript.UNKNOWN) {
                counts[sc] = (counts[sc] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Fraction of letters in [s] that belong to the script(s) a recognizer specialises in. */
    fun fitFraction(s: String, source0: String): Double {
        val source = source0.removeSuffix("+2nd")
        val wanted: Set<UnicodeScript> = when (source) {
            "chinese" -> setOf(UnicodeScript.HAN)
            "japanese" -> setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA)
            "korean" -> setOf(UnicodeScript.HANGUL, UnicodeScript.HAN)
            "devanagari" -> setOf(UnicodeScript.DEVANAGARI)
            "tesseract-ara" -> setOf(UnicodeScript.ARABIC)
            "tesseract-rus" -> setOf(UnicodeScript.CYRILLIC)
            else -> setOf(UnicodeScript.LATIN, UnicodeScript.GREEK)
        }
        var letters = 0
        var fit = 0
        s.codePoints().forEach { cp ->
            if (Character.isLetter(cp)) {
                letters++
                if (UnicodeScript.of(cp) in wanted) fit++
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

    fun letterCount(s: String) = s.codePoints().filter { Character.isLetter(it) }.count().toInt()
}
