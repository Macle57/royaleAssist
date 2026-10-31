package com.macle.royaleassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.L
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupOrigin
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.overlay.DeckPager
import com.macle.royaleassist.overlay.Panel
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.PillButton
import com.macle.royaleassist.overlay.decksFor
import com.macle.royaleassist.pipeline.Analyzer
import com.macle.royaleassist.pipeline.OcrOutcome
import com.macle.royaleassist.settings.OverlayPrefs
import com.macle.royaleassist.settings.Settings
import com.macle.royaleassist.text.fmt2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A screenshot scan started from outside the card (a `royaleassist://scan` link). [image] is the
 * already loaded screenshot, or null to let the user pick one; [id] makes repeats distinct.
 */
data class ScanRequest(val id: Long, val image: ArgbImage? = null, val label: String = "")

/**
 * "Scan a screenshot": the platform picks an image ([pickImage] calls back with null when the
 * user cancels or it cannot be read), the [analyzer] reads it like a capture (scan region, region
 * growing, second pass, full-screen fallback) and the result shows in the shared pinned [Panel]
 * (recent decks with arrows, then the stats; the decks are always fetched, [LookupOrigin.App]) with
 * the OCR lines to choose from. A new [request] scans its image right away.
 */
@Composable
fun ScreenshotScan(
    analyzer: Analyzer,
    lookup: LookupChain,
    settings: Settings,
    navigator: AppNavigator,
    pickImage: (onPicked: (ArgbImage?) -> Unit) -> Unit,
    request: ScanRequest? = null,
) {
    val scope = rememberCoroutineScope()
    val prefs by settings.overlayPrefs.collectAsState(initial = OverlayPrefs())
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<PanelState>(PanelState.Hidden) }
    val pager = remember { DeckPager() }
    val deckIndex by pager.index.collectAsState()
    fun show(s: PanelState) {
        pager.reset()
        state = s
    }

    fun scan(img: ArgbImage, label: String) {
        if (busy) return
        scope.launch {
            busy = true
            state = PanelState.Hidden
            try {
                status = "Reading the ${img.width}x${img.height} screenshot..."
                L.i("scan: $label ${img.width}x${img.height}")
                val region = settings.currentScanRegion()
                val o = withContext(Dispatchers.Default) { analyzer.scan(img, region, exclude = null, fullScreenFallback = true) }
                logOutcome(o)
                val name = o.pick.name
                if (name == null) {
                    status = "No name found (${o.lines.size} lines, ${o.millis} ms)"
                    show(PanelState.NotFound("No opponent name in the screenshot.", o.lines))
                    return@launch
                }
                val read = "Read '${name.text}'" + (o.pick.clan?.let { " / '${it.text}'" } ?: "") + " in ${o.millis} ms (${o.mode})"
                status = "$read; looking up..."
                val r = analyzer.lookupPick(o.pick, LookupOrigin.App)
                status = read
                L.i("scan: lookup -> '${r?.hit?.name}' #${r?.hit?.tag} via ${r?.strategy} stats=${r?.stats != null} verify=${r?.needsVerification} error=${r?.error}")
                show(panelFor(r, o.lines, o.pick))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e("scan: failed", e)
                status = "Scan failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun pickAndScan() {
        if (busy) return
        pickImage { picked -> if (picked != null) scan(picked, "picked image") else status = "No image picked" }
    }

    fun pickLine(line: OcrLine, lines: List<OcrLine>) {
        val pick = NamePicker.pickWithName(line, lines)
        L.i("scan: user picked '${line.text}' (clan '${pick.clan?.text}')")
        scope.launch {
            busy = true
            try {
                val r = analyzer.lookupPick(pick, LookupOrigin.App)
                show(panelFor(r, lines, pick))
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(request) {
        val q = request ?: return@LaunchedEffect
        val img = q.image
        if (img != null) scan(img, q.label) else pickAndScan()
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Royal.Navy).padding(16.dp)) {
        Text("Scan a screenshot", style = MaterialTheme.typography.titleMedium, color = Royal.Gold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick a battle screenshot: the opponent's name and clan are read on the device and looked up.",
            style = MaterialTheme.typography.bodySmall,
            color = Royal.Mist,
        )
        Row(Modifier.padding(top = 10.dp)) {
            PillButton(if (busy) "Scanning..." else "Choose screenshot", primary = true) { pickAndScan() }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Royal.Parchment)
        }
        val shown = state
        if (shown != PanelState.Hidden) {
            Spacer(Modifier.height(12.dp))
            Panel(
                state = shown,
                pinned = true,
                onPin = {},
                onClose = { state = PanelState.Hidden; status = null },
                onRetry = null,
                onPickLine = { line -> pickLine(line, shown.linesOrEmpty) },
                onVerify = {
                    val q = (shown as? PanelState.Result)?.result?.query ?: (shown as? PanelState.NotFound)?.pick?.name?.text
                    navigator.openVerify(q)
                },
                alpha = 1f,
                deckOnly = true,
                onRefresh = { _ ->
                    val res = (shown as? PanelState.Result)?.result
                    if (res != null) scope.launch { show(shown.copy(result = lookup.refresh(res, LookupOrigin.App))) }
                },
                onOpenWeb = { navigator.openPlayerPage(it) },
                showCloseHint = false,
                decksToShow = prefs.decksToShow,
                deckIndex = deckIndex,
                onDeckStep = { delta -> pager.step(delta, decksFor((shown as? PanelState.Result)?.result, prefs.decksToShow).size) },
            )
        }
    }
}

/**
 * The card for a lookup: a result, or a RoyaleAPI block (a result without stats, so the card offers
 * "Verify RoyaleAPI"), or "not found".
 */
private fun panelFor(r: LookupResult?, lines: List<OcrLine>, pick: NamePick): PanelState =
    if (r != null && (r.stats != null || r.needsVerification)) PanelState.Result(lines, pick, r)
    else PanelState.NotFound(r?.error ?: "Player not found", lines, pick)

/** Logs every merged line and each recognizer's raw reading (to compare with Android's corpus runs). */
private fun logOutcome(o: OcrOutcome) {
    L.i(
        "scan: name='${o.pick.name?.text}' (${o.pick.name?.source} ${o.pick.name?.confidence?.let { fmt2(it) }}) " +
            "clan='${o.pick.clan?.text}' mode=${o.mode} expanded=${o.expanded} scanned=${o.scanned} ${o.millis} ms: ${o.pick.reason}",
    )
    o.lines.forEach { l ->
        L.i("scan: LINE '${l.text}' src=${l.source} c=${fmt2(l.confidence)} warm=${fmt2(l.warmth)} box=(${l.box.left},${l.box.top},${l.box.right},${l.box.bottom})")
    }
    o.raw.forEach { (src, ls) ->
        L.i("scan: RAW $src: " + ls.joinToString(" | ") { "'${it.text}' c=${fmt2(it.confidence)} y=${it.box.top}" })
    }
    o.refinements.forEach { L.i("scan: REFINE $it") }
}
