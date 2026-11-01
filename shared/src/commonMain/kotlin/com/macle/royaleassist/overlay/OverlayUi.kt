package com.macle.royaleassist.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.cache.CachePolicy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.macle.royaleassist.lookup.LastBattle
import com.macle.royaleassist.lookup.DeckCard
import com.macle.royaleassist.lookup.LookupResult
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.resources.Res
import com.macle.royaleassist.resources.ic_bubble
import com.macle.royaleassist.ui.Royal
import com.macle.royaleassist.ui.RoyalIcons
import com.macle.royaleassist.ui.formatGrouped
import com.macle.royaleassist.util.nowMillis
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

/**
 * Minimal deck card: one line with the matched player's name, then one deck as a tight 4x2 grid
 * of icons. With more than one recent deck, slim arrow columns on both sides of the grid step
 * through them (newest first) and the name line ends with "1/3".
 */
const val MINI_DECK_GAP_DP = 3f
val MINI_DECK_GAP = MINI_DECK_GAP_DP.dp

/** Preferred icon width (dp); smaller when the screen is too narrow. */
const val MINI_DECK_ICON_DP = 46f

/** Icons never shrink below this (dp), even if the card then needs the full screen width. */
const val MINI_DECK_MIN_ICON_DP = 20f

/** Width (dp) of each arrow column beside the grid when the card pages through several decks. */
const val MINI_DECK_ARROW_DP = 20f

/** Margin (dp) kept free on each side of the screen. */
const val MINI_DECK_SCREEN_MARGIN_DP = 4f

/** Decks shown in the deck view: the recent unique decks, else the newest battle's deck. */
fun decksFor(r: LookupResult?, n: Int): List<List<DeckCard>> {
    if (r == null) return emptyList()
    val recent = r.recentDecks.take(n).map { it.cards }
    return recent.ifEmpty { listOfNotNull(r.lastBattle?.cards) }
}

/** Width (dp) of the minimal deck card: one deck with icons [iconDp] wide, plus arrows if [paged]. */
fun miniDeckWidthDp(iconDp: Float, paged: Boolean): Float =
    MINI_DECK_GAP_DP * 2 + iconDp * 4 + MINI_DECK_GAP_DP * 3 + (if (paged) MINI_DECK_ARROW_DP * 2 else 0f)

/** Icon width (dp) so the card (with arrows if [paged]) fits in [maxWidthDp] (the screen in overlay dp). */
fun miniDeckIconDp(maxWidthDp: Float, paged: Boolean): Float {
    val fit = (maxWidthDp - miniDeckWidthDp(0f, paged)) / 4
    return minOf(MINI_DECK_ICON_DP, fit).coerceAtLeast(MINI_DECK_MIN_ICON_DP)
}

/** Screen width available to the overlay, in overlay dp ([scale] is the overlay size setting). */
fun overlayMaxWidthDp(screenWidthDp: Float, scale: Float): Float = screenWidthDp / scale - MINI_DECK_SCREEN_MARGIN_DP * 2

/** "Name · Clan" of the matched player for the deck card; null when nothing was found. */
fun matchedNameLine(r: LookupResult?): Pair<String, String?>? {
    if (r == null) return null
    val name = r.stats?.name ?: r.hit?.name ?: return null
    val clan = (r.stats?.clanName ?: r.hit?.clanName)?.takeIf { it.isNotBlank() }
    return name to clan
}

sealed interface PanelState {
    data object Hidden : PanelState

    /** A finished scan. [result] is null when no lookup ran. */
    data class Result(val lines: List<OcrLine>, val pick: NamePick, val result: LookupResult?) : PanelState

    /** Nothing usable: no name on screen, capture refused, error... */
    data class NotFound(val message: String, val lines: List<OcrLine> = emptyList(), val pick: NamePick? = null) : PanelState

    val linesOrEmpty: List<OcrLine>
        get() = when (this) {
            is Result -> lines
            is NotFound -> lines
            Hidden -> emptyList()
        }
}

/**
 * Width of the window in unscaled dp (was `LocalConfiguration.screenWidthDp`). Inside [Scaled] the
 * density already includes [scale], so it is divided out again.
 */
@Composable
private fun screenWidthDp(scale: Float): Float {
    val px = LocalWindowInfo.current.containerSize.width
    return px / LocalDensity.current.density * scale
}

/** Scales dp and sp of [content] by [scale] (overlay size setting). */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
}

