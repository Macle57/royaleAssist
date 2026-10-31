package com.macle.royaleassist

import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.overlay.MINI_DECK_ARROW_DP
import com.macle.royaleassist.overlay.MINI_DECK_MIN_ICON_DP
import com.macle.royaleassist.overlay.matchedNameLine
import com.macle.royaleassist.overlay.miniDeckIconDp
import com.macle.royaleassist.overlay.miniDeckWidthDp
import com.macle.royaleassist.overlay.overlayMaxWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The minimal deck card shows one deck at a time (arrows when paging) and must fit the phone's width. */
class MiniDeckLayoutTest {
    @Test
    fun oneDeckCardFitsOnPhones() {
        // S26 Ultra: 1080 px at 450 dpi (384 dp, the default) or 1440 px at 560 dpi (411 dp); a small 360 dp phone.
        for (screenDp in listOf(360f, 384f, 411f)) {
            for (scale in listOf(0.75f, 1f, 1.25f, 1.5f)) {
                val max = overlayMaxWidthDp(screenDp, scale)
                for (paged in listOf(false, true)) {
                    val icon = miniDeckIconDp(max, paged)
                    val w = miniDeckWidthDp(icon, paged)
                    if (icon > MINI_DECK_MIN_ICON_DP) assertTrue("$screenDp dp x$scale, paged=$paged: $w > $max", w <= max + 0.01f)
                }
            }
        }
        // At the normal size the icons keep the big 46 dp, with or without arrows.
        assertEquals(46f, miniDeckIconDp(overlayMaxWidthDp(384f, 1f), paged = false), 0.01f)
        assertEquals(46f, miniDeckIconDp(overlayMaxWidthDp(360f, 1f), paged = true), 0.01f)
        // At the largest size on a small phone they shrink to fit, arrows included.
        val max = overlayMaxWidthDp(360f, 1.5f)
        val icon = miniDeckIconDp(max, paged = true)
        assertTrue("$icon", icon < 46f)
        assertEquals(max, miniDeckWidthDp(icon, paged = true), 0.01f)
    }

    @Test
    fun widthIsOneGridPlusPaddingAndArrows() {
        // 3 dp padding each side + 4 icons and 3 gaps; paging adds a 20 dp arrow column on each side.
        assertEquals(6f + 46f * 4 + 9f, miniDeckWidthDp(46f, paged = false), 0.01f)
        assertEquals(6f + 46f * 4 + 9f + 2 * MINI_DECK_ARROW_DP, miniDeckWidthDp(46f, paged = true), 0.01f)
        assertEquals(6f + 30f * 4 + 9f, miniDeckWidthDp(30f, paged = false), 0.01f)
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
