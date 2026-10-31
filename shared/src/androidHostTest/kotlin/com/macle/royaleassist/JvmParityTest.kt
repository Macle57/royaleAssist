package com.macle.royaleassist

import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.text.UScript
import com.macle.royaleassist.text.isLetter
import com.macle.royaleassist.util.formUrlEncode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The common replacements against the Java APIs the Android build used before (JVM only). */
class JvmParityTest {
    private val listed = mapOf(
        Character.UnicodeScript.LATIN to UScript.LATIN,
        Character.UnicodeScript.GREEK to UScript.GREEK,
        Character.UnicodeScript.CYRILLIC to UScript.CYRILLIC,
        Character.UnicodeScript.ARABIC to UScript.ARABIC,
        Character.UnicodeScript.DEVANAGARI to UScript.DEVANAGARI,
        Character.UnicodeScript.HAN to UScript.HAN,
        Character.UnicodeScript.HIRAGANA to UScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA to UScript.KATAKANA,
        Character.UnicodeScript.HANGUL to UScript.HANGUL,
    )

    @Test
    fun scriptTableMatchesJava() {
        var checked = 0
        for (cp in 0..0x10FFFF) {
            val java = Character.UnicodeScript.of(cp)
            val mine = UScript.of(cp)
            val expected = listed[java] ?: when (java) {
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> UScript.COMMON
                // Other scripts: their letters are OTHER; their digits and signs are not compared.
                else -> if (Character.isLetter(cp)) UScript.OTHER else continue
            }
            // Above the BMP letters are approximated by block, so only the listed scripts must match there.
            if (cp >= 0x10000 && expected !in listed.values && mine !in listed.values) continue
            assertEquals("U+%04X (%s)".format(cp, java), expected, mine)
            checked++
        }
        assert(checked > 60_000) { "only $checked code points compared" }
    }

    @Test
    fun letterTestMatchesJavaOnTheBmp() {
        for (cp in 0..0xFFFF) assertEquals("U+%04X".format(cp), Character.isLetter(cp), isLetter(cp))
    }

    @Test
    fun formEncodingMatchesUrlEncoder() {
        val samples = listOf(
            "Royale King", "Kings & Queens", "동덕여대 여자화장실", "天上星星不说话", "tokatçı yiğit", "a+b=c?d/e#f%g",
            "~!@\$^()[]{}|\\;:'\",<>", "✨Macle✨", "😀 emoji", "", " ", "a.b-c*d_e",
        )
        for (s in samples) assertEquals(s, URLEncoder.encode(s, "UTF-8"), formUrlEncode(s))
    }

    @Test
    fun apiTimeMatchesSimpleDateFormat() {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        for (s in listOf("20261027T104512.000Z", "20240229T235959.999Z", "20000101T000000.000Z", "20261231T120000.500Z")) {
            assertEquals(s, f.parse(s)!!.time / 1000, BattleLog.parseApiTime(s))
        }
    }
}
