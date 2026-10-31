package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.StringSim
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** Cases from the phone's saved scans (ra_scans, 2026-10-28). */
class PhoneScansTest {
    private fun l(t: String, b: IntArray, w: Double = 0.0) = OcrLine(t, Box(b[0], b[1], b[2], b[3]), 0.8f, "latin", warmth = w)

    /** A: 20261028-153938-360, "⚽JP⚽" read as "JP" starting 71 px right of the clan. */
    @Test
    fun emojiDecoratedNameIsDetectedAndComparesEqual() {
        val p = NamePicker.pick(
            listOf(
                l("N22", intArrayOf(446, 84, 510, 104)),
                l("JP", intArrayOf(172, 115, 233, 170), 0.39),
                l("BOLIVIA", intArrayOf(101, 179, 232, 205)),
                l("YLeague 5", intArrayOf(30, 224, 245, 267), 0.25),
            )
        )
        assertEquals("JP", p.name?.text)
        assertEquals("BOLIVIA", p.clan?.text)
        assertTrue(p.decorated)
        assertTrue(p.hint().decorated)
        assertEquals(5, p.league)
        // Emoji, variation selectors and ZWJ never count when comparing names.
        assertEquals(1.0, StringSim.similarity("⚽JP⚽", "JP"), 1e-9)
        assertEquals(1.0, StringSim.similarity("⚽️JP⚽️", "jp"), 1e-9)
        assertEquals(1.0, StringSim.similarity("👨‍👩‍👧Fam", "Fam"), 1e-9)
        // A normal name that starts where the clan starts is not decorated.
        val q = NamePicker.pick(listOf(l("Daniel", intArrayOf(86, 96, 234, 142), 0.47), l("GLI ARGONAUTI", intArrayOf(100, 151, 302, 181))))
        assertFalse(q.decorated)
    }

    @Test
    fun clanCardsRankExactNameFirstThenSize() {
        val cards = listOf(
            RoyaleApiWeb.ClanCard("A", "Bolivia Kings", 50, 90000),
            RoyaleApiWeb.ClanCard("B", "BOLIVIA", 12, 30000),
            RoyaleApiWeb.ClanCard("C", "bolivia", 48, 70000),
            RoyaleApiWeb.ClanCard("D", "BOLIVIA", 49, 80000),
        )
        assertEquals(listOf("D", "B", "C"), LookupChain.rankClanCards(cards, "BOLIVIA").map { it.first })
    }

    /** B: 20261028-154709-098, "tehran" is not "Tehran021". */
    @Test
    fun partialClanIsNotAStrongMatch() {
        assertEquals(1.0, StringSim.clanSimilarity("Tehran021", "tehran021"), 1e-9)
        assertTrue(StringSim.clanSimilarity("tehran", "Tehran021") <= 0.6)
        val wrong = LookupChain.score(PlayerHit("hamidreza", "29U8889V", "tehran", "X"), "HaMidReza", "Tehran021")
        val right = LookupChain.score(PlayerHit("hamidreza", "20GUR9UUV", "Tehran021", "Y"), "HaMidReza", "Tehran021")
        assertFalse(LookupChain.isStrong(wrong, "Tehran021", noClan = false, nameUnique = false))
        assertFalse(LookupChain.isStrong(wrong, "Tehran021", noClan = false, nameUnique = true))
        assertTrue(LookupChain.isStrong(right, "Tehran021", noClan = false, nameUnique = false))
        assertTrue(right.score > wrong.score)
        // OCR-noisy clan (0.75..0.95) only counts when nobody else has the name.
        val noisy = LookupChain.score(PlayerHit("x", "Z", "Off The Grid.", "Q"), "x", "OPF The Grid.")
        assertTrue(LookupChain.isStrong(noisy, "OPF The Grid.", noClan = false, nameUnique = true))
        assertFalse(LookupChain.isStrong(noisy, "OPF The Grid.", noClan = false, nameUnique = false))
    }

    /** C: 20261028-1543xx, "williammelo10" read as "williaMMelolo" / "williamMelolD". */
    @Test
    fun smallCapsTrailingDigits() {
        assertTrue("williaMMelo10" in LookupChain.trailingDigitVariants("williaMMelolo"))
        assertTrue("williamMelo10" in LookupChain.trailingDigitVariants("williamMelolD"))
        assertTrue("williaMMelo10" in LookupChain.ocrVariants("williaMMelolo").take(2))
        assertTrue(LookupChain.trailingDigitVariants("Macle").isEmpty())
        assertTrue(StringSim.similarity("williaMMelolo", "williammelo10") >= 0.95)
    }
}

class CaseVariantsTest {
    @Test
    fun everyLetterCaseIsSearched() {
        assertEquals(listOf("mbg", "MBG"), LookupChain.caseVariants("Mbg"))
        assertEquals(listOf("MBG", "Mbg"), LookupChain.caseVariants("mbg"))
    }
}
