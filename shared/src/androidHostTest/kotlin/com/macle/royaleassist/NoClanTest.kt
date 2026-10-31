package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.ClanText
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "No Clan" in the clan slot, in the UI languages of the corpus. */
class NoClanTest {
    private fun l(text: String, l: Int, t: Int, r: Int, b: Int, w: Double = 0.0) = OcrLine(text, Box(l, t, r, b), 0.8f, "latin", warmth = w)

    @Test
    fun placeholdersInFiveLanguages() {
        listOf("No Clan", "클랜 없음", "無部落", "Klan yok", "クランなし", "NO CLAN", "No Clan.", "클랜없음").forEach {
            assertTrue(it, ClanText.isNoClanPlaceholder(it))
        }
        listOf("Nova Clan", "Klan: #GORGQPCJ", "Clan #GVGVVGUO", "GamingHouse", "Royale Br", "", "aaa").forEach {
            assertFalse(it, ClanText.isNoClanPlaceholder(it))
        }
    }

    @Test
    fun placeholderMeansNoClanAndIsNeverAName() {
        // vaPj7O5QoEY_80: gold name, "No Clan", then the trophy count 9403.
        val p = NamePicker.pick(
            listOf(
                l("glopezzz", 200, 170, 420, 215, 0.4),
                l("No Clan", 205, 222, 330, 250),
                l("9403", 205, 258, 290, 286),
            )
        )
        assertEquals("glopezzz", p.name?.text)
        assertNull(p.clan)
        // A lone placeholder is not picked as the name.
        assertNull(NamePicker.pick(listOf(l("클랜 없음", 100, 100, 300, 160))).name)
    }

    @Test
    fun trophyLineUnderClanIsIgnored() {
        // Frames that show trophies (e.g. "6040") instead of "League N" under the clan.
        val p = NamePicker.pick(
            listOf(
                l("HepPazarCR", 205, 222, 400, 250),
                l("tokatçı yiğit", 200, 170, 520, 215, 0.3),
                l("9987", 205, 258, 290, 286, 0.0),
            )
        )
        assertEquals("tokatçı yiğit", p.name?.text)
        assertEquals("HepPazarCR", p.clan?.text)
    }

    @Test
    fun lookupDropsPlaceholderClan() {
        // isPlausibleText still accepts them; the chain filters them separately.
        assertTrue(LookupChain.isPlausibleText("No Clan"))
        assertTrue(ClanText.isNoClanPlaceholder(LookupChain.cleanOcrName("  No Clan ")))
    }
}

/** Search variants for symbol-heavy and mixed-script corpus names. */
class SymbolSearchTest {
    @Test
    fun symbolsBecomeSpacesAndScriptsSplit() {
        assertEquals(listOf("TT Lord Diego"), LookupChain.symbolVariants("TT: Lord⚡Diego"))
        assertEquals(listOf("Zodiac 是传奇"), LookupChain.symbolVariants("Zodiac™ 是传奇"))
        assertEquals(listOf("Frontier 白影"), LookupChain.symbolVariants("Frontier✨白影"))
        assertEquals(listOf("Void 零"), LookupChain.symbolVariants("Void零"))
        assertTrue(LookupChain.symbolVariants("Mauricio").isEmpty())
        assertTrue(LookupChain.symbolVariants("4858 ✨").isEmpty())
    }

    @Test
    fun mixedNamesGetPrefixes() {
        assertEquals(listOf("Zodiac是传", "Zodia", "Zod"), LookupChain.progressivePrefixes("Zodiac™ 是传奇"))
    }
}

/** Picker cases recorded from the corpus run (lines and warmth as measured on the emulator). */
class CorpusPickerTest {
    private fun l(text: String, b: IntArray, c: Float, w: Double, src: String = "latin") =
        OcrLine(text, Box(b[0], b[1], b[2], b[3]), c, src, warmth = w)

    @Test
    fun badgeMisreadByDevanagariDoesNotBeatChineseName() {
        // P5t5pCjb_oE_555: the gold "16" badge read as 'आह=' (0.15) was warmer than the name.
        val p = NamePicker.pick(
            listOf(
                l("天上星星不说话", intArrayOf(82, 142, 353, 173), 0.64f, 0.35, "chinese"),
                l("6322", intArrayOf(417, 158, 495, 186), 0.83f, 0.07),
                l("आह=", intArrayOf(337, 166, 399, 209), 0.15f, 0.57, "devanagari"),
                l("对线生活", intArrayOf(82, 192, 178, 215), 0.77f, 0.0, "chinese"),
                l("2409", intArrayOf(82, 234, 154, 256), 0.88f, 0.0),
            )
        )
        assertEquals("天上星星不说话", p.name?.text)
        assertEquals("对线生活", p.clan?.text)
    }

    @Test
    fun slightlyTallerClanAndClanWithDigits() {
        // 8d_XyUB0_i0_545: clan line 44 px under a 39 px name; TV5D2rbYvDI_65: clan "CRL2026".
        val a = NamePicker.pick(
            listOf(
                l("BU 14 AMaRw", intArrayOf(125, 142, 428, 181), 0.64f, 0.33),
                l("Coaching", intArrayOf(100, 197, 245, 241), 0.91f, 0.0),
                l("2346", intArrayOf(110, 252, 186, 276), 0.89f, 0.0),
            )
        )
        assertEquals("Coaching", a.clan?.text)
        val b = NamePicker.pick(
            listOf(
                l("むぎったん", intArrayOf(80, 9, 246, 49), 0.45f, 0.15, "japanese"),
                l("CRL2026", intArrayOf(85, 63, 213, 88), 0.86f, 0.0),
                l("3350", intArrayOf(86, 106, 160, 129), 0.92f, 0.0),
            )
        )
        assertEquals("むぎったん", b.name?.text)
        assertEquals("CRL2026", b.clan?.text)
    }
}
