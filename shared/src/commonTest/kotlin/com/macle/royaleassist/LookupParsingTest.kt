package com.macle.royaleassist

import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.StringSim
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class LookupParsingTest {
    private fun fixture(name: String) = readFixture("royaleapi/$name")

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
        val rows = RoyaleApiWeb.parseSearch(fixture("search_exact_macle.html")).shuffled(kotlin.random.Random(1))
        val found = LookupChain.best(rows, "Macle", "Kings & Queens")!!
        assertEquals("98VQRGCQ", found.hit.tag)
        assertTrue(LookupChain.isStrong(found, "Kings & Queens"))
        val ocrNoisy = LookupChain.best(rows, "MacIe", "Kings 8 Queens")!!
        assertEquals("98VQRGCQ", ocrNoisy.hit.tag)
    }

    @Test
    fun fuzzyPick() {
        val rows = RoyaleApiWeb.parseSearch(fixture("search_fuzzy_macle.html"))
        val found = LookupChain.best(rows, "Macle", "Kings & Queens")!!
        assertEquals("98VQRGCQ", found.hit.tag)
    }

    @Test
    fun parsesClanSearchAndMembers() {
        val clans = RoyaleApiWeb.parseClanSearch(fixture("clan_search_eternal_night.html"))
        assertTrue(clans.size >= 50)
        assertEquals("RQUVRYYL" to "Eternal Night", clans[0])
        val ranked = LookupChain.rankClans(clans, "Eternal Night")
        assertTrue(ranked.take(5).all { it.second == "Eternal Night" })
        val members = RoyaleApiWeb.parseClanMembers(fixture("clan_QCJPGQU2.html"), "QCJPGQU2", "Kings & Queens")
        assertTrue(members.size in 10..50)
        assertTrue(members.contains(PlayerHit("Macle", "98VQRGCQ", "Kings & Queens", "QCJPGQU2")))
    }

    @Test
    fun ocrVariantsFixCommonMisreads() {
        assertEquals("Royale King", LookupChain.ocrVariants("Rovale King").first())
    }

    @Test
    fun strongMatchNeedsClan() {
        val wrongClan = LookupChain.score(PlayerHit("Danilka Pro", "X", "\"Juggernaut\"", "Y"), "Danilka", "Juggernaut\"")
        assertTrue(!LookupChain.isStrong(wrongClan, "Juggernaut\""))
        val right = LookupChain.score(PlayerHit("Trainer fart", "X", "时光", "Y"), "TRaiNeR fart", "时光")
        assertTrue(LookupChain.isStrong(right, "时光"))
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
