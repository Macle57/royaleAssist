package com.macle.royaleassist

import com.macle.royaleassist.app.PipRenderer
import com.macle.royaleassist.app.PipView
import com.macle.royaleassist.app.toNSData
import com.macle.royaleassist.lookup.DeckCard
import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.IosHttpPlatform
import com.macle.royaleassist.ui.CardImages
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerHit
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.lookup.RecentDeck
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.overlay.PanelState
import kotlinx.cinterop.useContents
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The picture-in-picture bubble draws the shared Compose UI offscreen (ImageComposeScene, Skia on
 * the CPU). Each state renders to a 960x540 image; the PNGs go to /tmp/pip-*.png for a look.
 */
private const val CDN = "https://cdns3.royaleapi.com/cdn-cgi/image/q=80,w=75,h=90,format=auto/static/img/cards/v11-a8e42334"

class PipRendererTest {
    private val deck = listOf("hog-rider", "musketeer", "ice-spirit", "skeletons", "cannon", "fireball", "the-log", "ice-golem")
        .map { DeckCard(it, 14, "$CDN/$it.png", it) }

    private val result = LookupResult(
        query = "Daniel", clanQuery = "GLI ARGONAUTI",
        hit = PlayerHit("Daniel", "2PP", "GLI ARGONAUTI"), strategy = "test", confidence = 1.0,
        stats = PlayerStats("Daniel", "2PP", clanName = "GLI ARGONAUTI", source = "test"),
        strongMatch = true,
        recentDecks = listOf(RecentDeck(deck), RecentDeck(deck.reversed()), RecentDeck(deck.shuffled())),
    )

    private fun render(name: String, view: PipView, settleSeconds: Int = 0) {
        val r = PipRenderer()
        var img = assertNotNull(r.render(view), "no image for $name")
        // Card images load asynchronously on the main queue, as in the app: let it run, draw again.
        repeat(settleSeconds * 4) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.25))
            img = assertNotNull(r.render(view))
        }
        img.size.useContents {
            assertEquals(PipRenderer.WIDTH_PX.toDouble(), width * img.scale)
            assertEquals(PipRenderer.HEIGHT_PX.toDouble(), height * img.scale)
        }
        assertNotNull(r.lastPng).toNSData().writeToFile("/tmp/pip-$name.png", atomically = true)
    }

    @Test fun idle() = render("idle", PipView.Idle(capturing = true))

    @Test fun busy() = render("busy", PipView.Busy)

    /** With the real card images from the network (Coil through the app's Ktor client). */
    @Test fun deckCard() {
        CardImages.install(HttpClients(IosHttpPlatform()), diskCacheDir = "${NSTemporaryDirectory()}pip-test-coil".toPath())
        render("deck", PipView.Card(PanelState.Result(emptyList(), NamePick(null, null), result), deckIndex = 1, decksToShow = 3), settleSeconds = 8)
    }

    @Test fun notFound() = render("notfound", PipView.Card(PanelState.NotFound("No name on screen. Open a battle and tap again."), 0, 3))
}
