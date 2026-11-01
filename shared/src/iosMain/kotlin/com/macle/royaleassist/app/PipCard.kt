package com.macle.royaleassist.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.L
import com.macle.royaleassist.overlay.Panel
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.Scaled
import com.macle.royaleassist.overlay.WarningBadge
import com.macle.royaleassist.overlay.decksFor
import com.macle.royaleassist.overlay.matchedNameLine
import com.macle.royaleassist.overlay.showsDeck
import com.macle.royaleassist.ui.Royal
import com.macle.royaleassist.ui.RoyalTheme
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage
import kotlin.time.TimeSource

/** What the picture-in-picture window shows. */
sealed interface PipView {
    /** The bubble: tap play to scan. [capturing] is false when the screen broadcast is off. */
    data class Idle(val capturing: Boolean) : PipView

    /** A scan is running. */
    data object Busy : PipView

    /** A result (the deck card) or a message ("not found", errors). */
    data class Card(val state: PanelState, val deckIndex: Int, val decksToShow: Int) : PipView
}

/**
 * Draws the picture-in-picture content with the shared Compose UI (bubble look, the minimal deck
 * card of the Android overlay) into an offscreen image: Compose renders with Skia on the CPU, so
 * it also works while the app is in the background (no Metal there). Main thread only.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PipRenderer {
    private val view = mutableStateOf<PipView>(PipView.Idle(false))
    private val warning = mutableStateOf(false)
    private var scene: ImageComposeScene? = null
    private val start = TimeSource.Monotonic.markNow()

    /** The PNG of the last frame (debug artefacts). */
    var lastPng: ByteArray? = null
        private set

    /** Draws [v]; [warning] = RoyaleAPI needs the browser check again (red bubble, "!" badge). */
    fun render(v: PipView, warning: Boolean = false): UIImage? = try {
        view.value = v
        this.warning.value = warning
        val s = scene ?: ImageComposeScene(WIDTH_PX, HEIGHT_PX, Density(DENSITY), coroutineContext = Dispatchers.Main) {
            RoyalTheme { PipContent(view.value, this.warning.value) }
        }.also { scene = it }
        Snapshot.sendApplyNotifications()
        val img = s.render(start.elapsedNow().inWholeNanoseconds)
        val png = img.encodeToData(EncodedImageFormat.PNG)?.bytes
        img.close()
        lastPng = png
        png?.toNSData()?.let { UIImage(data = it) }
    } catch (e: Throwable) {
        L.e("pip: render failed", e)
        null
    }

    companion object {
        /** 16:9 canvas, 320 x 180 dp at 3x: the minimal deck card fits with room for the name line. */
        const val DENSITY = 3f
        const val WIDTH_PX = 960
        const val HEIGHT_PX = 540
    }
}

internal fun ByteArray.toNSData(): NSData = usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

