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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.R
import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.lookup.LastBattle
import com.macle.royaleassist.lookup.LookupResult
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.ui.Royal
import kotlin.math.roundToInt

/** Minimal deck card: 4 icons of ~46 dp, 3 dp gaps and edge. */
val MINI_DECK_GAP = 3.dp
val MINI_DECK_WIDTH = 46.dp * 4 + MINI_DECK_GAP * 5

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

/** Scales dp and sp of [content] by [scale] (overlay size setting). */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale), content = content)
}

@Composable
fun Bubble(busy: Boolean, alpha: Float = 0.7f) {
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
            Icon(painterResource(R.drawable.ic_bubble), contentDescription = "Scan opponent", tint = Royal.Gold, modifier = Modifier.size(30.dp))
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
    onRetry: () -> Unit,
    onPickLine: (OcrLine) -> Unit,
    onVerify: () -> Unit,
    alpha: Float = 0.95f,
    deckOnly: Boolean = false,
) {
    val battle = (state as? PanelState.Result)?.result?.lastBattle
    if (deckOnly && !pinned && battle != null) {
        // Minimal transient deck card: just the 8 icons. Tap to pin for the full layout.
        val mini = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .width(MINI_DECK_WIDTH)
                .graphicsLayer { this.alpha = alpha }
                .clip(mini)
                .background(Royal.Night.copy(alpha = 0.55f))
                .border(1.dp, Royal.Gold.copy(alpha = 0.3f), mini)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPin)
                .padding(MINI_DECK_GAP)
        ) {
            DeckGrid(battle, showLevels = false, gap = MINI_DECK_GAP)
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
                if (pinned) "Opponent, pinned" else "Opponent, tap to pin",
                style = MaterialTheme.typography.labelSmall,
                color = Royal.Mist,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Royal.Mist, modifier = Modifier.size(18.dp))
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
                is PanelState.Result -> ResultContent(state, onVerify, deckOnly, pinned)
            }
            if (pinned) PinnedExtras(state, onRetry, onPickLine, onVerify)
        }
    }
}

@Composable
private fun NameHeader(name: String, clan: String?, tag: String?) {
    Text(name, style = MaterialTheme.typography.headlineSmall, color = Royal.Elixir, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (tag != null) Text("#$tag", style = MaterialTheme.typography.labelLarge, color = Royal.Gold)
    if (!clan.isNullOrBlank()) Text(clan, style = MaterialTheme.typography.bodySmall, color = Royal.Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ResultContent(state: PanelState.Result, onVerify: () -> Unit, deckOnly: Boolean, pinned: Boolean) {
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
        Spacer(Modifier.height(6.dp))
        DeckGrid(battle)
        Text(
            listOfNotNull(battle.mode, BattleLog.relative(battle.time)).joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
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
fun DeckGrid(battle: LastBattle, showLevels: Boolean = true, gap: androidx.compose.ui.unit.Dp = 4.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        battle.cards.chunked(4).forEach { row ->
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
        r.strategy.startsWith("L2c") -> "clan member list"
        r.strategy.startsWith("L2") -> "fuzzy name search"
        r.strategy.startsWith("L3") -> "official API clan list"
        else -> r.strategy
    }
    val pct = (r.confidence * 100).toInt()
    return if (r.strongMatch) "$pct% match via $how" else "Best guess, $pct% match via $how; name or clan differ"
}

@Composable
private fun StatRow(s: PlayerStats) {
    val winRate = if (s.wins != null && s.losses != null && s.wins + s.losses > 0) "${s.wins * 100 / (s.wins + s.losses)}%" else null
    val cells = listOfNotNull(
        s.trophies?.let { "%,d".format(it) to "trophies" },
        s.bestTrophies?.let { "%,d".format(it) to "best" },
        s.expLevel?.let { "$it" to "level" },
        winRate?.let { it to "win rate" } ?: s.battleCount?.let { "%,d".format(it) to "games" },
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

@Composable
private fun PinnedExtras(state: PanelState, onRetry: () -> Unit, onPickLine: (OcrLine) -> Unit, onVerify: () -> Unit) {
    val r = (state as? PanelState.Result)?.result
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("Scan again", primary = true, onClick = onRetry)
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
