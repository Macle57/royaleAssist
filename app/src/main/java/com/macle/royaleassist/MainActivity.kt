package com.macle.royaleassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.macle.royaleassist.capture.ConsentActivity
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.OfficialApi
import com.macle.royaleassist.lookup.VerifyActivity
import com.macle.royaleassist.overlay.OverlayService
import com.macle.royaleassist.overlay.DeckGrid
import com.macle.royaleassist.lookup.BattleLog
import com.macle.royaleassist.settings.OverlayPrefs
import com.macle.royaleassist.cache.CacheTtl
import com.macle.royaleassist.debug.ScanRecorder
import kotlin.math.roundToInt
import com.macle.royaleassist.ui.Royal
import com.macle.royaleassist.ui.RoyalTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark UI: light status/navigation bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RoyaleAssistApp
        app.appScope.launch { app.ocr.ensureModels(applicationContext) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { RoyalTheme { SetupScreen(app) } }
    }
}

@Composable
private fun SetupScreen(app: RoyaleAssistApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val status by OverlayService.status.collectAsState()
    val savedToken by app.settings.apiToken.collectAsState(initial = null)
    val region by app.settings.scanRegion.collectAsState(initial = com.macle.royaleassist.ocr.ScanRegion.DEFAULT)
    val fullFallback by app.settings.fullScreenFallback.collectAsState(initial = false)
    var tokenField by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("Macle") }
    var clanQuery by remember { mutableStateOf("Kings & Queens") }
    var lookupBusy by remember { mutableStateOf(false) }
    var lookupResult by remember { mutableStateOf<LookupResult?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { canOverlay = Settings.canDrawOverlays(context) }
    }

    val overlay by app.settings.overlayPrefs.collectAsState(initial = OverlayPrefs())
    val cacheTtl by app.settings.cacheTtl.collectAsState(initial = CacheTtl.DEFAULT)
    val saveScans by app.settings.saveScans.collectAsState(initial = BuildConfig.DEBUG)
    var scanStats by remember { mutableStateOf(ScanRecorder.Stats(0, 0)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            scanStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.recorder.stats() }
        }
    }
    fun setOverlay(change: (OverlayPrefs) -> OverlayPrefs) { scope.launch { app.settings.updateOverlayPrefs(change) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Royal.Night)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("royaleAssist", style = MaterialTheme.typography.headlineSmall, color = Royal.Gold, modifier = Modifier.weight(1f))
                Pill("History", primary = false) { context.startActivity(HistoryActivity.intent(context)) }
            }
            Text(
                "Tap the crown bubble in a battle and royaleAssist reads the opponent's name off the screen and looks them up.",
                style = MaterialTheme.typography.bodyMedium,
                color = Royal.Mist,
            )
        }

        SectionCard("Permissions") {
            Step(
                done = canOverlay,
                title = "Show the bubble over other apps",
                body = "Android asks you to switch on \"Display over other apps\" for royaleAssist.",
                action = if (canOverlay) null else "Open setting" to {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                },
            )
            Step(
                done = status.capturing,
                title = "Allow screen capture",
                body = "Needed to read names off the game screen. Android may ask again after a restart or when the phone locks; the bubble then asks for you.",
                action = (if (status.capturing) "Ask again" else "Allow") to {
                    if (!status.running && canOverlay) OverlayService.start(context)
                    context.startActivity(ConsentActivity.intent(context))
                },
            )
            Step(
                done = status.running,
                title = if (status.running) "Bubble is on" else "Start the bubble",
                body = "The bubble stays on top of the game until you stop it here or from the notification.",
                action = if (status.running) "Stop bubble" to { OverlayService.stop(context) }
                else "Start bubble" to { if (canOverlay) OverlayService.start(context) },
                last = true,
            )
        }

        SectionCard("Overlay") {
            SliderRow(
                label = "Bubble opacity",
                value = overlay.bubbleAlpha,
                valueText = "${(overlay.bubbleAlpha * 100).roundToInt()}%",
                range = OverlayPrefs.MIN_ALPHA..1f,
                steps = 13,
            ) { v -> setOverlay { it.copy(bubbleAlpha = v) } }
            SliderRow(
                label = "Panel opacity",
                value = overlay.panelAlpha,
                valueText = "${(overlay.panelAlpha * 100).roundToInt()}%",
                range = OverlayPrefs.MIN_ALPHA..1f,
                steps = 13,
            ) { v -> setOverlay { it.copy(panelAlpha = v) } }
            SliderRow(
                label = "Size",
                value = overlay.scale,
                valueText = sizeLabel(overlay.scale),
                range = 0.75f..1.5f,
                steps = 14,
            ) { v -> setOverlay { it.copy(scale = v) } }
            SliderRow(
                label = "Hide results after",
                value = overlay.autoHideSeconds.toFloat(),
                valueText = if (overlay.alwaysPin) "never" else "${overlay.autoHideSeconds} s",
                range = 2f..30f,
                steps = 27,
                enabled = !overlay.alwaysPin,
            ) { v -> setOverlay { it.copy(autoHideSeconds = v.roundToInt()) } }
            SwitchRow(
                title = "Always pin",
                body = "Results stay until you close them.",
                checked = overlay.alwaysPin,
            ) { on -> setOverlay { it.copy(alwaysPin = on) } }
            SwitchRow(
                title = "Show only the last-battle deck",
                body = "The card shows the opponent's 8 cards from their most recent battle instead of stats. Pin it to see both.",
                checked = overlay.deckOnly,
            ) { on -> setOverlay { it.copy(deckOnly = on) } }
        }

        SectionCard("Scan region") {
            Text(
                "Only this part of the screen is read, which is much faster than the whole screen. " +
                    "Open a battle, long-press the bubble (or tap Set scan region and switch to the game), " +
                    "then drag the box over the opponent's name and clan.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
            )
            Text(
                "Now: x %d–%d%%, y %d–%d%% of the screen".format(
                    (region.left * 100).toInt(), (region.right * 100).toInt(), (region.top * 100).toInt(), (region.bottom * 100).toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Pill("Set scan region", primary = true) {
                    if (canOverlay) OverlayService.send(context, OverlayService.ACTION_EDIT_REGION)
                }
                Pill("Show scan region", primary = false) {
                    if (canOverlay) OverlayService.send(context, OverlayService.ACTION_SHOW_REGION)
                }
            }
            SwitchRow(
                title = "Full-screen fallback",
                body = "If the region has no name, read the whole screen too. Slower.",
                checked = fullFallback,
            ) { on -> scope.launch { app.settings.setFullScreenFallback(on) } }
        }

        SectionCard("Lookup") {
            Text(
                "Clash Royale API token (optional). With a token the card shows wins, losses and the deck from the official API, " +
                    "and players can be found through their clan. Create a key at developer.clashroyale.com with the IP " +
                    "${OfficialApi.PROXY_IP} (the RoyaleAPI proxy).",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
            )
            Spacer(Modifier.height(8.dp))
            RoyalField(
                value = tokenField,
                onChange = { tokenField = it; tokenSaved = false },
                label = if (savedToken != null) "Token saved, paste to replace" else "Paste token",
                secret = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Pill("Save token", primary = true) {
                    scope.launch { app.settings.setApiToken(tokenField); tokenField = ""; tokenSaved = true }
                }
                if (savedToken != null) Pill("Remove token", primary = false) {
                    scope.launch { app.settings.setApiToken(null); tokenSaved = false }
                }
            }
            if (tokenSaved) Text("Token saved.", color = Royal.Gold, style = MaterialTheme.typography.bodySmall)
            Text(
                "Without a token, lookups use royaleapi.com, which asks for a one-time browser check.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 6.dp)) {
                Pill("Verify RoyaleAPI", primary = false) { context.startActivity(VerifyActivity.intent(context, query)) }
            }
            Text("Cache for", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp))
            Text(
                "A player scanned again within this time is shown instantly from the phone, without network. Refresh in the pinned card fetches fresh data.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                CacheTtl.entries.forEach { t ->
                    ChoiceChip(t.label, selected = t == cacheTtl) { scope.launch { app.settings.setCacheTtl(t) } }
                }
            }
        }

        SectionCard("Debug") {
            Text(
                "Type a player name (or a #tag) to check the lookup without the game. Uses the settings above, including the deck view.",
                style = MaterialTheme.typography.bodySmall,
                color = Royal.Mist,
            )
            Spacer(Modifier.height(8.dp))
            RoyalField(value = query, onChange = { query = it }, label = "Player name or #tag")
            Spacer(Modifier.height(6.dp))
            RoyalField(value = clanQuery, onChange = { clanQuery = it }, label = "Clan name (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Pill(if (lookupBusy) "Looking up..." else "Look up", primary = true) {
                    if (!lookupBusy) scope.launch {
                        lookupBusy = true
                        lookupResult = app.lookup.lookup(query, clanQuery.ifBlank { null })
                        lookupBusy = false
                    }
                }
            }
            lookupResult?.let { ResultCard(it) }

            Text("Saved scans", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            SwitchRow(
                title = "Save scans for debugging",
                body = "Each bubble tap keeps the screenshot, the part that was read, what was recognised and the lookup steps (last ${ScanRecorder.KEEP}).",
                checked = saveScans,
            ) { on -> scope.launch { app.settings.setSaveScans(on) } }
            Text(
                "Scans saved: ${scanStats.count} (%.1f MB)".format(scanStats.bytes / 1_048_576.0),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Pill("Export debug bundle", primary = true) {
                    scope.launch {
                        val share = app.recorder.exportBundle()
                        context.startActivity(Intent.createChooser(share, "Share debug bundle"))
                    }
                }
                Pill("Clear saved scans", primary = false) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.recorder.clear() }
                        scanStats = app.recorder.stats()
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun sizeLabel(scale: Float): String {
    val name = when {
        scale < 0.9f -> "Small"
        scale <= 1.1f -> "Medium"
        else -> "Large"
    }
    return "$name, %.2fx".format(scale)
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Royal.Night else Royal.Gold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Royal.Gold else Color.Transparent)
            .border(1.dp, Royal.Gold, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Royal.Navy)
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    // Local state keeps dragging smooth; the value is saved when the finger lifts.
    var local by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.bodyMedium, color = Royal.Gold)
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onChange(local) },
        valueRange = range,
        steps = steps,
        enabled = enabled,
        colors = SliderDefaults.colors(thumbColor = Royal.Gold, activeTrackColor = Royal.Gold, inactiveTrackColor = Royal.Slate),
    )
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Royal.Mist)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Royal.Gold, checkedThumbColor = Royal.Night),
        )
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, action: Pair<String, () -> Unit>?, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 16.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) Royal.Gold else Color.Transparent)
                .border(2.dp, Royal.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", color = Royal.Night, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Royal.Parchment)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Royal.Mist)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                Pill(action.first, primary = !done, onClick = action.second)
            }
        }
    }
}

