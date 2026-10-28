package com.macle.royaleassist

import com.macle.royaleassist.cache.CachePolicy
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.ScreenHint
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone scan 20261028-153531: the opponent "mbg" (lowercase, small-caps font) showed "No Clan" and
 * "League 5". The old matcher took the case-sensitive exact hit "Mbg #CRQVGUQC (One Hit Wonders)".
 */
class ClanTriStateTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("royaleapi/$name")!!.readText()

    private val rows by lazy {
        (RoyaleApiWeb.parseSearch(fixture("search_exact_mbg.html")) + RoyaleApiWeb.parseSearch(fixture("search_fuzzy_mbg.html")))
            .distinctBy { it.tag }
    }

    @Test
    fun noClanRejectsTheClannedExactHit() {
        val wrong = rows.first { it.tag == "CRQVGUQC" }
        assertEquals("One Hit Wonders", wrong.clanName)
        val s = LookupChain.score(wrong, "Mbg", null, noClan = true)
        assertEquals(1.0, s.nameSim, 1e-9)
        assertEquals(0.0, s.clanSim, 1e-9)
        assertFalse(LookupChain.isStrong(s, null, noClan = true))
        // With the clan unknown it would still have been a strong match (old behaviour).
        assertTrue(LookupChain.isStrong(LookupChain.score(wrong, "Mbg", null), null))
    }

    @Test
    fun caseInsensitiveCandidatesWithoutClanWin() {
        val scored = rows.map { LookupChain.score(it, "Mbg", null, noClan = true) }
        val strong = scored.filter { LookupChain.isStrong(it, null, noClan = true) }
        // Every strong candidate is a no-clan player whose name is mbg in some case.
        assertTrue(strong.size >= 4)
        assertTrue(strong.all { it.hit.clanName.isNullOrBlank() && it.hit.name.lowercase() == "mbg" })
        // The real opponent's lowercase spelling is among them; case is never used to decide.
        assertTrue(strong.any { it.hit.name == "mbg" })
        assertTrue(strong.any { it.hit.name == "MBG" })
        val best = scored.maxBy { it.score }
        assertTrue(best.hit.clanName.isNullOrBlank())
        assertEquals(1.0, best.score, 1e-9)
    }

    @Test
    fun knownAndUnknownClanKeepTheirBehaviour() {
        val hit = PlayerHit("Macle", "98VQRGCQ", "Kings & Queens", "QCJPGQU2")
        assertTrue(LookupChain.isStrong(LookupChain.score(hit, "Macle", "Kings & Queens", noClan = false), "Kings & Queens", noClan = false))
        assertTrue(LookupChain.isStrong(LookupChain.score(hit, "Macle", null, noClan = false), null, noClan = false))
        // A real clan reading wins over a stray no-clan flag.
        assertTrue(LookupChain.isStrong(LookupChain.score(hit, "Macle", "Kings & Queens", noClan = true), "Kings & Queens", noClan = true))
    }

    @Test
    fun leagueTieBreak() {
        val tied = listOf("G28C8QGP9", "UCC9GP9JU", "C0CP8C20U").map { LookupChain.score(PlayerHit("mbg", it, null, null), "mbg", null, true) }
        fun prof(tag: String, league: Int?, trophies: Int?) = PlayerStats("mbg", tag, league = league, trophies = trophies, source = "t")
        val profiles = mapOf(
            "G28C8QGP9" to prof("G28C8QGP9", 4, 8405),
            "UCC9GP9JU" to prof("UCC9GP9JU", 5, 9100),
            "C0CP8C20U" to prof("C0CP8C20U", 1, 2000),
        )
        assertEquals(listOf("UCC9GP9JU"), LookupChain.tieBreak(tied, profiles, ScreenHint(noClan = true, league = 5)).map { it.hit.tag })
        assertEquals(listOf("G28C8QGP9"), LookupChain.tieBreak(tied, profiles, ScreenHint(noClan = true, trophies = 8390)).map { it.hit.tag })
        assertTrue(LookupChain.tieBreak(tied, profiles, ScreenHint(noClan = true)).isEmpty())
        assertTrue(LookupChain.tieBreak(tied, profiles, ScreenHint(noClan = true, league = 9)).isEmpty())
    }

    @Test
    fun profileExposesCurrentSeasonLeagueAndTrophies() {
        val p = RoyaleApiWeb.parseProfile(fixture("player_9CQ9Q80V.html"), "9CQ9Q80V")!!
        assertEquals(1, p.league)
        assertEquals(11677, p.trophies)
        // Macle: Best Season League 7, Current Season League 4.
        assertEquals(4, RoyaleApiWeb.parseProfile(fixture("player_98VQRGCQ.html"), "98VQRGCQ")!!.league)
    }

    @Test
    fun cacheKeyCarriesTheClanState() {
        val unknown = CachePolicy.queryKey("Mbg", null)
        val none = CachePolicy.queryKey("Mbg", null, noClan = true)
        val clan = CachePolicy.queryKey("Mbg", "One Hit Wonders")
        assertNotEquals(unknown, none)
        assertNotEquals(none, clan)
        assertNotEquals(unknown, clan)
        // Case never matters.
        assertEquals(none, CachePolicy.queryKey("mbg", null, noClan = true))
        assertEquals(2, CachePolicy.MATCHER_VERSION)
    }

    @Test
    fun pickerKeepsNoClanAndLeague() {
        fun l(t: String, b: IntArray, w: Double = 0.0) = OcrLine(t, Box(b[0], b[1], b[2], b[3]), 0.8f, "latin", warmth = w)
        // Lines from the phone scan.
        val p = NamePicker.pick(
            listOf(
                l("N22", intArrayOf(446, 84, 510, 104)),
                l("Mbg", intArrayOf(97, 116, 211, 181), 0.40),
                l("No Clan", intArrayOf(100, 178, 222, 206)),
                l("MLeague 5", intArrayOf(37, 222, 245, 266), 0.24),
            )
        )
        assertEquals("Mbg", p.name?.text)
        assertNull(p.clan)
        assertTrue(p.noClan)
        assertEquals(5, p.league)
        assertEquals(ScreenHint(null, true, 5, null), p.hint())
        // Trophy screens: "No Clan" then "9403".
        val q = NamePicker.pick(
            listOf(
                l("glopezzz", intArrayOf(200, 170, 420, 215), 0.4),
                l("No Clan", intArrayOf(205, 222, 330, 250)),
                l("9403", intArrayOf(205, 258, 290, 286)),
            )
        )
        assertEquals(9403, q.trophies)
        assertTrue(q.noClan)
    }
}
