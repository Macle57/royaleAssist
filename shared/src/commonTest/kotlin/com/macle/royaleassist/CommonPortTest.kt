package com.macle.royaleassist

import com.macle.royaleassist.cache.ScreenHash
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.ocr.ColorStats
import com.macle.royaleassist.ocr.Preprocess
import com.macle.royaleassist.ocr.Scripts
import com.macle.royaleassist.text.UScript
import com.macle.royaleassist.text.codePoints
import com.macle.royaleassist.text.fmt2
import com.macle.royaleassist.text.normalizeNfc
import com.macle.royaleassist.text.normalizeNfkd
import com.macle.royaleassist.util.ConcurrentMap
import com.macle.royaleassist.util.formUrlEncode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The replacements for JVM-only APIs behave like the originals on every platform. */
class CommonPortTest {
    @Test
    fun formEncodingMatchesUrlEncoder() {
        assertEquals("Kings+%26+Queens", formUrlEncode("Kings & Queens"))
        assertEquals("a.b-c*d_e09AZ", formUrlEncode("a.b-c*d_e09AZ"))
        assertEquals("%EB%8F%99%EB%8D%95+%E2%98%A0", formUrlEncode("동덕 ☠"))
        assertEquals("%23TAG%2F%3F%3D%7E", formUrlEncode("#TAG/?=~"))
        assertEquals(
            "https://royaleapi.com/player/search/results?q=Royale+King&exact_match=on",
            RoyaleApiWeb.searchUrl("Royale King", exact = true),
        )
        assertEquals("https://royaleapi.com/clans/search?name=Eternal+Night", RoyaleApiWeb.clanSearchUrl("Eternal Night"))
    }

    @Test
    fun apiTimeParses() {
        assertEquals(1761561912L, BattleLog.parseApiTime("20251027T104512.000Z"))
        assertEquals(0L, BattleLog.parseApiTime("19700101T000000.999Z"))
        assertNull(BattleLog.parseApiTime("not a time"))
        assertNull(BattleLog.parseApiTime(null))
    }

    @Test
    fun twoDecimals() {
        assertEquals("0.85", fmt2(0.849))
        assertEquals("0.13", fmt2(0.125))
        assertEquals("1.00", fmt2(1.0))
        assertEquals("-0.50", fmt2(-0.5))
        assertEquals("0.00", fmt2(-0.001))
        assertEquals("12.30", fmt2(12.3f))
    }

    @Test
    fun scripts() {
        assertEquals(UScript.LATIN, UScript.of('a'))
        assertEquals(UScript.GREEK, UScript.of('θ'))
        assertEquals(UScript.CYRILLIC, UScript.of('Ж'))
        assertEquals(UScript.ARABIC, UScript.of('ي'))
        assertEquals(UScript.DEVANAGARI, UScript.of('ह'))
        assertEquals(UScript.HAN, UScript.of('中'))
        assertEquals(UScript.HIRAGANA, UScript.of('あ'))
        assertEquals(UScript.KATAKANA, UScript.of('ア'))
        assertEquals(UScript.HANGUL, UScript.of('한'))
        assertEquals(UScript.HANGUL, UScript.of('ㅅ'))
        // Prolonged sound mark and digits/symbols belong to no script.
        assertEquals(UScript.COMMON, UScript.of('ー'))
        assertEquals(UScript.COMMON, UScript.of('7'))
        assertEquals(UScript.COMMON, UScript.of('★'))
        assertEquals(UScript.OTHER, UScript.of('ก'))
        assertEquals(UScript.KATAKANA, Scripts.dominant("ラーメン"))
        assertEquals(UScript.HAN, UScript.of(0x20000))
        assertEquals(2, Scripts.letterCount("𠀀a1"))
    }

    @Test
    fun codePointsCombineSurrogates() {
        assertContentEquals(intArrayOf('a'.code, 0x1F600, 'b'.code), codePoints("a😀b"))
        assertContentEquals(intArrayOf(0xD83D), codePoints("\uD83D"))
    }

