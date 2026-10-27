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

    Column(
        Modifier
            .fillMaxSize()
            .background(Royal.Night)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("royaleAssist", style = MaterialTheme.typography.headlineSmall, color = Royal.Gold)
        Text(
            "Tap the crown bubble in a battle and royaleAssist reads the opponent's name off the screen and looks them up.",
            style = MaterialTheme.typography.bodyMedium,
            color = Royal.Mist,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

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
        )

        Section("Scan region")
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Full-screen fallback", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If the region has no name, read the whole screen too. Slower.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Royal.Mist,
                )
            }
            Switch(
                checked = fullFallback,
                onCheckedChange = { on -> scope.launch { app.settings.setFullScreenFallback(on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = Royal.Gold, checkedThumbColor = Royal.Night),
            )
        }

        Section("Clash Royale API token (optional)")
        Text(
            "With a token the panel shows wins, losses and the current deck, and players can be found through their clan. " +
                "Create a key at developer.clashroyale.com with the IP ${OfficialApi.PROXY_IP} (the RoyaleAPI proxy).",
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

        Section("Test a lookup")
        Text(
            "Type a player name (or a #tag) to check the lookup without the game.",
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
            Pill("Verify RoyaleAPI", primary = false) { context.startActivity(VerifyActivity.intent(context, query)) }
        }
        lookupResult?.let { ResultCard(it) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
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
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Royal.Slate))
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
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
