package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.overlay.MINI_DECK_MIN_ICON_DP
import com.macle.royaleassist.overlay.matchedNameLine
import com.macle.royaleassist.overlay.miniDeckIconDp
import com.macle.royaleassist.overlay.miniDeckWidthDp
import com.macle.royaleassist.overlay.overlayMaxWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The minimal deck card puts 1-3 decks side by side and must fit the phone's width. */
class MiniDeckLayoutTest {
    @Test
    fun threeDecksFitSideBySideOnPhones() {
        // S26 Ultra: 1080 px at 450 dpi (384 dp, the default) or 1440 px at 560 dpi (411 dp); a small 360 dp phone.
        for (screenDp in listOf(360f, 384f, 411f)) {
            for (scale in listOf(0.75f, 1f, 1.25f, 1.5f)) {
                val max = overlayMaxWidthDp(screenDp, scale)
                for (n in 1..3) {
                    val icon = miniDeckIconDp(n, max)
                    val w = miniDeckWidthDp(n, icon)
                    if (icon > MINI_DECK_MIN_ICON_DP) assertTrue("$screenDp dp x$scale, $n decks: $w > $max", w <= max + 0.01f)
                }
            }
        }
        // At the normal size the icons stay readable: about 27 dp (76 px) with three decks on 384 dp.
        val icon3 = miniDeckIconDp(3, overlayMaxWidthDp(384f, 1f))
        assertTrue("$icon3", icon3 in 26f..32f)
        // One deck keeps the big 46 dp icons; two decks 38 dp.
        assertEquals(46f, miniDeckIconDp(1, overlayMaxWidthDp(384f, 1f)), 0.01f)
        assertEquals(38f, miniDeckIconDp(2, overlayMaxWidthDp(411f, 1f)), 0.01f)
    }

    @Test
    fun widthAddsGridsSeparatorsAndPadding() {
        // 3 dp padding each side + 4 icons and 3 gaps per deck + (3 dp, 1 dp line, 3 dp) between decks.
        assertEquals(6f + 46f * 4 + 9f, miniDeckWidthDp(1, 46f), 0.01f)
        assertEquals(6f + (30f * 4 + 9f) * 3 + 2 * 7f, miniDeckWidthDp(3, 30f), 0.01f)
    }

    @Test
    fun nameLineShowsTheMatchedPlayer() {
        val r = LookupResult(
            "MBG", null, PlayerHit("mbg", "ABC", null), "L2", 1.0,
            PlayerStats(name = "mbg", tag = "ABC", clanName = "One Hit Wonders", source = "test"),
        )
        assertEquals("mbg" to "One Hit Wonders", matchedNameLine(r))
        assertEquals("mbg" to null, matchedNameLine(r.copy(stats = r.stats!!.copy(clanName = " "))))
        assertEquals("x" to "C", matchedNameLine(r.copy(stats = null, hit = PlayerHit("x", "T", "C"))))
        assertNull(matchedNameLine(r.copy(stats = null, hit = null)))
        assertNull(matchedNameLine(null))
    }
}
