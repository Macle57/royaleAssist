package com.macle.royaleassist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.macle.royaleassist.cache.CachePolicy
import com.macle.royaleassist.cache.LookupCache
import com.macle.royaleassist.cache.PlayerEntity
import com.macle.royaleassist.cache.toResult
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.overlay.Panel
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.PillButton
import com.macle.royaleassist.ui.Royal
import com.macle.royaleassist.ui.RoyalTheme
import kotlinx.coroutines.launch

/** Every looked-up player (the lookup cache), newest first. */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RoyaleAssistApp
        setContent { RoyalTheme { HistoryScreen(app, onBack = { finish() }) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, HistoryActivity::class.java)

        fun openOnRoyaleApi(context: Context, tag: String) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RoyaleApiWeb.playerUrl(tag))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun HistoryScreen(app: RoyaleAssistApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by app.cache.history().collectAsState(initial = null)
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LookupResult?>(null) }
    val now = remember(rows) { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize().background(Royal.Night).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Royal.Gold) }
            Text("History", style = MaterialTheme.typography.headlineSmall, color = Royal.Gold, modifier = Modifier.weight(1f))
            if (!rows.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear history", color = Royal.Gold) }
        }
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "No players yet. Tap the bubble in a battle, or use the test lookup on the setup screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = Royal.Mist,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.tag }) { e ->
                    val cached = remember(e) { app.cache.decode(e) }
                    HistoryRow(
                        e, cached, now,
                        onOpen = { cached?.let { detail = it.toResult() } },
                        onWeb = { HistoryActivity.openOnRoyaleApi(context, e.tag) },
                        onDelete = { scope.launch { app.cache.delete(e.tag) } },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes every saved player and cached lookup.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { app.cache.clear() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = Royal.Navy,
        )
    }

    detail?.let { r ->
        Dialog(onDismissRequest = { detail = null }) {
            Panel(
                state = PanelState.Result(emptyList(), NamePick(null, null), r),
                pinned = true,
                onPin = {},
                onClose = { detail = null },
                onRetry = null,
                onPickLine = {},
                onVerify = { context.startActivity(com.macle.royaleassist.lookup.VerifyActivity.intent(context, r.stats?.name)) },
                alpha = 1f,
                deckOnly = r.lastBattle != null, // pinned: deck (with levels) above the stats
                onRefresh = { tag -> scope.launch { detail = app.lookup.refresh(tag) } },
                onOpenWeb = { tag -> HistoryActivity.openOnRoyaleApi(context, tag) },
                showCloseHint = false,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    e: PlayerEntity,
    cached: LookupCache.Cached?,
    now: Long,
    onOpen: () -> Unit,
    onWeb: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Royal.Navy)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, color = Royal.Elixir, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "#${e.tag}" + (e.clanName?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Royal.Parchment,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        e.trophies?.let { "%,d trophies".format(it) },
                        "scanned ${CachePolicy.age(e.lastSeenAt, now)}",
                        "seen ${e.seenCount}x",
                    ).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Royal.Mist,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${e.name}", tint = Royal.Mist) }
        }
        cached?.battle?.let { b ->
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(end = 10.dp)) {
                b.cards.take(8).forEach { c ->
                    AsyncImage(
                        model = c.iconUrl,
                        contentDescription = c.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).aspectRatio(5f / 6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box { PillButton("Open on RoyaleAPI", primary = false, onClick = onWeb) }
    }
}
