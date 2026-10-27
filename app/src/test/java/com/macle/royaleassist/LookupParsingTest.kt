package com.macle.royaleassist

import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.StringSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupParsingTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("royaleapi/$name")!!.readText()

    @Test
    fun parsesExactSearch() {
        val rows = RoyaleApiWeb.parseSearch(fixture("search_exact_macle.html"))
        assertEquals(8, rows.size)
        assertEquals(PlayerHit("Macle", "98VQRGCQ", "Kings & Queens", "QCJPGQU2"), rows[0])
        // Last row has no clan.
        assertNull(rows.last().clanName)
        assertTrue(rows.all { it.name == "Macle" })
    }

    @Test
    fun parsesFuzzySearch() {
        val rows = RoyaleApiWeb.parseSearch(fixture("search_fuzzy_macle.html"))
        assertEquals(100, rows.size)
        assertTrue(rows.any { it.tag == "98VQRGCQ" })
    }

    @Test
    fun parsesProfile() {
        val p = RoyaleApiWeb.parseProfile(fixture("player_98VQRGCQ.html"), "98VQRGCQ")
        assertNotNull(p)
        p!!
        assertEquals("Macle", p.name)
        assertEquals("98VQRGCQ", p.tag)
        assertEquals("Kings & Queens", p.clanName)
        assertEquals("QCJPGQU2", p.clanTag)
        assertEquals(14000, p.trophies)
        assertEquals(68, p.expLevel)
        assertEquals(13284, p.battleCount)
        assertEquals("Co-Leader", p.clanRole)
    }

    @Test
    fun detectsChallengePage() {
        assertTrue(HttpClients.isChallengeHtml(fixture("cloudflare_challenge.html")))
        assertTrue(!HttpClients.isChallengeHtml(fixture("search_exact_macle.html")))
    }

    @Test
    fun clanDisambiguationPicksRightRow() {
        val rows = RoyaleApiWeb.parseSearch(fixture("search_exact_macle.html")).shuffled(java.util.Random(1))
        val found = LookupChain.pickByClan(rows, "Kings & Queens", base = 0.75)!!
        assertEquals("98VQRGCQ", found.hit.tag)
        val ocrNoisy = LookupChain.pickByClan(rows, "Kings 8 Queens", base = 0.75)!!
        assertEquals("98VQRGCQ", ocrNoisy.hit.tag)
    }

    @Test
    fun fuzzyPick() {
        val rows = RoyaleApiWeb.parseSearch(fixture("search_fuzzy_macle.html"))
        val found = LookupChain.pickFuzzy(rows, "Macle", "Kings & Queens")!!
        assertEquals("98VQRGCQ", found.hit.tag)
    }

    @Test
    fun similarityFoldsDecorations() {
        assertTrue(StringSim.similarity("MasterX™<", "MasterX") > 0.9)
        assertTrue(StringSim.similarity("TOT", "TθT") > 0.9)
        assertTrue(StringSim.similarity("Eternal Night *", "Eternal Night") > 0.95)
        assertTrue(StringSim.similarity("Royale King", "Danilka") < 0.6)
    }

    @Test
    fun cleansNames() {
        assertEquals("Eternal Night", LookupChain.cleanOcrName("  Eternal Night ★ "))
    }
}
