package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupOrigin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which lookups fetch the battle log for the recent decks. */
class LookupOriginTest {
    @Test
    fun overlayFollowsTheDeckSetting() {
        assertTrue(LookupChain.wantsDecks(LookupOrigin.Overlay, deckOnlySetting = true))
        assertFalse(LookupChain.wantsDecks(LookupOrigin.Overlay, deckOnlySetting = false), "stats card: no battle log")
    }

    @Test
    fun appLookupsAlwaysFetchTheDecks() {
        // The quick lookup and the screenshot scan show the pinned card with the decks either way.
        assertTrue(LookupChain.wantsDecks(LookupOrigin.App, deckOnlySetting = false))
        assertTrue(LookupChain.wantsDecks(LookupOrigin.App, deckOnlySetting = true))
    }
}
