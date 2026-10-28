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
