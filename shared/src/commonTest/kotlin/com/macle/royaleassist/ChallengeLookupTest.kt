package com.macle.royaleassist

import com.macle.royaleassist.badge.BadgeMatcher
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupChain.Companion.ClanRef
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.ScreenHint
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.LineReading
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.pipeline.Analyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classic challenges of 2026-11-13 (phone log): both names were read right, but the lookup picked
 * namesakes. "ACE" / "iran": 348+ clans are named "iran" and the real one (#GQR2QPLY, badge
 * Flame_03 = 16000002) was 201st. "KeepCalm" / "Тцк": ML Kit's Latin model read the clan as "TIK".
 */
class ChallengeLookupTest {
    private fun ref(tag: String, badge: Int?) = ClanRef(tag, "iran", badge)

    @Test
    fun badgeIdsFromRoyaleApiImageUrls() {
        assertEquals(16000002, BadgeMatcher.idForName("Flame_03"))
        assertEquals(16000175, BadgeMatcher.idForName("flag_e_01"))
        assertEquals("Flame_03", BadgeMatcher.nameOf(16000002))
        assertEquals(
            BadgeMatcher.idForName("Twin_Peaks_01"),
            RoyaleApiWeb.badgeIdFromUrl("https://cdns3.royaleapi.com/cdn-cgi/image/w=64,h=64/static/img/badge-fs8/Twin_Peaks_01-fs8.png"),
        )
        assertEquals(
            BadgeMatcher.idForName("A_Char_King_02"),
            RoyaleApiWeb.badgeIdFromUrl("https://cdn.royaleapi.com/static/img/badge/gold-1/A_Char_King_02.png?t=1"),
        )
        assertEquals(null, RoyaleApiWeb.badgeIdFromUrl("https://cdn.royaleapi.com/static/img/ui/trophy.png"))
    }

    @Test
    fun clanSearchCardsCarryTheBadge() {
        val cards = RoyaleApiWeb.parseClanCards(readFixture("royaleapi/clan_search_eternal_night.html"))
        assertTrue(cards.isNotEmpty())
        assertEquals(BadgeMatcher.idForName("Twin_Peaks_01"), cards.first { it.tag == "RQUVRYYL" }.badgeId)
        assertTrue(cards.count { it.badgeId != null } >= cards.size - 2, "nearly every card shows its badge")
    }

    @Test
    fun withoutBadgeTheFirstClansAreRead() {
        val ranked = (1..10).map { ref("C$it", 16000000 + it) }
        assertEquals(ranked.take(6), LookupChain.pickClans(ranked, emptyList(), limit = 6, maxWithBadge = 20))
    }

    @Test
    fun clansWearingTheBadgeOnScreenComeFirstAndDeep() {
        // 300 clans named "iran"; the opponent's is #201 and two others share its badge.
        val ranked = (1..300).map { ref("C$it", if (it in setOf(40, 201, 250)) 16000002 else 16000100 + it % 50) }
        val picked = LookupChain.pickClans(ranked, listOf(16000002), limit = 6, maxWithBadge = 20)
        assertEquals(listOf("C40", "C201", "C250"), picked.take(3).map { it.tag })
        // The rest is filled up to the usual limit, unknown badges before other badges.
        assertEquals(6, picked.size)
        val withUnknown = listOf(ref("A", 16000005), ref("B", null), ref("C", 16000002))
        assertEquals(listOf("C", "B", "A"), LookupChain.pickClans(withUnknown, listOf(16000002), limit = 6, maxWithBadge = 20).map { it.tag })
    }

    @Test
    fun manyClansWithTheBadgeAreCapped() {
        val ranked = (1..40).map { ref("C$it", 16000002) }
        assertEquals(20, LookupChain.pickClans(ranked, listOf(16000002), limit = 6, maxWithBadge = 20).size)
    }

    @Test
    fun cyrillicClanReadingMatchesWhenTheLatinOneDoesNot() {
        val hit = PlayerHit("KeepCalm", "98QJR8UR9", "Тцк", "R8YUCYPL")
        val latinOnly = LookupChain.scoreAny(hit, "KeepcalM", "TIK", emptyList(), noClan = false)
        val withAlt = LookupChain.scoreAny(hit, "KeepcalM", "TIK", listOf("Тцк"), noClan = false)
        assertTrue(latinOnly.clanSim < 0.75, "Latin misread alone does not match: ${latinOnly.clanSim}")
        assertEquals(1.0, withAlt.clanSim, 1e-9)
        assertTrue(LookupChain.isStrong(withAlt, "TIK", noClan = false))
    }

    @Test
    fun weakLatinClanGetsACyrillicReRead() {
        fun line(t: String, c: Float) = OcrLine(t, Box(98, 179, 160, 210), c, "latin")
        assertTrue(Analyzer.clanNeedsOtherScript(line("TIK", 0.61f)))
        assertFalse(Analyzer.clanNeedsOtherScript(line("iran", 0.82f)))
        assertFalse(Analyzer.clanNeedsOtherScript(line("Боевые Коты", 0.3f)), "already Cyrillic")
        assertTrue(Analyzer.acceptClanAlternative(LineReading("Тцк", 71, "rus", "gray"), "TIK"))
        assertFalse(Analyzer.acceptClanAlternative(LineReading("TIK", 80, "rus", "gray"), "TIK"), "not Cyrillic")
        assertFalse(Analyzer.acceptClanAlternative(LineReading("Тцк", 20, "rus", "gray"), "TIK"), "too unsure")
        assertFalse(Analyzer.acceptClanAlternative(LineReading("ц", 90, "rus", "gray"), "TIK"), "one letter")
    }

    @Test
    fun hintCarriesAlternativesAndBadges() {
        val pick = NamePick(
            OcrLine("KeepcalM", Box(93, 111, 300, 171), 0.6f, "latin"),
            OcrLine("TIK", Box(98, 179, 160, 210), 0.61f, "latin"),
            clanAlternatives = listOf("Тцк"), badges = listOf(16000175),
        )
        val h: ScreenHint = pick.hint()
        assertEquals(listOf("Тцк"), h.clanAlternatives)
        assertEquals(listOf(16000175), h.badges)
        assertTrue(h.describe().contains("'Тцк'"))
    }
}