@Composable
private fun RoyalField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Royal.Gold,
            unfocusedBorderColor = Royal.Slate,
            focusedLabelColor = Royal.Gold,
            cursorColor = Royal.Gold,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Royal.Night else Royal.Gold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Royal.Gold else Color.Transparent)
            .border(1.5.dp, Royal.Gold, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun ResultCard(r: LookupResult) {
    Column(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Royal.Navy)
            .padding(14.dp)
    ) {
        val s = r.stats
        if (s != null) {
            Text(s.name, style = MaterialTheme.typography.headlineSmall, color = Royal.Elixir)
            Text("#${s.tag}", style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
            s.clanName?.let { Text(it + (s.clanTag?.let { t -> "  #$t" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
            r.lastBattle?.let { b ->
                Spacer(Modifier.height(8.dp))
                Text("Last battle deck", style = MaterialTheme.typography.labelSmall, color = Royal.Mist)
                Box(Modifier.width(260.dp)) { DeckGrid(b) }
                Text(
                    listOfNotNull(b.mode, BattleLog.relative(b.time)).joinToString(", ") + " (${b.source})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Royal.Mist,
                )
                Spacer(Modifier.height(6.dp))
            }
            r.lastBattleError?.let { Text("Last battle: $it", style = MaterialTheme.typography.labelSmall, color = Royal.Gold) }
            val facts = listOfNotNull(
                s.trophies?.let { "Trophies $it" + (s.bestTrophies?.let { b -> " (best $b)" } ?: "") },
                s.expLevel?.let { "Level $it" },
                if (s.wins != null && s.losses != null) "Wins ${s.wins}, losses ${s.losses}" else null,
                s.threeCrownWins?.let { "Three-crown wins $it" },
                s.battleCount?.let { "Games $it" },
                s.arena,
                s.deck.takeIf { it.isNotEmpty() }?.let { "Deck: " + it.joinToString() },
            ) + s.extra.map { "${it.first}: ${it.second}" }
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Stats from ${s.source}", style = MaterialTheme.typography.labelSmall, color = Royal.Mist)
        } else {
            Text(r.error ?: "Not found", color = Royal.Danger, style = MaterialTheme.typography.titleMedium)
            if (r.needsVerification) Text(
                "RoyaleAPI wants a one-time browser check. Tap Verify RoyaleAPI, pass it, then look up again.",
                style = MaterialTheme.typography.bodySmall, color = Royal.Mist,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("How it was found", style = MaterialTheme.typography.labelSmall, color = Royal.Mist)
        r.log.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Royal.Mist) }
    }
}
