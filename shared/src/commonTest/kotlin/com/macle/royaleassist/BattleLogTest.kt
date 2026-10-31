package com.macle.royaleassist

import com.macle.royaleassist.lookup.ApiBattle
import com.macle.royaleassist.lookup.ApiCard
import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.settings.OverlayPrefs
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class BattleLogTest {
    private fun fixture(name: String) = readFixture("royaleapi/$name")

    @Test
    fun parsesRoyaleApiBattlesPage() {
        val b = BattleLog.parseRoyaleApi(fixture("battles_PVJ28CYGG.html"), "#PVJ28CYGG")
        assertNotNull(b)
        b!!
        assertEquals("CRL 20-Win Challenge", b.mode)
        assertEquals(1793089097L, b.time)
        assertEquals(8, b.cards.size)
        assertEquals(listOf("Firecracker", "Mighty Miner", "Tesla", "Hog Rider"), b.cards.take(4).map { it.name })
        assertTrue(b.cards.all { it.level == 11 })
        assertTrue(b.cards.all { it.iconUrl!!.startsWith("https://cdns3.royaleapi.com/") && it.iconUrl!!.endsWith(".png") })
    }

    @Test
    fun parsesSecondFixture() {
        val b = BattleLog.parseRoyaleApi(fixture("battles_98VQRGCQ.html"), "98VQRGCQ")!!
        assertEquals(1793112894L, b.time)
        assertEquals(8, b.cards.size)
    }

    @Test
    fun readsOfficialApiBattleLog() {
        val json = """
            [{"type":"PvP","battleTime":"20261027T120000.000Z","gameMode":{"id":72000006,"name":"Ladder"},
              "team":[{"tag":"#98VQRGCQ","name":"Macle","cards":[
                {"name":"Hog Rider","level":9,"maxLevel":12,"iconUrls":{"medium":"https://api-assets.clashroyale.com/cards/300/hog.png"}},
                {"name":"Firecracker","level":11,"maxLevel":14,"evolutionLevel":1,
                 "iconUrls":{"medium":"https://x/fc.png","evolutionMedium":"https://x/fc-evo.png"}}]}],
              "opponent":[{"tag":"#AAA","cards":[]}]}]
        """.trimIndent()
        val battles = Json { ignoreUnknownKeys = true }.decodeFromString<List<ApiBattle>>(json)
        val b = BattleLog.fromApi(battles, "98VQRGCQ")!!
        assertEquals("Ladder", b.mode)
        assertEquals(listOf(11, 11), b.cards.map { it.level })
        assertEquals("https://x/fc-evo.png", b.cards[1].iconUrl)
        assertEquals(1793102400L, b.time)
    }

    @Test
    fun relativeTimes() {
        assertEquals("just now", BattleLog.relative(1000, 1030))
        assertEquals("12 min ago", BattleLog.relative(1000, 1000 + 12 * 60 + 5))
        assertEquals("3 h ago", BattleLog.relative(0, 3 * 3600 + 10))
        assertEquals(14, BattleLog.apiDisplayLevel(ApiCard("x", level = 11, maxLevel = 11)))
    }

    @Test
    fun overlayPrefsAreClamped() {
        val p = OverlayPrefs(bubbleAlpha = 0f, panelAlpha = 2f, autoHideSeconds = 99, scale = 5f).normalized()
        assertEquals(OverlayPrefs.MIN_ALPHA, p.bubbleAlpha)
        assertEquals(1f, p.panelAlpha)
        assertEquals(30, p.autoHideSeconds)
        assertEquals(1.5f, p.scale)
    }
}