@Composable
fun Bubble(busy: Boolean, icon: Painter = painterResource(Res.drawable.ic_bubble), alpha: Float = 0.7f) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Royal.Navy)
            .border(2.dp, Royal.Gold.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(30.dp), color = Royal.Gold, strokeWidth = 3.dp)
        } else {
            Icon(icon, contentDescription = "Scan opponent", tint = Royal.Gold, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * Transient result card. Unpinned it is compact and the service hides it after a few seconds;
 * tapping it pins it (timer cancelled) and reveals the OCR line list and actions.
 */
@Composable
fun Panel(
    state: PanelState,
    pinned: Boolean,
    onPin: () -> Unit,
    onClose: () -> Unit,
    onRetry: (() -> Unit)?,
    onPickLine: (OcrLine) -> Unit,
    onVerify: () -> Unit,
    alpha: Float = 0.95f,
    deckOnly: Boolean = false,
    onRefresh: ((String) -> Unit)? = null,
    onOpenWeb: ((String) -> Unit)? = null,
    showCloseHint: Boolean = true,
    decksToShow: Int = 3,
    scale: Float = 1f,
    /** Width the minimal deck card may use, in overlay dp; defaults to the screen width. */
    maxWidthDp: Float? = null,
    /** Which recent deck is shown (0 = newest); clamped to the decks there are. */
    deckIndex: Int = 0,
    /** Steps to an older (+1) or newer (-1) deck; null hides the arrows. */
    onDeckStep: ((Int) -> Unit)? = null,
    /** Shows "1/3" on the name line when there are several decks (by default only with the arrows). */
    deckCounter: Boolean = onDeckStep != null,
) {
    val decks = decksFor((state as? PanelState.Result)?.result, decksToShow)
    if (deckOnly && !pinned && decks.isNotEmpty()) {
        // Minimal transient deck card: the matched name, then one deck (newest first). With
        // several decks, arrows beside the grid step through them. Tap the card to pin it.
        val mini = RoundedCornerShape(8.dp)
        val paged = decks.size > 1 && onDeckStep != null
        val counted = decks.size > 1 && (paged || deckCounter)
        val page = deckPageIndex(deckIndex, decks.size)
        val maxW = maxWidthDp ?: overlayMaxWidthDp(screenWidthDp(scale), scale)
        val iconDp = miniDeckIconDp(maxW, paged)
        val iconW = iconDp.dp
        val gridW = iconW * 4 + MINI_DECK_GAP * 3
        Column(
            Modifier
                .width(miniDeckWidthDp(iconDp, paged).dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(mini)
                .background(Royal.Night.copy(alpha = 0.55f))
                .border(1.dp, Royal.Gold.copy(alpha = 0.3f), mini)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPin)
                .padding(MINI_DECK_GAP)
        ) {
            val r = (state as? PanelState.Result)?.result
            val nameLine = matchedNameLine(r)
            if (nameLine != null || counted) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = MINI_DECK_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        nameLine?.let { (name, clan) -> MiniNameLine(name, clan, guess = r?.strongMatch == false) }
                    }
                    if (counted) {
                        Text(
                            "${page + 1}/${decks.size}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                            color = Royal.Gold.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val arrow = Modifier.width(MINI_DECK_ARROW_DP.dp).height(iconW * 2.4f)
                if (paged) DeckArrow(left = true, enabled = page > 0, arrow) { onDeckStep?.invoke(-1) }
                Box(Modifier.width(gridW)) { DeckGrid(decks[page], showLevels = false, gap = MINI_DECK_GAP) }
                if (paged) DeckArrow(left = false, enabled = page < decks.size - 1, arrow) { onDeckStep?.invoke(1) }
            }
        }
        return
    }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
    Column(
        Modifier
            .width(264.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(Royal.Navy)
            .border(1.dp, Royal.Gold.copy(alpha = if (pinned) 0.8f else 0.35f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPin)
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (state is PanelState.NotFound) Royal.Danger else Royal.Gold))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (!showCloseHint) "Opponent" else if (pinned) "Opponent, pinned" else "Opponent, tap to pin",
                style = MaterialTheme.typography.labelSmall,
                color = Royal.Mist,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(RoyalIcons.Close, contentDescription = "Close", tint = Royal.Mist, modifier = Modifier.size(18.dp))
            }
        }
        Column(
            Modifier
                .heightIn(max = if (pinned) 440.dp else 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
        ) {
            when (state) {
                PanelState.Hidden -> Unit
                is PanelState.NotFound -> {
                    state.pick?.name?.let { NameHeader(it.text, state.pick.clan?.text, null) }
                    Text(state.message, color = Royal.Danger, style = MaterialTheme.typography.bodyMedium)
                }
                is PanelState.Result -> ResultContent(state, onVerify, deckOnly, pinned, decksToShow, deckIndex, onDeckStep)
            }
            if (pinned) PinnedExtras(state, onRetry, onPickLine, onVerify, onRefresh, onOpenWeb)
        }
    }
}

/** One compact line over the minimal deck card: name, clan and a "?" for a best guess. */
@Composable
private fun MiniNameLine(name: String, clan: String?, guess: Boolean) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Royal.Elixir, fontWeight = FontWeight.SemiBold)) { append(name) }
        if (clan != null) withStyle(SpanStyle(color = Royal.Mist)) { append("  \u00b7  $clan") }
        if (guess) withStyle(SpanStyle(color = Royal.Gold)) { append("  ?") }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Small chevron that steps through the decks. It always consumes the tap (even at the end, where it
 * is dimmed and does nothing) so a near miss never pins the card.
 */
