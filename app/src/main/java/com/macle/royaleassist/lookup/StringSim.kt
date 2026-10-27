package com.macle.royaleassist.lookup

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Fuzzy string helpers tuned for comparing OCR output with real player/clan names. */
object StringSim {
    /**
     * Lowercase, NFKC, drop decorations (stars, quotes, trademark signs, punctuation) and fold
     * characters OCR commonly confuses, so "MasterX™<" and "masterx" compare equal.
     */
    fun normalize(s: String): String {
        // Drop trademark-style symbols first: NFKC would expand ™ to "TM".
        val n = Normalizer.normalize(s.replace(Regex("[™®©℠]"), ""), Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder()
        for (ch in n) {
            val c = when (ch) {
                '0' -> 'o'
                '1', '|', 'i', '!' -> 'l'
                '5' -> 's'
                'θ', 'Θ', 'ø', 'ö', 'ó', 'ò', 'ô', 'õ' -> 'o'
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c)
            else if (c.isWhitespace() && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val range = max(0, max(a.length, b.length) / 2 - 1)
        val aM = BooleanArray(a.length)
        val bM = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = max(0, i - range)
            val hi = min(b.length - 1, i + range)
            for (j in lo..hi) {
                if (!bM[j] && a[i] == b[j]) { aM[i] = true; bM[j] = true; matches++; break }
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!aM[i]) continue
            while (!bM[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    /** Similarity in [0,1] of two raw strings after normalisation (mean of Jaro-Winkler and Levenshtein). */
    fun similarity(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.0
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) {
            // Non-letter names (rare) – compare raw.
            return if (a.trim() == b.trim()) 1.0 else 0.0
        }
        return (jaroWinkler(na, nb) + levenshteinRatio(na, nb)) / 2.0
    }
}
