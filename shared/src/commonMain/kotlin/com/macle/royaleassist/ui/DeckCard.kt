package com.macle.royaleassist.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.overlay.DeckPager
import com.macle.royaleassist.overlay.Panel
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.decksFor

/**
 * The result card for one lookup outside the Android overlay (iOS picture-in-picture / Slide Over
 * card, quick lookup): the minimal deck card with its arrows, pinned on tap. [maxWidthDp] is the
 * width the card may use; null uses the window width. [alwaysPinned] shows the pinned card right
 * away (decks with the arrows above the stats, like the pinned Android card); Close then closes it.
 */
@Composable
fun DeckCard(
    result: LookupResult,
    deckOnly: Boolean = true,
    decksToShow: Int = 3,
    alpha: Float = 0.95f,
    maxWidthDp: Float? = null,
    onVerify: () -> Unit = {},
    onOpenWeb: ((String) -> Unit)? = null,
    onRefresh: ((String) -> Unit)? = null,
    onClose: () -> Unit = {},
    alwaysPinned: Boolean = false,
) {
    val pager = remember(result) { DeckPager() }
    val deckIndex by pager.index.collectAsState()
    var pinned by remember(result) { mutableStateOf(alwaysPinned) }
    val decks = decksFor(result, decksToShow).size
    Panel(
        state = PanelState.Result(emptyList(), NamePick(null, null), result),
        pinned = pinned,
        onPin = { pinned = true },
        onClose = { if (pinned && !alwaysPinned) pinned = false else onClose() },
        onRetry = null,
        onPickLine = {},
        onVerify = onVerify,
        alpha = alpha,
        deckOnly = deckOnly,
        onRefresh = onRefresh,
        onOpenWeb = onOpenWeb,
        showCloseHint = !alwaysPinned,
        decksToShow = decksToShow,
        maxWidthDp = maxWidthDp,
        deckIndex = deckIndex,
        onDeckStep = { delta -> pager.step(delta, decks) },
    )
}