    @Test
    fun normalization() {
        assertEquals("TM", normalizeNfkd("™"))
        // 대 = ㄷ + ㅐ as conjoining jamo.
        assertEquals(2, normalizeNfkd("대").length)
        assertEquals("대", normalizeNfc(normalizeNfkd("대")))
        assertEquals("é", normalizeNfc("é"))
    }

    @Test
    fun screenHashHexRoundTrip() {
        val h = LongArray(8) { i -> if (i % 2 == 0) -1L - i else Long.MIN_VALUE + i }
        val hex = ScreenHash.toHex(h)
        assertEquals(8 * 16, hex.length)
        assertEquals("ffffffffffffffff", hex.take(16))
        assertEquals("8000000000000001", hex.substring(16, 32))
        assertContentEquals(h, ScreenHash.fromHex(hex))
        assertNull(ScreenHash.fromHex("xyz"))
        assertEquals(64 * 4, ScreenHash.sureBits(LongArray(8) { if (it < 4) 0L else -1L }))
    }

    @Test
    fun bilinearScale() {
        val img = ArgbImage(2, 1, intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()))
        val up = Preprocess.scaleBilinear(img, 4, 1)
        // Pixel centres at 0.25 steps: clamped, 1/4, 3/4, clamped.
        assertContentEquals(intArrayOf(0xff000000.toInt(), 0xff404040.toInt(), 0xffbfbfbf.toInt(), 0xffffffff.toInt()), up.pixels)
        val flat = ArgbImage(3, 3, IntArray(9) { 0xff336699.toInt() })
        assertEquals(setOf(0xff336699.toInt()), Preprocess.scaleBilinear(flat, 7, 5).pixels.toSet())
    }

    @Test
    fun grayContrastAndBinarize() {
        val img = ArgbImage(2, 1, intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()))
        val (gray, s) = Preprocess.apply(img, com.macle.royaleassist.ocr.Prep.UPSCALE_GRAY, forcedScale = 1f)
        assertEquals(1f, s)
        assertEquals(0xff363636.toInt(), gray.pixels[0]) // 0.213 * 255 = 54.3
        assertEquals(0xffb6b6b6.toInt(), gray.pixels[1]) // 0.715 * 255 = 182.3
        val contrast = Preprocess.colorFilter(ArgbImage(1, 1, intArrayOf(0xff8040c0.toInt())), gray = false, contrast = 1.6f)
        // (v - 128) * 1.6 + 128: 128 -> 128, 64 -> 25.6, 192 -> 230.4
        assertEquals(0xff801ae6.toInt(), contrast.pixels[0])
        val bin = Preprocess.binarize(ArgbImage(2, 1, intArrayOf(0xffffffff.toInt(), 0xff101010.toInt())), 150, invert = false)
        assertContentEquals(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()), bin.pixels)
    }

    @Test
    fun hsvLikeAndroid() {
        assertContentEquals(floatArrayOf(0f, 1f, 1f), ColorStats.colorToHsv(0xffff0000.toInt()))
        assertContentEquals(floatArrayOf(300f, 1f, 1f), ColorStats.colorToHsv(0xffff00ff.toInt()))
        assertContentEquals(floatArrayOf(0f, 0f, 0.5019608f), ColorStats.colorToHsv(0xff808080.toInt()))
        val warm = ArgbImage(4, 4, IntArray(16) { if (it < 8) 0xffff40a0.toInt() else 0xffffffff.toInt() })
        assertEquals(0.5, ColorStats.warmFraction(warm, com.macle.royaleassist.ocr.Box(0, 0, 4, 4)), 1e-9)
    }

    @Test
    fun imageCrop() {
        val img = ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val c = img.crop(1, 0, 2, 2)
        assertContentEquals(intArrayOf(2, 3, 5, 6), c.pixels)
        assertEquals(6, img.getPixel(2, 1))
    }

    @Test
    fun concurrentMap() {
        val m = ConcurrentMap<String, Int>()
        assertNull(m["a"])
        m["a"] = 1
        m["a"] = 2
        m["b"] = 3
        assertEquals(mapOf("a" to 2, "b" to 3), m.snapshot())
    }
}
