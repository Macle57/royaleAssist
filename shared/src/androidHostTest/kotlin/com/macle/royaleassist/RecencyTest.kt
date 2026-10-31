package com.macle.royaleassist

import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.RoyaleApiWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recency tie-break: among equally named players, the one who is playing right now wins. */
class RecencyTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("royaleapi/$name")!!.readText()

    private fun s(tag: String) = LookupChain.Scored(PlayerHit("mbg", tag), 1.0, 1.0, 1.0)

    private val now = 1_800_000_000L

    @Test
    fun onlyOneActiveIsDecisive() {
        val c = listOf(s("A"), s("B"), s("C"), s("D"))
        val r = LookupChain.byRecency(c, mapOf("A" to now - 86_400, "B" to now - 240, "C" to null, "D" to now - 3 * 3600), now)
        assertEquals(listOf("B"), r.active.map { it.hit.tag })
        // Newest first, unknown last.
        assertEquals(listOf("B", "D", "A", "C"), r.ordered.map { it.hit.tag })
        assertTrue(LookupChain.describeRecency(r, now).startsWith("#B 4 min ago, #D 3 h ago, #A 1 d ago, #C ?; 1 active"))
    }

    @Test
    fun severalActiveOrNoneAreNotDecisive() {
        val c = listOf(s("A"), s("B"), s("C"))
        val two = LookupChain.byRecency(c, mapOf("A" to now - 600, "B" to now - 60, "C" to now - 7200), now)
        assertEquals(listOf("B", "A"), two.active.map { it.hit.tag })
        val none = LookupChain.byRecency(c, mapOf("A" to now - 3600, "B" to null), now)
        assertTrue(none.active.isEmpty())
        assertEquals("A", none.ordered.first().hit.tag)
        // Ties keep the score order (input order).
        val tie = LookupChain.byRecency(c, emptyMap(), now)
        assertEquals(listOf("A", "B", "C"), tie.ordered.map { it.hit.tag })
    }

    @Test
    fun windowEdgesAndClockSkew() {
        val c = listOf(s("A"), s("B"), s("C"))
        val w = LookupChain.ACTIVE_WINDOW_SEC
        val r = LookupChain.byRecency(c, mapOf("A" to now - w, "B" to now - w - 1, "C" to now + 120), now)
        assertEquals(setOf("A", "C"), r.active.map { it.hit.tag }.toSet())
        // A battle far in the future (bad clock) does not count as active.
        val f = LookupChain.byRecency(c, mapOf("A" to now + 3600), now)
        assertTrue(f.active.isEmpty())
    }

    @Test
    fun newestBattleTimeComesFromTheBattlesPage() {
        // The recency check reads data-timestamp of the newest battle on royaleapi.com.
        val battles = BattleLog.parseRoyaleApiBattles(fixture("battles_98VQRGCQ.html"), "98VQRGCQ")
        assertTrue(battles.isNotEmpty())
        val newest = battles.mapNotNull { it.time }.max()
        assertTrue(newest > 1_700_000_000L)
        assertEquals("https://royaleapi.com/player/98VQRGCQ/battles", RoyaleApiWeb.battlesUrl("#98vqrgcq"))
        // Three minutes later the player counts as active.
        val r = LookupChain.byRecency(listOf(s("98VQRGCQ"), s("X")), mapOf("98VQRGCQ" to newest, "X" to newest - 86_400), newest + 180)
        assertEquals(listOf("98VQRGCQ"), r.active.map { it.hit.tag })
    }
}
