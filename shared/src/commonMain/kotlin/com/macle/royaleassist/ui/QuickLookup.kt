package com.macle.royaleassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.ScreenHint
import com.macle.royaleassist.overlay.PillButton
import com.macle.royaleassist.settings.OverlayPrefs
import com.macle.royaleassist.settings.Settings
import kotlinx.coroutines.launch

/**
 * Manual lookup for platforms without screen reading (iOS for now): type the opponent's name (and
 * clan), get the deck card. Uses the deck settings of the setup screen.
 */
@Composable
fun QuickLookup(
    lookup: LookupChain,
    settings: Settings,
    navigator: AppNavigator,
    onResult: (LookupResult) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val prefs by settings.overlayPrefs.collectAsState(initial = OverlayPrefs())
    var name by remember { mutableStateOf("") }
    var clan by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    fun run() {
        if (busy || name.isBlank()) return
        scope.launch {
            busy = true
            val r = lookup.lookup(name.trim(), ScreenHint(clan = clan.trim().ifBlank { null }))
            result = r
            busy = false
            onResult(r)
        }
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Royal.Gold,
        unfocusedBorderColor = Royal.Slate,
        focusedLabelColor = Royal.Gold,
        cursorColor = Royal.Gold,
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Royal.Navy).padding(16.dp)
    ) {
        Text("Look up a player", style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Opponent name or #tag") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = colors, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = clan, onValueChange = { clan = it }, label = { Text("Clan (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
            colors = colors, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 10.dp)) {
            PillButton(if (busy) "Looking up..." else "Look up", primary = true) { run() }
        }
        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            DeckCard(
                r,
                deckOnly = true,
                decksToShow = prefs.decksToShow,
                alpha = 1f,
                onVerify = { navigator.openVerify(r.query) },
                onOpenWeb = { navigator.openPlayerPage(it) },
                onRefresh = { _ -> scope.launch { result = lookup.refresh(r) } },
                onClose = { result = null },
            )
        }
    }
}