@Composable
private fun PipContent(v: PipView, warning: Boolean) {
    Box(Modifier.fillMaxSize().background(Royal.Night), contentAlignment = Alignment.Center) {
        when (v) {
            is PipView.Idle -> BubbleRow(
                busy = false,
                warning = warning,
                title = "Tap, then play",
                body = when {
                    warning -> VERIFY_HINT
                    v.capturing -> "Reads the opponent and shows their decks."
                    else -> "Start screen capture in royaleAssist first."
                },
            )
            PipView.Busy -> BubbleRow(
                busy = true,
                warning = warning,
                title = "Reading the screen...",
                body = if (warning) VERIFY_HINT else "Looking up the opponent.",
            )
            is PipView.Card -> {
                CardContent(v)
                // Visible while a deck is up, so the expired check is noticed before the next scan.
                if (warning) WarningBadge(BADGE_DP.dp, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
}

private const val VERIFY_HINT = "RoyaleAPI check expired. Open royaleAssist and tap Verify RoyaleAPI."

/** The bubble's 18 dp badge, scaled with the 72 dp circle here (56 dp on Android). */
private const val BADGE_DP = 22

@Composable
private fun CardContent(v: PipView.Card) {
    val state = v.state
    if (showsDeck(state, deckOnly = true, decksToShow = v.decksToShow)) {
        // The mini deck card of the Android overlay, a bit larger to fill the window. Nothing in
        // the image can be tapped, so no arrows: the window's skip buttons change the deck, which
        // the line under the card says when there is more than one.
        val decks = decksFor((state as? PanelState.Result)?.result, v.decksToShow).size
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Scaled(PIP_CARD_SCALE) {
                Panel(
                    state = state,
                    pinned = false,
                    onPin = {},
                    onClose = {},
                    onRetry = null,
                    onPickLine = {},
                    onVerify = {},
                    alpha = 1f,
                    deckOnly = true,
                    decksToShow = v.decksToShow,
                    maxWidthDp = 304f / PIP_CARD_SCALE,
                    deckIndex = v.deckIndex,
                    onDeckStep = null,
                    deckCounter = true,
                )
            }
            if (decks > 1) SkipHint()
        }
        return
    }
    val r = (state as? PanelState.Result)?.result
    val notFound = state as? PanelState.NotFound
    // The matched player, else what was read off the screen.
    val pick = (state as? PanelState.Result)?.pick ?: notFound?.pick
    val name = matchedNameLine(r) ?: pick?.name?.let { it.text to pick.clan?.text }
    val message = when {
        notFound != null -> notFound.message
        r?.needsVerification == true && r.stats == null -> "RoyaleAPI wants a one-time browser check: open royaleAssist and tap Verify RoyaleAPI."
        r?.stats != null -> "No recent decks for this player." + (r.stats.trophies?.let { " Trophies $it." } ?: "")
        else -> r?.error ?: "Player not found"
    }
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        name?.let { (n, clan) ->
            Text(n, style = MaterialTheme.typography.headlineSmall, color = Royal.Elixir, maxLines = 1, overflow = TextOverflow.Ellipsis)
            clan?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Royal.Mist, maxLines = 1) }
        }
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = if (notFound != null) Royal.Danger else Royal.Parchment,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Leaves room under the card for the [SkipHint] line in the 180 dp tall window. */
private const val PIP_CARD_SCALE = 1.1f

/** "<< >> change deck": the window's skip buttons page through the decks. */
@Composable
private fun SkipHint() {
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(SkipBack, contentDescription = null, colorFilter = ColorFilter.tint(Royal.Gold), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Image(SkipForward, contentDescription = null, colorFilter = ColorFilter.tint(Royal.Gold), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("change deck", style = MaterialTheme.typography.labelMedium, color = Royal.Mist, maxLines = 1)
    }
}

/** Two left-pointing triangles (rewind), drawn in code like [Crown]. */
private val SkipBack: ImageVector by lazy { doubleTriangle("SkipBack", left = true) }

/** Two right-pointing triangles (fast forward). */
private val SkipForward: ImageVector by lazy { doubleTriangle("SkipForward", left = false) }

private fun doubleTriangle(name: String, left: Boolean): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        fun x(v: Float) = if (left) 24f - v else v
        path(fill = SolidColor(Royal.Gold)) { moveTo(x(2f), 5f); lineTo(x(12f), 12f); lineTo(x(2f), 19f); close() }
        path(fill = SolidColor(Royal.Gold)) { moveTo(x(12f), 5f); lineTo(x(22f), 12f); lineTo(x(12f), 19f); close() }
    }.build()

@Composable
private fun BubbleRow(busy: Boolean, warning: Boolean, title: String, body: String) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        // The Android bubble (OverlayUi.Bubble) at 72 dp: red with a white rim and a badge when warning.
        Box(Modifier.size(72.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(if (warning) Royal.Danger else Royal.Navy)
                    .border(2.5.dp, if (warning) Color.White.copy(alpha = 0.85f) else Royal.Gold.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(40.dp), color = Royal.Gold, strokeWidth = 4.dp)
                else Image(Crown, contentDescription = null, colorFilter = ColorFilter.tint(Royal.Gold), modifier = Modifier.size(44.dp))
            }
            if (warning) WarningBadge(BADGE_DP.dp, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Royal.Gold, maxLines = 1)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Royal.Mist, maxLines = 3)
        }
    }
}

/** The crown of the bubble icon (ic_bubble.xml), built in code so the background render needs no resource files. */
private val Crown: ImageVector by lazy {
    ImageVector.Builder("Crown", 24.dp, 24.dp, viewportWidth = 108f, viewportHeight = 108f).apply {
        path(fill = SolidColor(Royal.Gold)) {
            moveTo(34f, 68f); lineTo(30f, 42f); lineTo(43f, 53f); lineTo(54f, 36f); lineTo(65f, 53f); lineTo(78f, 42f); lineTo(74f, 68f); close()
        }
        path(fill = SolidColor(Royal.Gold)) {
            moveTo(34f, 72f); lineTo(74f, 72f); lineTo(74f, 77f); lineTo(34f, 77f); close()
        }
    }.build()
}