@Composable
private fun DeckArrow(left: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) RoyalIcons.ChevronLeft else RoyalIcons.ChevronRight,
            contentDescription = if (left) "Newer deck" else "Older deck",
            tint = Royal.Gold.copy(alpha = if (enabled) 0.7f else 0.18f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NameHeader(name: String, clan: String?, tag: String?) {
    Text(name, style = MaterialTheme.typography.headlineSmall, color = Royal.Elixir, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (tag != null) Text("#$tag", style = MaterialTheme.typography.labelLarge, color = Royal.Gold)
    if (!clan.isNullOrBlank()) Text(clan, style = MaterialTheme.typography.bodySmall, color = Royal.Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ResultContent(
    state: PanelState.Result,
    onVerify: () -> Unit,
    deckOnly: Boolean,
    pinned: Boolean,
    decksToShow: Int = 3,
    deckIndex: Int = 0,
    onDeckStep: ((Int) -> Unit)? = null,
) {
    val r = state.result
    val s = r?.stats
    if (r == null || s == null) {
        NameHeader(state.pick.name?.text ?: "?", state.pick.clan?.text, null)
        Text(r?.error ?: "Not found", color = Royal.Danger, style = MaterialTheme.typography.bodyMedium)
        if (r?.needsVerification == true) {
            Text(
                "RoyaleAPI wants a one-time check.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Gold,
                modifier = Modifier.clickable(onClick = onVerify).padding(top = 2.dp),
            )
        }
        return
    }
    val battle = r.lastBattle
    if (deckOnly && battle != null) {
        // Deck view: name/tag line, the 8 cards, and when/where the battle was.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, color = Royal.Elixir, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text("#${s.tag}", style = MaterialTheme.typography.labelSmall, color = Royal.Gold)
        }
        s.clanName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Royal.Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val recent = r.recentDecks.take(decksToShow)
        if (recent.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            DeckGrid(battle)
            Text(
                listOfNotNull(battle.mode, BattleLog.relative(battle.time)).joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = Royal.Mist,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // One deck at a time, newest first; the arrows step through the others.
            val page = deckPageIndex(deckIndex, recent.size)
            val d = recent[page]
            if (recent.size > 1 && onDeckStep != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (page == 0) "Newest deck, 1 of ${recent.size}" else "Deck ${page + 1} of ${recent.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Royal.Mist,
                        modifier = Modifier.weight(1f),
                    )
                    DeckArrow(left = true, enabled = page > 0, Modifier.size(28.dp, 24.dp)) { onDeckStep(-1) }
                    DeckArrow(left = false, enabled = page < recent.size - 1, Modifier.size(28.dp, 24.dp)) { onDeckStep(1) }
                }
                Spacer(Modifier.height(2.dp))
            } else {
                Spacer(Modifier.height(6.dp))
            }
            DeckGrid(d.cards)
            Text(
                BattleLog.usageLine(d),
                style = MaterialTheme.typography.labelSmall,
                color = Royal.Mist,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!pinned) return
        Spacer(Modifier.height(10.dp))
    }
    NameHeader(s.name, s.clanName, s.tag)
    Spacer(Modifier.height(8.dp))
    StatRow(s)
    if (deckOnly && battle == null) {
        Text(
            "Last-battle deck unavailable" + (r.lastBattleError?.let { ": $it" } ?: "") + ". Showing stats.",
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Gold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        matchNote(r),
        style = MaterialTheme.typography.labelSmall,
        color = if (r.strongMatch) Royal.Mist else Royal.Gold,
    )
}

/** Two rows of four card images with their levels. */
@Composable
fun DeckGrid(battle: LastBattle, showLevels: Boolean = true, gap: androidx.compose.ui.unit.Dp = 4.dp) =
    DeckGrid(battle.cards, showLevels, gap)

@Composable
fun DeckGrid(cards: List<DeckCard>, showLevels: Boolean = true, gap: androidx.compose.ui.unit.Dp = 4.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        cards.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { card ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = card.iconUrl,
                            contentDescription = card.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(5f / 6f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Royal.Night.copy(alpha = 0.5f)),
                        )
                        if (showLevels) card.level?.let {
                            Text("Lv $it", style = MaterialTheme.typography.labelSmall, color = Royal.Parchment, maxLines = 1)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun matchNote(r: LookupResult): String {
    val how = when {
        r.strategy == null -> ""
        r.strategy.startsWith("L1") -> "exact name search"
        r.strategy.startsWith("L2v") -> "search with OCR fixes"
        r.strategy.startsWith("L2p") -> "name prefix search"
        r.strategy.startsWith("L2c") -> "clan member list"
        r.strategy.startsWith("L2") -> "fuzzy name search"
        r.strategy.startsWith("L3") -> "official API clan list"
        else -> r.strategy
    }
    val pct = (r.confidence * 100).toInt()
    val base = if (r.strongMatch) "$pct% match via $how" else "Best guess, $pct% match via $how; name or clan differ"
    return r.note?.let { "$base. $it" } ?: base
}

@Composable
private fun StatRow(s: PlayerStats) {
    val winRate = if (s.wins != null && s.losses != null && s.wins + s.losses > 0) "${s.wins * 100 / (s.wins + s.losses)}%" else null
    val cells = listOfNotNull(
        s.trophies?.let { formatGrouped(it) to "trophies" },
        s.bestTrophies?.let { formatGrouped(it) to "best" },
        s.expLevel?.let { "$it" to "level" },
        winRate?.let { it to "win rate" } ?: s.battleCount?.let { formatGrouped(it) to "games" },
    )
    if (cells.isEmpty()) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Royal.Night.copy(alpha = 0.6f)).padding(vertical = 6.dp)) {
        cells.forEach { (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = Royal.Parchment, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Royal.Mist)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedExtras(
    state: PanelState,
    onRetry: (() -> Unit)?,
    onPickLine: (OcrLine) -> Unit,
    onVerify: () -> Unit,
    onRefresh: ((String) -> Unit)?,
    onOpenWeb: ((String) -> Unit)?,
) {
    val r = (state as? PanelState.Result)?.result
    val tag = r?.stats?.tag
    r?.fetchedAt?.let { at ->
        Text(
            (if (r.screenHit) "Same screen as before (cache hit by screenshot), fetched " else if (r.cached) "Cached, fetched " else "Fetched ") +
                CachePolicy.age(at, nowMillis()),
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    r?.alternatives?.takeIf { it.isNotEmpty() }?.let { alts ->
        Text(
            "Other matches: " + alts.take(3).joinToString { "${it.name} #${it.tag}" },
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    val picked = when (state) {
        is PanelState.Result -> state.pick.name
        is PanelState.NotFound -> state.pick?.name
        else -> null
    }
    val lines = state.linesOrEmpty
    if (lines.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Wrong name? Tap the right line", style = MaterialTheme.typography.bodySmall, color = Royal.Mist)
        lines.forEach { line ->
            val selected = picked != null && line.box == picked.box && line.text == picked.text
            Text(
                line.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Royal.Gold else Royal.Parchment,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Royal.Slate else Color.Transparent)
                    .clickable { onPickLine(line) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onRetry != null) PillButton("Scan again", primary = true, onClick = onRetry)
        if (tag != null && onRefresh != null) PillButton("Refresh", primary = false) { onRefresh(tag) }
        if (tag != null && onOpenWeb != null) PillButton("Open on RoyaleAPI", primary = false) { onOpenWeb(tag) }
        if (r?.needsVerification == true) PillButton("Verify RoyaleAPI", primary = false, onClick = onVerify)
    }
}

@Composable
fun PillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Royal.Night else Royal.Gold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Royal.Gold else Royal.Night.copy(alpha = 0.6f))
            .border(1.5.dp, Royal.Gold, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- scan region

/**
 * Full-screen editor: the rectangle is the scan region over the live game. Drag inside it to move,
 * drag a corner to resize. The window covers the whole display, so pixels map 1:1 to the screen.
 */
@Composable
fun RegionEditor(initial: ScanRegion, onSave: (ScanRegion) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val minPx = with(LocalDensity.current) { 48.dp.toPx() }
        val handlePx = with(LocalDensity.current) { 36.dp.toPx() }
        var rect by remember(initial, w, h) {
            mutableStateOf(Rect(initial.left * w, initial.top * h, initial.right * w, initial.bottom * h))
        }

        fun clampMove(r: Rect, d: Offset): Rect {
            val dx = d.x.coerceIn(-r.left, w - r.right)
            val dy = d.y.coerceIn(-r.top, h - r.bottom)
            return r.translate(dx, dy)
        }

        RegionOverlay(rect, dim = 0.55f, handles = true)

        // One full-screen gesture layer (it does not move, so drag deltas stay clean). The grab
        // point decides the mode: near a corner resizes, inside moves.
        var mode by remember { mutableStateOf<String?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(w, h) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val r = rect
                            val grab = handlePx
                            fun near(x: Float, y: Float) = kotlin.math.abs(p.x - x) < grab && kotlin.math.abs(p.y - y) < grab
                            mode = when {
                                near(r.left, r.top) -> "tl"
                                near(r.right, r.top) -> "tr"
                                near(r.left, r.bottom) -> "bl"
                                near(r.right, r.bottom) -> "br"
                                r.contains(p) -> "move"
                                else -> null
                            }
                        },
                        onDragEnd = { mode = null },
                        onDragCancel = { mode = null },
                    ) { c, d ->
                        val m = mode ?: return@detectDragGestures
                        c.consume()
                        val r = rect
                        rect = when (m) {
                            "move" -> clampMove(r, d)
                            "tl" -> Rect((r.left + d.x).coerceIn(0f, r.right - minPx), (r.top + d.y).coerceIn(0f, r.bottom - minPx), r.right, r.bottom)
                            "tr" -> Rect(r.left, (r.top + d.y).coerceIn(0f, r.bottom - minPx), (r.right + d.x).coerceIn(r.left + minPx, w), r.bottom)
                            "bl" -> Rect((r.left + d.x).coerceIn(0f, r.right - minPx), r.top, r.right, (r.bottom + d.y).coerceIn(r.top + minPx, h))
                            else -> Rect(r.left, r.top, (r.right + d.x).coerceIn(r.left + minPx, w), (r.bottom + d.y).coerceIn(r.top + minPx, h))
                        }
                    }
                }
        )

        // Toolbar sits below the rectangle when there is room, else above it.
        val below = rect.bottom < h * 0.6f
        val toolbarY = with(LocalDensity.current) { (if (below) rect.bottom + 24.dp.toPx() else rect.top - 190.dp.toPx()).coerceAtLeast(0f).toDp() }
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = toolbarY)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Royal.Navy.copy(alpha = 0.95f))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Put the box over the opponent's name and clan. Drag inside to move, drag a corner to resize.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Parchment,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Save region", primary = true) {
                    onSave(ScanRegion(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h).normalized())
                }
                PillButton("Reset", primary = false, onClick = onReset)
                PillButton("Cancel", primary = false, onClick = onCancel)
            }
        }
    }
}

/** Non-interactive flash of the saved region so the user can check the alignment. */
@Composable
fun RegionPreview(region: ScanRegion) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "alpha",
        )
        RegionOverlay(Rect(region.left * w, region.top * h, region.right * w, region.bottom * h), dim = 0.25f, strokeAlpha = pulse)
    }
}

@Composable
private fun RegionOverlay(rect: Rect, dim: Float, strokeAlpha: Float = 1f, handles: Boolean = false) {
    Canvas(Modifier.fillMaxSize()) {
        val shade = Color.Black.copy(alpha = dim)
        drawRect(shade, Offset.Zero, Size(size.width, rect.top))
        drawRect(shade, Offset(0f, rect.bottom), Size(size.width, size.height - rect.bottom))
        drawRect(shade, Offset(0f, rect.top), Size(rect.left, rect.height))
        drawRect(shade, Offset(rect.right, rect.top), Size(size.width - rect.right, rect.height))
        drawRect(
            Royal.Gold.copy(alpha = strokeAlpha), rect.topLeft, rect.size,
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f))),
        )
        if (handles) {
            listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach { c ->
                drawCircle(Royal.Night, radius = 11.dp.toPx(), center = c)
                drawCircle(Royal.Gold, radius = 8.dp.toPx(), center = c)
            }
        }
    }
}
