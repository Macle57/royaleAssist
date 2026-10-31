package com.macle.royaleassist

import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.lookup.DeckCard
import com.macle.royaleassist.lookup.LastBattle
import com.macle.royaleassist.lookup.RecentDeck
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** Unique recent decks from saved royaleapi.com pages (BigWance has 3 distinct decks in 5 battles). */
class RecentDecksTest {
    private fun fixture(name: String) = readFixture("royaleapi/$name")

    @Test
    fun threeUniqueDecksFromBattlesPage() {
        val battles = BattleLog.parseRoyaleApiBattles(fixture("battles_8LCR02U.html"), "8LCR02U")
        assertEquals(5, battles.size)
        assertTrue(battles.all { it.cards.size == 8 && it.cards.all { c -> c.key != null } })
        assertTrue(battles.zipWithNext().all { (a, b) -> (a.time ?: 0) >= (b.time ?: 0) }, "newest first")
        val decks = BattleLog.uniqueDecks(battles, 3)
        assertEquals(3, decks.size)
        assertEquals(3, decks.map { BattleLog.deckKey(it.cards) }.distinct().size)
        // Counts cover the 5 battles; the first deck is the newest battle's.
        assertEquals(5, decks.sumOf { it.battleUses })
        assertTrue(decks.all { it.battlesSeen == 5 })
        assertEquals(battles.first().time, decks.first().lastTime)
        assertEquals(BattleLog.deckKey(battles.first().cards), BattleLog.deckKey(decks.first().cards))
    }

    @Test
    fun evolutionAndHeroVariantsAreTheSameCard() {
        fun d(vararg keys: String) = keys.map { DeckCard(it, 11, null, key = it) }
        val a = d("knight-ev1", "witch-ev1", "barbarian-barrel-hero", "cannon", "graveyard", "ice-spirit", "poison", "skeletons")
        val b = d("knight-hero", "witch-ev1", "barbarian-barrel", "cannon-ev1", "graveyard", "ice-spirit", "poison", "skeletons")
        assertEquals(BattleLog.deckKey(a), BattleLog.deckKey(b))
        // Order does not matter either; a different card does.
        assertEquals(BattleLog.deckKey(a), BattleLog.deckKey(a.reversed()))
        assertNotEquals(BattleLog.deckKey(a), BattleLog.deckKey(d("knight", "witch", "barbarian-barrel", "cannon", "graveyard", "ice-spirit", "poison", "zap")))
        val battles = listOf(LastBattle("Ladder", 300, a, "t"), LastBattle("Ladder", 200, b, "t"))
        val decks = BattleLog.uniqueDecks(battles, 3)
        assertEquals(1, decks.size)
        assertEquals(2, decks[0].battleUses)
    }

    @Test
    fun decksTabParsesAndTopsUp() {
        val tab = BattleLog.parseRoyaleApiDecksTab(fixture("decks_98VQRGCQ.html"))
        assertEquals(2, tab.size)
        assertEquals(30 to 47, tab[0].games to tab[0].total)
        assertTrue(tab.all { it.cards.size == 8 })
        val battles = BattleLog.parseRoyaleApiBattles(fixture("battles_98VQRGCQ.html"), "98VQRGCQ")
        val fromBattles = BattleLog.uniqueDecks(battles, 3)
        val merged = BattleLog.uniqueDecks(battles, 3, tab)
        assertTrue(merged.size >= fromBattles.size)
        assertTrue(merged.size <= 3)
        assertEquals(merged.map { BattleLog.deckKey(it.cards) }.distinct().size, merged.size)
    }

    @Test
    fun fewerThanThreeShowsWhatThereIs() {
        val battles = BattleLog.parseRoyaleApiBattles(fixture("battles_PVJ28CYGG.html"), "PVJ28CYGG")
        val decks = BattleLog.uniqueDecks(battles, 3)
        assertTrue(decks.isNotEmpty() && decks.size <= 3)
    }

    @Test
    fun usageLines() {
        val now = 10_000L
        assertEquals(
            "Ladder, 3 h ago · 4 of last 10",
            BattleLog.usageLine(RecentDeck(emptyList(), "Ladder", now - 3 * 3600, 4, 10), now),
        )
        assertEquals(
            "30 of 47 games in 7 days",
            BattleLog.usageLine(RecentDeck(emptyList(), battlesSeen = 5, weekGames = 30, weekTotal = 47), now),
        )
    }
}
