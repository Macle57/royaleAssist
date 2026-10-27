package com.macle.royaleassist.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.R
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.PlayerStats
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ui.Royal

sealed interface PanelState {
    data object Hidden : PanelState
    data class Working(val step: String, val lines: List<OcrLine> = emptyList(), val pick: NamePick? = null) : PanelState
    data class Done(val lines: List<OcrLine>, val pick: NamePick, val result: LookupResult?) : PanelState
    data class Failed(val message: String, val lines: List<OcrLine> = emptyList(), val pick: NamePick? = null) : PanelState
}

@Composable
fun Bubble(busy: Boolean) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Royal.Navy.copy(alpha = 0.72f))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Panel(
    state: PanelState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPickLine: (OcrLine) -> Unit,
    onVerify: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(
        Modifier
            .width(300.dp)
            .clip(shape)
            .background(Royal.Navy.copy(alpha = 0.96f))
            .border(1.dp, Royal.Gold.copy(alpha = 0.35f), shape)
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Royal.Gold))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opponent", style = MaterialTheme.typography.bodySmall, color = Royal.Mist, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Royal.Mist)
            }
        }
        Column(
            Modifier
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
        ) {
            when (state) {
                PanelState.Hidden -> Unit
                is PanelState.Working -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Royal.Gold, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(state.step, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.pick?.name?.let { NameHeader(it.text, state.pick.clan?.text, null) }
                }
                is PanelState.Failed -> {
                    state.pick?.name?.let { NameHeader(it.text, state.pick.clan?.text, null) }
                    Text(state.message, color = Royal.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
                is PanelState.Done -> DoneContent(state, onVerify)
            }
            val lines = when (state) {
                is PanelState.Done -> state.lines
                is PanelState.Failed -> state.lines
                is PanelState.Working -> state.lines
                else -> emptyList()
            }
            val pickedName = when (state) {
                is PanelState.Done -> state.pick.name
                is PanelState.Failed -> state.pick?.name
                is PanelState.Working -> state.pick?.name
                else -> null
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Wrong name? Tap the right line", style = MaterialTheme.typography.bodySmall, color = Royal.Mist)
                Spacer(Modifier.height(4.dp))
                lines.forEach { line ->
                    val selected = pickedName != null && line.box == pickedName.box && line.text == pickedName.text
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
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Scan again", primary = true, onClick = onRetry)
                if (state is PanelState.Done && state.result?.needsVerification == true) {
                    PillButton("Verify RoyaleAPI", primary = false, onClick = onVerify)
                }
            }
        }
    }
}

@Composable
private fun NameHeader(name: String, clan: String?, tag: String?) {
    Text(
        name,
        style = MaterialTheme.typography.headlineSmall,
        color = Royal.Elixir,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (tag != null) Text("#$tag", style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
    if (!clan.isNullOrBlank()) Text(clan, style = MaterialTheme.typography.bodyMedium, color = Royal.Parchment)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoneContent(state: PanelState.Done, onVerify: () -> Unit) {
    val r = state.result
    val s = r?.stats
    if (s == null) {
        NameHeader(state.pick.name?.text ?: "?", state.pick.clan?.text, null)
        Text(
            r?.error ?: "No lookup was run.",
            color = Royal.Danger,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (r?.needsVerification == true) {
            Text(
                "RoyaleAPI wants a one-time browser check. Tap Verify RoyaleAPI, pass the check, and the lookup runs again.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }
    NameHeader(s.name, s.clanName?.let { c -> s.clanRole?.let { "$c, $it" } ?: c }, s.tag)
    Spacer(Modifier.height(10.dp))
    StatRow(s)
    if (s.deck.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Current deck", style = MaterialTheme.typography.bodySmall, color = Royal.Mist)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.deck.forEach { card ->
                Text(
                    card,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Royal.Slate).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
    if (s.extra.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        s.extra.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth()) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = Royal.Mist, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Read \"${r.query}\"${r.clanQuery?.let { " / \"$it\"" } ?: ""}. Found with ${r.strategy}, " +
            "match ${(r.confidence * 100).toInt()}%. Stats from ${s.source}.",
        style = MaterialTheme.typography.labelSmall,
        color = Royal.Mist,
    )
    if (r.alternatives.isNotEmpty()) {
        Text(
            "Other matches: " + r.alternatives.take(3).joinToString { "${it.name} #${it.tag}" },
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Mist,
        )
    }
    if (r.needsVerification) {
        Text(
            "Some RoyaleAPI requests were blocked. Verify RoyaleAPI to get more detail.",
            style = MaterialTheme.typography.labelSmall,
            color = Royal.Gold,
            modifier = Modifier.clickable(onClick = onVerify),
        )
    }
}

@Composable
private fun StatRow(s: PlayerStats) {
    val winRate = if (s.wins != null && s.losses != null && s.wins + s.losses > 0) {
        "${s.wins * 100 / (s.wins + s.losses)}%"
    } else null
    val cells = listOfNotNull(
        s.trophies?.let { "%,d".format(it) to "trophies" },
        s.bestTrophies?.let { "%,d".format(it) to "best" },
        s.expLevel?.let { "$it" to "level" },
        winRate?.let { it to "win rate" },
        s.threeCrownWins?.let { "%,d".format(it) to "3-crowns" },
        s.battleCount?.takeIf { winRate == null }?.let { "%,d".format(it) to "games" },
    ).take(4)
    if (cells.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Royal.Night.copy(alpha = 0.6f)).padding(vertical = 8.dp),
    ) {
        cells.forEach { (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleMedium, color = Royal.Parchment)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Royal.Mist)
            }
        }
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Royal.Night else Royal.Gold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Royal.Gold else Color.Transparent)
            .border(1.5.dp, Royal.Gold, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
