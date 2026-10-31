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
 * card, history detail): the minimal deck card with its arrows, pinned on tap. [maxWidthDp] is the
 * width the card may use; null uses the window width.
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
) {
    val pager = remember(result) { DeckPager() }
    val deckIndex by pager.index.collectAsState()
    var pinned by remember(result) { mutableStateOf(false) }
    val decks = decksFor(result, decksToShow).size
    Panel(
        state = PanelState.Result(emptyList(), NamePick(null, null), result),
        pinned = pinned,
        onPin = { pinned = true },
        onClose = { if (pinned) pinned = false else onClose() },
        onRetry = null,
        onPickLine = {},
        onVerify = onVerify,
        alpha = alpha,
        deckOnly = deckOnly,
        onRefresh = onRefresh,
        onOpenWeb = onOpenWeb,
        showCloseHint = true,
        decksToShow = decksToShow,
        maxWidthDp = maxWidthDp,
        deckIndex = deckIndex,
        onDeckStep = { delta -> pager.step(delta, decks) },
    )
}
