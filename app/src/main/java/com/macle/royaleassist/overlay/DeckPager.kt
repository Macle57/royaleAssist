package com.macle.royaleassist.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A card that shows a deck stays on screen at least this long, whatever the auto-hide setting. */
const val DECK_MIN_SHOW_MS = 10_000L

/** [index] clamped to the decks there are (0 is the newest); 0 when there are none. */
fun deckPageIndex(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

/** True when [state] is shown as a deck card (deck view on and at least one deck). */
fun showsDeck(state: PanelState, deckOnly: Boolean, decksToShow: Int): Boolean =
    deckOnly && decksFor((state as? PanelState.Result)?.result, decksToShow).isNotEmpty()

/**
 * How long an unpinned panel stays up: the auto-hide setting, at least [DECK_MIN_SHOW_MS] for a
 * deck card, and at most [OverlayService.NOT_FOUND_HIDE_MS] for "not found".
 */
fun panelHideDelayMs(state: PanelState, autoHideSeconds: Int, showsDeck: Boolean): Long {
    val full = autoHideSeconds * 1000L
    return when {
        state is PanelState.NotFound -> minOf(OverlayService.NOT_FOUND_HIDE_MS, full)
        showsDeck -> maxOf(full, DECK_MIN_SHOW_MS)
        else -> full
    }
}

/**
 * Which of the recent decks the card shows, newest (0) first. Lives in the service so the mini card
 * and the pinned view agree, and so a new result can start again at the newest deck.
 */
class DeckPager {
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    /** Back to the newest deck (a new result is shown). */
    fun reset() {
        _index.value = 0
    }

    /** Moves by [delta] within [count] decks, without wrapping; true if the page changed. */
    fun step(delta: Int, count: Int): Boolean {
        val from = deckPageIndex(_index.value, count)
        val to = deckPageIndex(from + delta, count)
        _index.value = to
        return to != from
    }
}
