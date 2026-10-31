package com.macle.royaleassist.lookup

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Fuzzy string helpers tuned for comparing OCR output with real player/clan names. */
object StringSim {
    /**
     * CJK characters that OCR returns for look-alike Hangul jamo (a clan "ㅅㅁㅂ" was read as
     * "人口日" by the Japanese model). Folded on both sides of a comparison, so it is symmetric.
     */
    private val JAMO_HOMOGLYPHS = mapOf(
        '人' to 'ㅅ', '口' to 'ㅁ', 'ロ' to 'ㅁ', '日' to 'ㅂ', '己' to 'ㄹ', '〇' to 'ㅇ', '匚' to 'ㄷ',
    )

    /**
     * Lowercase, drop decorations (stars, skulls, quotes, trademark signs, emoji, punctuation),
     * fold characters OCR commonly confuses, and decompose Hangul into jamo (NFKD), so "MasterX™<"
     * equals "masterx" and 대/태 or 나/니 differ by one jamo instead of a whole syllable.
     * Whitespace is kept as single spaces (see [compact]).
     */
    fun normalize(s: String): String {
        // Drop trademark-style symbols first: NFKD would expand ™ to "TM".
        val folded = s.replace(Regex("[™®©℠]"), "").map { JAMO_HOMOGLYPHS[it] ?: it }.joinToString("")
        val n = Normalizer.normalize(folded, Normalizer.Form.NFKD).lowercase()
        val sb = StringBuilder()
        for (ch in n) {
            val c = when (ch) {
                '0' -> 'o'
                '1', '|', 'i', '!', 'ı' -> 'l'
                '5' -> 's'
                'θ', 'Θ', 'ø' -> 'o'
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c)
            else if (c.isWhitespace() && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    /** [normalize] without spaces: OCR inserts or drops spaces, names often have none. */
    fun compact(s: String): String = normalize(s).replace(" ", "")

    /** True when the string contains Hangul (syllables or jamo). */
    fun hasHangul(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }

    /** Letters/digits only, original case and composed syllables (used to build search queries). */
    fun searchable(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).filter { it.isLetterOrDigit() }

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

    /**
     * Clan similarity: 1.0 only for normalised equality; a clan that is a prefix or part of the
     * other ("tehran" vs "Tehran021") is at most 0.6, otherwise the usual similarity capped at
     * 0.94, so only an exact clan can make a strong match on its own.
     */
    fun clanSimilarity(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.0
        val na = compact(a)
        val nb = compact(b)
        if (na.isEmpty() || nb.isEmpty()) return if (a.trim() == b.trim() && a.isNotBlank()) 1.0 else 0.0
        if (na == nb) return 1.0
        val s = similarity(a, b)
        if (na.contains(nb) || nb.contains(na)) return minOf(s, 0.6)
        return minOf(s, 0.94)
    }

    /**
     * Similarity in [0,1] of two raw strings after normalisation (mean of Jaro-Winkler and
     * Levenshtein over [compact] forms, i.e. ignoring spaces and decorations; Hangul by jamo).
     */
    fun similarity(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.0
        val na = compact(a)
        val nb = compact(b)
        if (na.isEmpty() || nb.isEmpty()) {
            // Non-letter names (rare) – compare raw.
            return if (a.trim() == b.trim()) 1.0 else 0.0
        }
        return (jaroWinkler(na, nb) + levenshteinRatio(na, nb)) / 2.0
    }
}
