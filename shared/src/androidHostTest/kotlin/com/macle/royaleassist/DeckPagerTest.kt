package com.macle.royaleassist

import com.macle.royaleassist.lookup.DeckCard
import com.macle.royaleassist.lookup.LastBattle
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.lookup.RecentDeck
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.overlay.DECK_MIN_SHOW_MS
import com.macle.royaleassist.overlay.DeckPager
import com.macle.royaleassist.overlay.NOT_FOUND_HIDE_MS
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.deckPageIndex
import com.macle.royaleassist.overlay.panelHideDelayMs
import com.macle.royaleassist.overlay.showsDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deck card pages through the recent decks (newest first) and stays up at least 10 s. */
class DeckPagerTest {
    private fun deck(vararg keys: String) = RecentDeck(keys.map { DeckCard(it, 11, null, key = it) })

    private fun result(decks: Int, battle: Boolean = true): PanelState.Result {
        val recent = (1..decks).map { deck("a$it", "b", "c", "d", "e", "f", "g", "h") }
        val r = LookupResult(
            "mbg", null, PlayerHit("mbg", "ABC", null), "L2", 1.0,
            PlayerStats(name = "mbg", tag = "ABC", source = "test"),
            lastBattle = if (battle) LastBattle("Ladder", null, recent.firstOrNull()?.cards ?: deck("x").cards, "test") else null,
            recentDecks = recent,
        )
        val line = OcrLine("mbg", Box(0, 0, 10, 10), 1f, "test")
        return PanelState.Result(listOf(line), NamePick(line, null, reason = "test"), r)
    }

    @Test
    fun indexIsClampedToTheDecks() {
        assertEquals(0, deckPageIndex(0, 3))
        assertEquals(2, deckPageIndex(2, 3))
        assertEquals(2, deckPageIndex(5, 3))
        assertEquals(0, deckPageIndex(-1, 3))
        assertEquals(0, deckPageIndex(2, 1))
        assertEquals(0, deckPageIndex(1, 0))
    }

    @Test
    fun stepsWithoutWrapping() {
        val p = DeckPager()
        assertEquals(0, p.index.value)
        assertFalse("no newer deck than the newest", p.step(-1, 3))
        assertEquals(0, p.index.value)
        assertTrue(p.step(1, 3))
        assertTrue(p.step(1, 3))
        assertEquals(2, p.index.value)
        assertFalse("no wrap past the oldest", p.step(1, 3))
        assertEquals(2, p.index.value)
        assertTrue(p.step(-1, 3))
        assertEquals(1, p.index.value)
        // Fewer decks than the index (setting lowered): clamps first, then steps.
        assertFalse(p.step(-1, 1))
        assertEquals(0, p.index.value)
        assertFalse(p.step(1, 0))
    }

    @Test
    fun newResultStartsAtTheNewestDeck() {
        val p = DeckPager()
        p.step(1, 3)
        p.step(1, 3)
        assertEquals(2, p.index.value)
        p.reset()
        assertEquals(0, p.index.value)
    }

    @Test
    fun deckCardsStayAtLeastTenSeconds() {
        val decks = result(3)
        assertTrue(showsDeck(decks, deckOnly = true, decksToShow = 3))
        assertEquals(DECK_MIN_SHOW_MS, panelHideDelayMs(decks, 2, showsDeck = true))
        assertEquals(10_000L, panelHideDelayMs(decks, 6, showsDeck = true))
        assertEquals(10_000L, panelHideDelayMs(decks, 10, showsDeck = true))
        assertEquals(25_000L, panelHideDelayMs(decks, 25, showsDeck = true))
        // One deck (only the last battle known) counts too.
        val one = result(0)
        assertTrue(showsDeck(one, deckOnly = true, decksToShow = 3))
        // The auto-hide default is 10 s too.
        assertEquals(10, com.macle.royaleassist.settings.OverlayPrefs().autoHideSeconds)
    }

    @Test
    fun otherResultsKeepTheSetting() {
        val decks = result(3)
        // Stats view (deck view off): the plain setting.
        assertFalse(showsDeck(decks, deckOnly = false, decksToShow = 3))
        assertEquals(6_000L, panelHideDelayMs(decks, 6, showsDeck = false))
        assertEquals(2_000L, panelHideDelayMs(decks, 2, showsDeck = false))
        // Deck view but no deck at all.
        val none = result(0, battle = false)
        assertFalse(showsDeck(none, deckOnly = true, decksToShow = 3))
        // "Not found" stays short.
        val nf = PanelState.NotFound("nope")
        assertFalse(showsDeck(nf, deckOnly = true, decksToShow = 3))
        assertEquals(NOT_FOUND_HIDE_MS, panelHideDelayMs(nf, 10, showsDeck = false))
        assertEquals(2_000L, panelHideDelayMs(nf, 2, showsDeck = false))
    }
}
