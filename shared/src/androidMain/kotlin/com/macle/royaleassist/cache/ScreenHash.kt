package com.macle.royaleassist.cache

import com.macle.royaleassist.ocr.Box

/**
 * Perceptual fingerprint of the opponent's name (and clan) exactly as drawn on screen, used to
 * answer a repeated tap on the same screen without OCR or network.
 *
 * A hash of the whole scan region does not work: in the same arena the background is identical
 * for every opponent and a troop walking past changes more pixels than a different name does
 * (phone scans 2026-10-28). So the first scan of a screen records where OCR found the name and
 * clan, and a later capture is compared on exactly those boxes: a 32x8 difference hash (dHash,
 * 256 bits) of each padded box, from an area-averaged grayscale thumbnail. Each bit also has a
 * "sure" flag: neighbouring cells that differ by less than [SURE_DELTA] gray levels (flat
 * background) flip on the slightest noise, so only sure bits of the stored hash are compared.
 *
 * Measured (ScreenHashTest): repeated phone captures of one screen differ in 0 sure bits (0-1
 * with added noise); a different opponent cut at the same boxes in 61+ (phone, same arena) and
 * 45+ (66 corpus frames, all pairs), so the limit [MAX_DISTANCE] sits far from both.
 */
object ScreenHash {
    const val W = 32
    const val H = 8
    const val BITS = W * H
    private const val WORDS = BITS / 64

    /** Cell pairs closer than this (gray levels) are not compared ("unsure" bits). */
    const val SURE_DELTA = 3.0

    /** Same screen when at most this many of the [BITS] bits differ (name and clan each). */
    const val MAX_DISTANCE = 20

    /** Crops with less gray-level spread than this are featureless and never stored or matched. */
    const val MIN_SPREAD = 24

    /** The text box grown by a quarter of its height on every side, clamped to the frame. */
    fun pad(b: Box, frameWidth: Int, frameHeight: Int): Box {
        val p = (b.height / 4).coerceAtLeast(1)
        return Box(
            (b.left - p).coerceIn(0, frameWidth - 1), (b.top - p).coerceIn(0, frameHeight - 1),
            (b.right + p).coerceIn(1, frameWidth), (b.bottom + p).coerceIn(1, frameHeight),
        )
    }

    /** Rec. 601 luma of an ARGB pixel, 0..255. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * dHash of a [w] x [h] block of ARGB pixels (row-major): area-average to (W+1) x H gray cells,
     * one bit per horizontally adjacent pair (left brighter than right), followed by the same
     * number of "sure" flags ([SURE_DELTA]). Null when the block is too small or featureless.
     */
    fun dHash(argb: IntArray, w: Int, h: Int): LongArray? {
        if (w < 8 || h < 4 || argb.size < w * h) return null
        val cw = W + 1
        val sum = LongArray(cw * H)
        val cnt = IntArray(cw * H)
        var min = 255
        var max = 0
        for (y in 0 until h) {
            val cy = (y * H / h).coerceAtMost(H - 1)
            val row = y * w
            for (x in 0 until w) {
                val v = luma(argb[row + x])
                if (v < min) min = v
                if (v > max) max = v
                val cx = (x * cw / w).coerceAtMost(cw - 1)
                sum[cy * cw + cx] += v.toLong()
                cnt[cy * cw + cx]++
            }
        }
        if (max - min < MIN_SPREAD) return null
        // Narrow blocks leave some cells empty: borrow the left neighbour (or the right one).
        val cell = DoubleArray(cw * H) { i -> if (cnt[i] > 0) sum[i].toDouble() / cnt[i] else Double.NaN }
        for (cy in 0 until H) for (cx in 0 until cw) {
            val i = cy * cw + cx
            if (cell[i].isNaN()) cell[i] = (if (cx > 0) cell[i - 1] else Double.NaN).takeIf { !it.isNaN() }
                ?: (cx + 1 until cw).map { cell[cy * cw + it] }.firstOrNull { !it.isNaN() } ?: 0.0
        }
        val out = LongArray(WORDS * 2)
        var bit = 0
        for (cy in 0 until H) for (cx in 0 until W) {
            val l = cell[cy * cw + cx]
            val r = cell[cy * cw + cx + 1]
            if (l > r) out[bit / 64] = out[bit / 64] or (1L shl (bit % 64))
            if (kotlin.math.abs(l - r) >= SURE_DELTA) out[WORDS + bit / 64] = out[WORDS + bit / 64] or (1L shl (bit % 64))
            bit++
        }
        return out
    }

    /** Bits of [stored] that are sure and differ in [probe] (only the stored side's flags count). */
    fun distance(stored: LongArray, probe: LongArray): Int {
        if (stored.size != WORDS * 2 || probe.size != WORDS * 2) return BITS
        var d = 0
        for (i in 0 until WORDS) d += java.lang.Long.bitCount((stored[i] xor probe[i]) and stored[WORDS + i])
        return d
    }

    /** Number of sure bits in [h]. */
    fun sureBits(h: LongArray): Int = (WORDS until minOf(h.size, WORDS * 2)).sumOf { java.lang.Long.bitCount(h[it]) }

    fun toHex(h: LongArray): String = h.joinToString("") { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') }

    fun fromHex(s: String): LongArray? = runCatching {
        if (s.length != WORDS * 2 * 16) return null
        LongArray(WORDS * 2) { i -> java.lang.Long.parseUnsignedLong(s.substring(i * 16, i * 16 + 16), 16) }
    }.getOrNull()

    /** A stored screen: frame size, the name/clan boxes OCR found and their hashes. */
    data class Key(
        val frameWidth: Int,
        val frameHeight: Int,
        val nameBox: Box,
        val nameHash: LongArray,
        val clanBox: Box?,
        val clanHash: LongArray?,
    )

    data class Match<T>(val item: T, val nameDistance: Int, val clanDistance: Int?)

    /**
     * The stored entry whose boxes, cut from the new frame by [hashAt], hash closest to what was
     * stored: same frame size, name (and clan, when one was stored) within [MAX_DISTANCE].
     */
    fun <T> bestMatch(entries: List<T>, key: (T) -> Key, frameWidth: Int, frameHeight: Int, hashAt: (Box) -> LongArray?): Match<T>? {
        var best: Match<T>? = null
        for (e in entries) {
            val k = key(e)
            if (k.frameWidth != frameWidth || k.frameHeight != frameHeight) continue
            val nh = hashAt(k.nameBox) ?: continue
            val nd = distance(nh, k.nameHash)
            if (nd > MAX_DISTANCE) continue
            var cd: Int? = null
            if (k.clanBox != null && k.clanHash != null) {
                val ch = hashAt(k.clanBox) ?: continue
                cd = distance(ch, k.clanHash)
                if (cd > MAX_DISTANCE) continue
            }
            val total = nd + (cd ?: 0)
            if (best == null || total < best.nameDistance + (best.clanDistance ?: 0)) best = Match(e, nd, cd)
        }
        return best
    }
}
